#!/usr/bin/env python3
"""把 AIDE 打出来的 APK 里的 classes.dex 发布成热更新 payload。

用法：
    python3 tools/publish_dex.py                       # 默认取 ../dekk/app/build/bin/app.apk
    python3 tools/publish_dex.py --apk /sdcard/app.apk # 指定 APK
    python3 tools/publish_dex.py --upload root@1.2.3.4 # 传服务器（密码走环境变量 DEKK_SSH_PASS）

产出（放在 `要热更新的dex/`）：
    classes.dex   ← 上传到服务器的就是它
    version.txt   ← 内容为 classes.dex 的 md5，客户端拿它判断要不要重新下载

服务器上的最终位置：
    https://dekk.example.com/dex/classes.dex
    https://dekk.example.com/dex/version.txt
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent          # dekk热更新/
DEFAULT_APK = HERE.parent / 'dekk/app/build/bin/app.apk'
OUT_DIR = HERE / '要热更新的dex'
REMOTE_DIR = '/var/www/dekk.example.com/dex'
DEX_NAME = 'classes.dex'
VERSION_NAME = 'version.txt'


def md5_of(path):
    digest = hashlib.md5()
    with open(path, 'rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b''):
            digest.update(chunk)
    return digest.hexdigest()


def extract_dex(apk, out_dir):
    """从 APK 里取出 classes.dex（多 dex 直接报错，避免只发布一半）。"""
    if not apk.is_file():
        sys.exit('找不到 APK：%s' % apk)
    out_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(apk) as zf:
        names = [n for n in zf.namelist() if n.startswith('classes') and n.endswith('.dex')]
        names.sort()
        if not names:
            sys.exit('APK 里没有 dex：%s' % apk)
        if names != [DEX_NAME]:
            sys.exit('APK 里有多个 dex（%s）。热更新壳目前只认单个 classes.dex，'
                     '要么把模块拆小，要么改成同时发布多份并让壳按顺序加载。' % '、'.join(names))
        target = out_dir / DEX_NAME
        with zf.open(DEX_NAME) as src, open(target, 'wb') as dst:
            shutil.copyfileobj(src, dst)
    return target


def upload(dex, version_file, target):
    """用 scp 传到服务器；密码从环境变量 DEKK_SSH_PASS 取，绝不写进仓库。"""
    password = os.environ.get('DEKK_SSH_PASS', '')
    if not password:
        sys.exit('缺少环境变量 DEKK_SSH_PASS（服务器密码），拒绝上传')
    prefix = ['sshpass', '-p', password]
    ssh = prefix + ['ssh', '-o', 'StrictHostKeyChecking=no', target]
    scp = prefix + ['scp', '-o', 'StrictHostKeyChecking=no']
    subprocess.run(ssh + ['mkdir -p %s && chmod 755 %s' % (REMOTE_DIR, REMOTE_DIR)], check=True)
    subprocess.run(scp + [str(dex), str(version_file), '%s:%s/' % (target, REMOTE_DIR)], check=True)
    subprocess.run(ssh + ['chmod 644 %s/%s %s/%s' % (REMOTE_DIR, DEX_NAME, REMOTE_DIR, VERSION_NAME)],
                   check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, default=DEFAULT_APK)
    parser.add_argument('--upload', metavar='USER@HOST', default='')
    args = parser.parse_args()

    dex = extract_dex(args.apk, OUT_DIR)
    version = md5_of(dex)
    version_file = OUT_DIR / VERSION_NAME
    version_file.write_text(version + '\n', encoding='utf-8')

    size = dex.stat().st_size
    print('APK      : %s' % args.apk)
    print('dex      : %s（%d 字节）' % (dex, size))
    print('md5      : %s' % version)
    print('version  : %s' % version_file)

    if len(version) != 32 or size <= 0:
        sys.exit('生成的 dex 不合法（md5=%s，size=%d）' % (version, size))
    with open(dex, 'rb') as handle:
        if handle.read(4) != b'dex\n':
            sys.exit('生成的 dex 头不对，可能不是真正的 dex 文件：%s' % dex)
    if args.upload:
        upload(dex, version_file, args.upload)
        print('已上传   : https://dekk.example.com/dex/%s' % DEX_NAME)
        print('版本文件 : https://dekk.example.com/dex/%s' % VERSION_NAME)
    else:
        print('未上传（想要直接发布请加 --upload USER@HOST，密码放 DEKK_SSH_PASS）')
        print('手动上传示例：')
        print('  scp %s %s USER@HOST:%s/' % (dex, version_file, REMOTE_DIR))


if __name__ == '__main__':
    main()