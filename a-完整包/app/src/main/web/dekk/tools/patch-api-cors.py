#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""整理 DEKK 服务端接口（/API/index.php）的跨域处理。

v1 曾经把 CORS 头写在 PHP 里；后来发现 OPTIONS 预检根本进不了 PHP
（nginx 静态处理直接 405），于是改由 nginx 的 extension 配置统一加头
（见 tools/nginx-cors-preflight.conf）。两边都加会出现重复的
Access-Control-Allow-Origin，浏览器会报
"The 'Access-Control-Allow-Origin' header contains multiple values" 而拒绝响应。

本脚本因此做两件事：
  1) 删掉 PHP 里的 header('Access-Control-...') 相关行；
  2) 保留（必要时补上）OPTIONS 直接 204 的兜底短路。

用法（服务器上）：
    python3 patch-api-cors.py /var/www/dekk.example.com/API/index.php
"""
import re
import shutil
import sys
import time

ANCHOR = "ini_set('display_errors','0');"

OPTIONS_BLOCK = """ini_set('display_errors','0');
// DEKK 网页版跨域：CORS 头统一由 nginx 加（tools/nginx-cors-preflight.conf），这里只做兜底短路
if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
    http_response_code(204);
    exit;
}
"""

# PHP 侧写过 / 可能写过的 CORS 片段
REMOVE_PATTERNS = [
    r"// DEKK 网页版脚本跨域调用[^\n]*\n",
    r"\$dekkOrigin = \$_SERVER\['HTTP_ORIGIN'\] \?\? '';\n",
    r"if \(\$dekkOrigin !== ''\) \{(?:[^\n]*\n)*?\}\n",
    r"[^\n]*header\('Access-Control-Allow-Origin[^\n]*\n",
    r"[^\n]*header\('Vary: Origin'\);[^\n]*\n",
    r"[^\n]*header\('Access-Control-Allow-Headers[^\n]*\n",
    r"[^\n]*header\('Access-Control-Allow-Methods[^\n]*\n",
    r"[^\n]*header\('Access-Control-Max-Age[^\n]*\n",
    # v1 写的 OPTIONS 短路（含 header 行）
    r"if \(\(\$_SERVER\['REQUEST_METHOD'\] \?\? ''\) === 'OPTIONS'\) \{(?:[^\n]*\n)*?\}\n",
]


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else '/var/www/dekk.example.com/API/index.php'
    with open(path, 'r', encoding='utf-8') as handle:
        text = handle.read()

    original = text
    for pattern in REMOVE_PATTERNS:
        text = re.sub(pattern, '', text)

    if 'REQUEST_METHOD' not in text:
        text = text.replace(ANCHOR, OPTIONS_BLOCK, 1)

    if text == original:
        print('无需修改：%s' % path)
        return 0

    backup = '%s.bak.%d' % (path, int(time.time()))
    shutil.copyfile(path, backup)
    with open(path, 'w', encoding='utf-8') as handle:
        handle.write(text)
    print('已整理 PHP 跨域处理，备份：%s' % backup)
    return 0


if __name__ == '__main__':
    sys.exit(main())