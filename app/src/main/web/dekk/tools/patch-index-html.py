#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给官网首页（index.html）加一个「网页版脚本」入口。

在服务器上执行：
    python3 patch-index-html.py /var/www/dekk.example.com/index.html

会先备份成 index.html.bak.<时间戳>；已经改过就跳过。
"""
import shutil
import sys
import time

MARK = 'jsdekk/dekk.js'

SUBTITLE_OLD = '<p class="subtitle">仅支持 Android</p>'
SUBTITLE_NEW = '<p class="subtitle">安卓模块 + 网页版脚本（Via / 油猴）</p>'

BUTTON_ANCHOR = '<a href="dekk011.apk" class="btn btn-primary">安卓15及以下</a>'
BUTTON_NEW = (
    '<a href="jsdekk/dekk.js" class="btn btn-primary">网页版脚本 dekk.js</a>\n'
    '        ' + BUTTON_ANCHOR
)

SECTION_ANCHOR = '<p class="section-title">安装说明</p>'
SECTION_NEW = '''<p class="section-title">网页版脚本（苹果 / 安卓都能用）</p>
        <div class="info">
            <p>配套浏览器：Via 浏览器（苹果、安卓都有），自带脚本功能。</p>
            <p>1. Via 设置 → 脚本 → 添加脚本 → 从链接导入：<br>https://dekk.example.com/API/dekk.js</p>
            <p>2. 也可以直接下载 <a href="jsdekk/dekk.js">dekk.js</a> 后本地导入。</p>
            <p>3. 打开 chat.deepseek.com，点头像 → 「系统设置」下面就是「DEKK模块」。</p>
            <p>功能与安卓模块一致：设备状态提示词、补充上下文、一问多答、伪封号、AI控制封号、
               防撤回存档、聊天美化、历史对话全选、人设市场 / 插件中心 / 消息中心。</p>
        </div>

        <p class="section-title">安装说明</p>'''


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else '/var/www/dekk.example.com/index.html'
    with open(path, 'r', encoding='utf-8') as handle:
        text = handle.read()

    if MARK in text:
        print('首页已经加过脚本入口，跳过：%s' % path)
        return 0

    backup = '%s.bak.%d' % (path, int(time.time()))
    shutil.copyfile(path, backup)

    text = text.replace(SUBTITLE_OLD, SUBTITLE_NEW, 1)
    text = text.replace(BUTTON_ANCHOR, BUTTON_NEW, 1)
    text = text.replace(SECTION_ANCHOR, SECTION_NEW, 1)

    with open(path, 'w', encoding='utf-8') as handle:
        handle.write(text)
    print('首页已更新，备份：%s' % backup)
    return 0


if __name__ == '__main__':
    sys.exit(main())