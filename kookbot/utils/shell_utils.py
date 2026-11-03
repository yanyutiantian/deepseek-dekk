#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
系统命令工具 — 本地执行 shell 命令
所有子进程调用均使用列表参数，严禁 shell=True 拼接用户输入
"""

import subprocess
import re
import shlex

from config import PING_TIMEOUT


def ping_host(target: str, count: int = 4) -> tuple[str, str | None]:
    """
    Ping 目标主机，返回完整输出和平均延迟。
    使用列表参数调用 subprocess，杜绝命令注入。

    Returns:
        (ping_output, avg_rtt_str_or_None)
    """
    # 安全校验：target 仅允许字母数字、点、冒号、连字符（IPv4/IPv6/域名）
    if not re.fullmatch(r"[a-zA-Z0-9.\-:]+", target):
        return "参数包含非法字符，已拒绝", None

    try:
        r = subprocess.run(
            ["ping", "-c", str(count), "-W", "3", target],
            capture_output=True, text=True, timeout=PING_TIMEOUT,
        )
        out = r.stdout.strip()
        err = r.stderr.strip()
    except subprocess.TimeoutExpired:
        return "Ping 超时", None
    except FileNotFoundError:
        return "ping 命令不可用", None

    if err and not out:
        return err, None

    avg_rtt = None
    match = re.search(r"rtt.*=\s*[\d.]+/([\d.]+)/[\d.]+/[\d.]+\s*ms", out)
    if match:
        avg_rtt = match.group(1) + " ms"
    return out, avg_rtt


def run_command_safe(argv: list[str], timeout: int = PING_TIMEOUT) -> tuple[str, str]:
    """
    安全执行命令（列表参数，不使用 shell）。

    Returns:
        (stdout, stderr)
    """
    try:
        r = subprocess.run(
            argv, shell=False,
            capture_output=True, text=True,
            timeout=timeout,
        )
        return r.stdout.strip(), r.stderr.strip()
    except subprocess.TimeoutExpired:
        return "", "命令执行超时"
    except FileNotFoundError:
        return "", f"命令不可用: {argv[0]}"
