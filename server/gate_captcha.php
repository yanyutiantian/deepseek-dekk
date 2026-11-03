<?php
/**
 * 图片验证码渲染（4 位纯数字）。
 *
 * 设计约束：
 * · 答案**只存在服务端**，图片里不夹带任何明文/可逆编码；
 * · 每次请求都重新随机颜色、位置抖动、干扰线、噪点，同一答案两次渲染也不相同
 *   （防「反复刷图取平均」这类降噪攻击）；
 * · 不写任何缓存头，浏览器/代理都不留副本。
 *
 * 依赖：GD（已在线上确认存在）。若 imagettftext 可用则用 TTF 字体更清晰，
 * 否则回退 imagestring 内置字体 —— 两条路都能出图，不会因为缺字体白屏。
 */
declare(strict_types=1);

function gate_captcha_png(string $code): void {
    $W = 132;
    $H = 48;

    $im = imagecreatetruecolor($W, $H);
    if ($im === false) {
        http_response_code(500);
        exit;
    }
    $bg = imagecolorallocate($im, 246, 247, 249);
    imagefilledrectangle($im, 0, 0, $W - 1, $H - 1, $bg);

    // ── 干扰线（画在数字下面，避免盖住笔画）──
    for ($i = 0; $i < 4; $i++) {
        $lc = imagecolorallocate($im, random_int(150, 205), random_int(150, 205), random_int(150, 205));
        imageline($im, random_int(0, $W - 1), random_int(0, $H - 1), random_int(0, $W - 1), random_int(0, $H - 1), $lc);
    }

    $font = '';
    foreach ([
        '/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf',
        '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
        '/usr/share/fonts/dejavu-lgc/DejaVuSans-Bold.ttf',
        '/usr/share/fonts/wqy-zenhei/wqy-zenhei.ttc',
    ] as $f) {
        if (is_file($f)) { $font = $f; break; }
    }

    $slotW = (int)ceil(($W - 16) / 4);
    for ($i = 0; $i < 4; $i++) {
        $ch = $code[$i];
        // 每位数一个独立随机前景色（深色系，保证对比度）
        $c = imagecolorallocate($im, random_int(18, 80), random_int(18, 80), random_int(18, 80));
        $x = 8 + $i * $slotW + random_int(-1, 3);
        if ($font !== '') {
            $size  = random_int(22, 26);
            $angle = random_int(-12, 12);
            $y     = 34 + random_int(-3, 3);
            imagettftext($im, $size, $angle, $x, $y, $c, $font, $ch);
        } else {
            // 回退：内置 5 号字体（9x15），画两遍模拟加粗
            $y = 16 + random_int(-2, 2);
            imagestring($im, 5, $x, $y, $ch, $c);
            imagestring($im, 5, $x + 1, $y, $ch, $c);
        }
    }

    // ── 噪点（画在数字之后，少量，兼顾可读性）──
    for ($i = 0; $i < 220; $i++) {
        $nc = imagecolorallocate($im, random_int(120, 210), random_int(120, 210), random_int(120, 210));
        imagesetpixel($im, random_int(0, $W - 1), random_int(0, $H - 1), $nc);
    }

    header('Content-Type: image/png');
    header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
    header('Pragma: no-cache');
    header('X-Content-Type-Options: nosniff');
    imagepng($im);
    imagedestroy($im);
}