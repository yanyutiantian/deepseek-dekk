#!/usr/bin/env python3
"""静态体检：payload dex 引用的类，运行时到底找不找得到。

反射调用的异常被包在 InvocationTargetException 里时，最常见的原因就是「某个类的依赖缺失」
（NoClassDefFoundError / IllegalAccessError）。这个脚本把 dex 里引用到的所有类型
和「一定能看到的类」做差集：

    dex 自己的类 + android.jar 里的类 + Xposed API 里的类 + 壳 APK 里的类
    （host 的类不参与，因为模块里对宿主只用字符串引用）

用法：
    python3 tools/check_dex_deps.py                       # 默认查 `要热更新的dex/classes.dex`
    python3 tools/check_dex_deps.py --dex xxx.dex --shell-apk ../dekk热更新.apk
    python3 tools/check_dex_deps.py --apk ../dekk/app/build/bin/app.apk   # 直接从 APK 抽
"""
import argparse
import struct
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
ANDROID_JAR = Path('/sdcard/android_check.jar')
XPOSED_JAR = HERE / 'app/libs/compile_only/xposed-api-82_compileonly.jar'
DEFAULT_DEX = HERE / '要热更新的dex/classes.dex'

# 一定存在的（boot classpath），Java 核心库不算缺失
BOOT_PREFIXES = ('java.', 'javax.', 'sun.', 'org.w3c.', 'org.xml.', 'org.json.', 'org.apache.http.')


def uleb128(data, offset):
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7f) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7


def dex_types(path):
    """返回 (dex 里定义的类型, dex 里引用到的类型)。"""
    data = Path(path).read_bytes()
    if data[:4] != b'dex\n':
        sys.exit('不是 dex 文件：%s' % path)
    string_ids_size, string_ids_off = struct.unpack_from('<II', data, 56)
    type_ids_size, type_ids_off = struct.unpack_from('<II', data, 64)
    class_defs_size, class_defs_off = struct.unpack_from('<II', data, 96)

    strings = []
    for i in range(string_ids_size):
        offset = struct.unpack_from('<I', data, string_ids_off + i * 4)[0]
        _, offset = uleb128(data, offset)
        end = data.index(b'\x00', offset)
        strings.append(data[offset:end].decode('utf-8', 'replace'))

    types = []
    for i in range(type_ids_size):
        index = struct.unpack_from('<I', data, type_ids_off + i * 4)[0]
        types.append(strings[index])

    defined = set()
    for i in range(class_defs_size):
        class_idx = struct.unpack_from('<I', data, class_defs_off + i * 32)[0]
        defined.add(types[class_idx])
    return defined, set(types)


def descriptor_to_name(descriptor):
    if descriptor.startswith('L') and descriptor.endswith(';'):
        return descriptor[1:-1].replace('/', '.')
    return None                       # 数组/基本类型不参与类名比对


def jar_classes(path):
    names = set()
    if not Path(path).is_file():
        return names
    with zipfile.ZipFile(path) as zf:
        for entry in zf.namelist():
            if entry.endswith('.class'):
                names.add(entry[:-6].replace('/', '.'))
    return names


def apk_dex_classes(apk):
    with zipfile.ZipFile(apk) as zf:
        names = [n for n in zf.namelist() if n.startswith('classes') and n.endswith('.dex')]
        if not names:
            return set(), []
        entries = sorted(names)
        tmp = Path('/tmp/depprobe.dex')
        with zf.open(entries[0]) as src, open(tmp, 'wb') as dst:
            dst.write(src.read())
    defined, _ = dex_types(tmp)
    return {descriptor_to_name(d) for d in defined if descriptor_to_name(d)}, entries


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dex', type=Path, default=DEFAULT_DEX)
    parser.add_argument('--apk', type=Path, default=None, help='直接从 APK 的 classes.dex 检查')
    parser.add_argument('--shell-apk', type=Path, default=None, help='壳 APK（补上 R / BuildConfig / Hot* 这些类）')
    args = parser.parse_args()

    dex_path = args.dex
    if args.apk:
        with zipfile.ZipFile(args.apk) as zf:
            entries = sorted(n for n in zf.namelist() if n.startswith('classes') and n.endswith('.dex'))
            if not entries:
                sys.exit('APK 里没有 dex：%s' % args.apk)
            with zf.open(entries[0]) as src, open('/tmp/depprobe.dex', 'wb') as dst:
                dst.write(src.read())
        dex_path = Path('/tmp/depprobe.dex')
        print('APK      : %s（使用 %s）' % (args.apk, entries[0]))

    defined_raw, referenced_raw = dex_types(dex_path)
    defined = {descriptor_to_name(d) for d in defined_raw if descriptor_to_name(d)}
    referenced = {descriptor_to_name(d) for d in referenced_raw if descriptor_to_name(d)}

    known = set(defined)
    known |= jar_classes(ANDROID_JAR)
    known |= jar_classes(XPOSED_JAR)
    if args.shell_apk:
        shell, entries = apk_dex_classes(args.shell_apk)
        known |= shell
        print('壳 APK   : %s（%s，%d 个类）' % (args.shell_apk, '、'.join(entries), len(shell)))

    missing = sorted(n for n in referenced - known
                     if not n.startswith(BOOT_PREFIXES))
    print('dex      : %s' % dex_path)
    print('定义类   : %d' % len(defined))
    print('引用类   : %d' % len(referenced))
    print('比对范围 : dex + android.jar + xposed api%s' % (' + 壳 APK' if args.shell_apk else ''))
    if missing:
        print('\n⚠️  找不到的引用（运行时很可能 NoClassDefFoundError）：')
        for name in missing:
            print('   ' + name)
        print('\n提示：如果这些是宿主（DeepSeek）里的类，模块必须用字符串 + XposedHelpers.findClass 取，')
        print('      不能直接写 className.class / new，否则加载 dex 时就会炸。')
    else:
        print('\nOK：没有找不到的引用（宿主类不会出现在这里，模块只用字符串引用宿主）')


if __name__ == '__main__':
    main()