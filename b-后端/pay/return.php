<?php
declare(strict_types=1);

/**
 * 支付完成后回跳落地页：
 *   已支付 → 「支付成功，已升级为赞助用户」
 *   未支付 → 自动刷新等待回调
 *   过期/无效 → 提示重新获取链接
 */
require __DIR__ . '/lib.php';

plimit('pay-return:'.($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 60, 60);

$outTradeNo = isset($_REQUEST['out_trade_no']) ? trim((string) $_REQUEST['out_trade_no']) : '';
$order = null;
$error = '';

if ($outTradeNo === '') {
    $error = '缺少订单号，请回到 KOOK 频道重新发送 /pay 你的邮箱 获取充值链接。';
} else {
    expire_orders();
    try {
        $stmt = db()->prepare('SELECT * FROM pay_orders WHERE out_trade_no=? LIMIT 1');
        $stmt->execute([$outTradeNo]);
        $order = $stmt->fetch();
    } catch (Throwable $e) {
        $error = '查询订单失败，请稍后刷新页面。';
    }
    if (!$order && $error === '') {
        $error = '没有找到这个订单，请回到 KOOK 频道重新发送 /pay 你的邮箱。';
    }
}

$paid = $order && $order['status'] === 'paid';
$pending = $order && $order['status'] === 'pending';
$expired = $order && $order['status'] === 'expired';

function mask_email(string $email): string
{
    $at = strpos($email, '@');
    if ($at === false || $at <= 0) {
        return $email;
    }
    $name = substr($email, 0, $at);
    $shown = strlen($name) <= 2 ? substr($name, 0, 1) : substr($name, 0, 2);
    return $shown . '***' . substr($email, $at);
}

function h($value): string
{
    return htmlspecialchars((string) $value, ENT_QUOTES, 'UTF-8');
}
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>dekk 自助赞助</title>
    <?php if ($pending): ?>
        <meta http-equiv="refresh" content="5">
    <?php endif; ?>
    <style>
        * { box-sizing: border-box; }
        body {
            margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
            background: #0f1115; color: #e8eaed; padding: 24px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
        }
        .card {
            width: 100%; max-width: 420px; background: #171a21; border: 1px solid #262b36;
            border-radius: 18px; padding: 28px 24px 24px; text-align: center;
            box-shadow: 0 18px 50px rgba(0,0,0,.45);
        }
        .icon {
            width: 62px; height: 62px; line-height: 62px; margin: 0 auto 16px; border-radius: 50%;
            font-size: 32px; font-weight: 700;
        }
        .icon.ok { background: rgba(52,199,89,.15); color: #34c759; border: 1px solid rgba(52,199,89,.4); }
        .icon.wait { background: rgba(255,193,7,.13); color: #ffc107; border: 1px solid rgba(255,193,7,.35); }
        .icon.bad { background: rgba(255,69,58,.13); color: #ff453a; border: 1px solid rgba(255,69,58,.35); }
        h1 { font-size: 21px; margin: 0 0 6px; font-weight: 600; }
        .sub { margin: 0 0 18px; color: #9aa3b2; font-size: 14px; line-height: 1.6; }
        ul { list-style: none; margin: 0 0 16px; padding: 14px 16px; background: #12151b; border-radius: 12px; text-align: left; }
        li { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; padding: 5px 0; }
        li span { color: #8b93a1; flex: none; }
        li b { font-weight: 500; word-break: break-all; text-align: right; }
        .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
        .tip { margin: 0; font-size: 12.5px; color: #7f8797; line-height: 1.7; }
        .brand { margin-top: 18px; font-size: 11.5px; color: #5c6373; letter-spacing: .4px; }
    </style>
</head>
<body>
<div class="card">
    <?php if ($paid): ?>
        <div class="icon ok">✓</div>
        <h1>支付成功</h1>
        <p class="sub">已升级为赞助用户，感谢你的支持！</p>
        <ul>
            <li><span>账号</span><b><?= h(mask_email((string) $order['email'])) ?></b></li>
            <li><span>金额</span><b>¥<?= h($order['money']) ?></b></li>
            <li><span>订单号</span><b class="mono"><?= h($order['out_trade_no']) ?></b></li>
            <li><span>完成时间</span><b><?= h($order['paid_at']) ?></b></li>
        </ul>
        <p class="tip">KOOK 频道的「赞助用户」身份会在几秒内自动发放，稍后回到频道查看即可。</p>
    <?php elseif ($pending): ?>
        <div class="icon wait">…</div>
        <h1>正在确认支付结果</h1>
        <p class="sub">如果你已经完成支付，请稍等几秒 —— 本页每 5 秒自动刷新一次。</p>
        <ul>
            <li><span>订单号</span><b class="mono"><?= h($outTradeNo) ?></b></li>
            <li><span>金额</span><b>¥<?= h(isset($order['money']) ? $order['money'] : '20.00') ?></b></li>
        </ul>
        <p class="tip">若长时间仍显示本页，请回到 KOOK 频道联系管理员。</p>
    <?php else: ?>
        <div class="icon bad">!</div>
        <h1><?= $expired ? '订单已过期' : '订单无效' ?></h1>
        <p class="sub"><?= h($error !== '' ? $error
                : '这个充值链接已超过 5 分钟有效期，请回到 KOOK 频道重新发送 /pay 你的邮箱 获取新链接。') ?></p>
    <?php endif; ?>
    <div class="brand">dekk.example.com</div>
</div>
</body>
</html>