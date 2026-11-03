<?php
declare(strict_types=1);
/**
 * 公开内容（人设 / 插件）的 AI 审核。
 * 一次请求同时审完「名称 / 简介 / 提示词」，不合规直接 fail()，把违规字段回给客户端。
 * 私密内容不审核（调用方只在 visibility=public 时调用）。
 *
 * 接口：https://your-review-api.example.com/v1/chat/completions
 * 必需参数：apikey（密钥）、model（模型名）、qq（调用方标识）、msg（提示词）
 * 返回：OpenAI 风格 JSON，正文在 choices[0].message.content
 */
const DEKK_REVIEW_ENDPOINT = 'https://your-review-api.example.com/v1/chat/completions';
const DEKK_REVIEW_MODEL    = 'your-model-name';
const DEKK_REVIEW_QQ       = '10000';
const DEKK_REVIEW_TIMEOUT  = 40;

/**
 * 审核密钥改从 config.php 读取，不再硬编码在源码里 ——
 * 万一仓库外泄，也不会连带把 key 一起交出去。
 */
function dekk_review_apikey(): string {
    static $key = null;
    if ($key === null) {
        $key = '';
        try {
            $cfg = require __DIR__ . '/config.php';
            if (is_array($cfg) && isset($cfg['review_apikey'])) {
                $key = (string) $cfg['review_apikey'];
            }
        } catch (Throwable $e) {
            $key = '';
        }
    }
    return $key;
}

function dekk_review_prompt(string $name, string $intro, string $prompt): string {
    // 给网页版千问用：结论式、极简，避免它自创字段名
    return "你是内容安全审核员。下面是用户要公开分享的【人设/插件】内容（含名称、简介、提示词）。\n"
        . "必须拒绝（回复 UNSAFE）：涉及未成年人的性相关或恋爱性暗示、露骨色情描写、色情交易、"
        . "严重血腥虐杀肢解、教唆犯罪、自残自杀、仇恨歧视、政治敏感。\n"
        . "其余情况（轻微暴力、轻微擦边、暧昧玩笑、口语化、虚构冲突、黑帮/悬疑/战争虚构题材）一律放行（回复 SAFE）。\n"
        . "要求：第一行只回复 SAFE 或 UNSAFE 一个词；如果是 UNSAFE，第二行用不超过20个汉字说明原因。不要输出任何其他内容。\n"
        . "===待审内容开始===\n"
        . "名称：" . $name . "\n"
        . "简介：" . $intro . "\n"
        . "提示词：" . $prompt . "\n"
        . "===待审内容结束===";
}

function dekk_review_field_label(string $field): string {
    switch ($field) {
        case 'name': return '名称';
        case 'intro': return '简介';
        case 'description': return '简介';
        case 'prompt': return '提示词';
        default: return '内容';
    }
}

/** 本地千问审核服务（Playwright 常驻真实页面，自动过 AWSC 签名/风控）。 */
const DEKK_REVIEW_LOCAL = 'http://127.0.0.1:8790/review';
const DEKK_REVIEW_LOCAL_HEALTH = 'http://127.0.0.1:8790/health';

/**
 * 调本地千问服务发一条消息，返回回答文本。
 * @return array{ok:bool,text:string,error:string}
 */
function dekk_qwen_ask(string $msg, int $timeout = 90): array {
    $out = ['ok' => false, 'text' => '', 'error' => ''];
    if (!function_exists('curl_init')) { $out['error'] = '服务器缺少 curl 扩展'; return $out; }
    $ch = curl_init(DEKK_REVIEW_LOCAL);
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => json_encode(['text' => $msg], JSON_UNESCAPED_UNICODE),
        CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CONNECTTIMEOUT => 5,
        CURLOPT_TIMEOUT => $timeout,
    ]);
    $raw = curl_exec($ch);
    $err = curl_error($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if ($raw === false || $raw === '') {
        error_log('[DEKK review] 本地千问服务不可用 code=' . $code . ' err=' . $err);
        $out['error'] = '审核服务暂时不可用（本地服务无响应）';
        return $out;
    }
    $decoded = json_decode((string) $raw, true);
    if (!is_array($decoded) || empty($decoded['ok'])) {
        $msg2 = is_array($decoded) ? (string) ($decoded['msg'] ?? '') : '';
        error_log('[DEKK review] 本地千问服务返回失败: ' . substr((string) $raw, 0, 200));
        $out['error'] = '审核服务暂时不可用' . ($msg2 !== '' ? '（' . substr($msg2, 0, 40) . '）' : '');
        return $out;
    }
    $out['ok'] = true;
    $out['text'] = (string) ($decoded['text'] ?? '');
    return $out;
}

/**
 * @return array{ok:bool,field:string,reason:string,error:string}
 *         error 非空表示审核服务本身失败（网络/解析），此时 ok=false。
 */
function reviewContent(string $name, string $intro, string $prompt): array {
    $out = ['ok' => false, 'field' => '', 'reason' => '', 'error' => ''];

    $msg = dekk_review_prompt($name, $intro, $prompt);
    $r = dekk_qwen_ask($msg, 150);
    if (!$r['ok']) { $out['error'] = $r['error'] !== '' ? $r['error'] : '审核服务暂时不可用'; return $out; }

    $text = trim($r['text']);
    // 格式：第一行 SAFE / UNSAFE，UNSAFE 时第二行是原因
    $lines = array_values(array_filter(array_map('trim', preg_split('/\r\n|\r|\n/', $text)), function ($x) { return $x !== ''; }));
    $first = $lines[0] ?? '';
    $upper = strtoupper($first);
    if (strpos($upper, 'UNSAFE') !== false || strpos($upper, '不安全') !== false || strpos($upper, '违规') !== false) {
        $out['ok'] = false;
        $out['field'] = '';
        $reason = $lines[1] ?? '';
        if ($reason === '') {
            $reason = trim(str_ireplace(['UNSAFE', 'unsafe', '不安全', '违规', '：', ':'], '', $first));
        }
        // 原因里如果混进了 SAFE 以外的噪声，截断保护
        $out['reason'] = mb_substr($reason, 0, 40, 'UTF-8');
        if ($out['reason'] === '') $out['reason'] = '内容不符合公开要求';
        return $out;
    }
    if (strpos($upper, 'SAFE') !== false || strpos($first, '安全') !== false) {
        $out['ok'] = true;
        return $out;
    }
    // 没按约定返回：保守起见不判违规，但把原文记录下来，交由上层决定
    error_log('[DEKK review] 审核结果非约定格式: ' . substr($text, 0, 200));
    $out['ok'] = true;
    return $out;
}

/** 审核公开内容；不合规或审核服务不可用时直接返回错误响应。 */
function reviewOrFail(string $kind, string $name, string $intro, string $prompt): void {
    $result = reviewContent($name, $intro, $prompt);
    if ($result['error'] !== '') {
        fail($kind.'审核服务暂时不可用，请稍后再试，或先保存为私密', 503);
    }
    if (!$result['ok']) {
        $reason = $result['reason'] !== '' ? '（'.$result['reason'].'）' : '';
        fail($kind.'审核未通过：'.dekk_review_field_label($result['field']).'违规'.$reason);
    }
}