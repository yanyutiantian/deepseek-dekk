#!/usr/bin/env python3
"""把门面类从主模块同步到壳。

门面（HotHook / HotParam / HotHooks / HotUnhook / HotMethodReplacement / HotBridge / HotHelpers / HotApp）
在两个工程里各有一份，而且**必须是同一份**：

  * payload（主模块构建出来的 dex）里带着一份 → 保证 payload 能独立编译；
  * 壳里那份才是在热更新时真正跑的（payload 的类加载器父链优先命中壳的版本，
    而壳的 dex 会被 LSPosed 重写，只有它能真的调用 Xposed API）。

所以改门面时：改主模块那份 → 跑这个脚本 → 重新构建壳 + 重新发布 dex（两边都要更新）。
"""
import hashlib
import shutil
import sys
from pathlib import Path

MAIN_SRC = Path('/storage/emulated/0/AideProjects/dekk/app/src/main/java/com/dekk/yanyu')
HOT_SRC = Path('/storage/emulated/0/AideProjects/dekk热更新/app/src/main/java/com/dekk/yanyu')
FACADE = ['HotApp.java', 'HotBridge.java',
           'HotHelpers.java', 'HotHook.java',
          'HotHooks.java', 'HotMethodReplacement.java', 'HotParam.java', 'HotUnhook.java']


def md5(path):
    return hashlib.md5(path.read_bytes()).hexdigest()


def main():
    check = '--check' in sys.argv
    changed = []
    for name in FACADE:
        src = MAIN_SRC / name
        dst = HOT_SRC / name
        if not src.is_file():
            sys.exit('主模块缺少门面文件：%s' % src)
        if dst.is_file() and md5(src) == md5(dst):
            continue
        changed.append(name)
        if not check:
            shutil.copy2(src, dst)
    if check:
        if changed:
            sys.exit('门面不一致：%s（跑 tools/sync_facade.py 修）' % '、'.join(changed))
        print('OK 门面两份一致')
        return
    if changed:
        print('已同步：%s' % '、'.join(changed))
    else:
        print('门面本来就是一致的')


if __name__ == '__main__':
    main()