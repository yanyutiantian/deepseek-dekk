#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/xt — 服务器运行状态：CPU / 内存 / 磁盘 / 进程 / Nginx / MySQL
"""

import subprocess
import asyncio


async def handle_xt() -> str:
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _xt_info)


def _xt_info() -> str:
    lines = ["服务器状态", "=" * 30]

    # CPU
    cpu = _run("top -bn1 | grep 'Cpu(s)' | awk '{print $2+$4\"%\"}'")
    load = _run("cat /proc/loadavg | awk '{print $1,$2,$3}'")
    lines.append(f"CPU 使用率: {cpu or 'N/A'}")
    lines.append(f"系统负载 (1/5/15min): {load or 'N/A'}")

    # 内存
    mem = _run("free -m | awk '/Mem:/{printf \"%dM / %dM (%.1f%%)\", $3, $2, $3*100/$2}'")
    lines.append(f"内存: {mem or 'N/A'}")

    swap = _run("free -m | awk '/Swap:/{printf \"%dM / %dM\", $3, $2}'")
    if swap:
        lines.append(f"Swap: {swap}")

    # 磁盘
    disk = _run("df -h / | awk 'NR==2{print $3\"/\"$2\" (\"$5\")\"}'")
    lines.append(f"磁盘 (/): {disk or 'N/A'}")

    # 进程
    procs = _run("ps aux --no-headers | wc -l")
    lines.append(f"进程总数: {procs or 'N/A'}")

    # Nginx
    nginx = _run("systemctl is-active nginx 2>/dev/null || echo 'not installed'")
    lines.append(f"Nginx: {nginx or 'N/A'}")

    # MySQL/MariaDB
    mysql = _run("systemctl is-active mysql 2>/dev/null || systemctl is-active mariadb 2>/dev/null || echo 'not installed'")
    lines.append(f"MySQL: {mysql or 'N/A'}")

    # 运行时间
    uptime_str = _run("uptime -p")
    if uptime_str:
        lines.append(f"运行时间: {uptime_str}")

    return "\n".join(lines)


def _run(cmd: str) -> str:
    """执行内置命令（无用户输入，仅用于 /xt 自身监控，保留 shell=True 处理管道）"""
    try:
        r = subprocess.run(
            cmd, shell=True, capture_output=True, text=True, timeout=10,
            executable="/bin/bash",
        )
        return r.stdout.strip()
    except Exception:
        return ""