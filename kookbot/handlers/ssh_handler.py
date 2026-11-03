#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/ssh 命令 — 以 www 权限在服务器上执行命令
"""

import subprocess
import asyncio

# 禁用的危险命令关键字
BLOCKED = {"rm -rf", "dd if=", "mkfs", ":(){", "fork bomb", "shutdown", "reboot",
           "halt", "poweroff", "/dev/sda", "/dev/nvme", "fdisk", "wget", "curl"}


async def handle_ssh(command: str) -> str:
    command = command.strip()
    if not command:
        return "请提供要执行的命令，例如: /ssh whoami"

    # 安全检查
    cmd_lower = command.lower()
    for bad in BLOCKED:
        if bad in cmd_lower:
            return f"[拒绝] 命令包含危险关键字: {bad}"

    # 限制长度
    if len(command) > 500:
        return "[拒绝] 命令过长（限500字符）"

    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, _run_as_www, command)


def _run_as_www(command: str) -> str:
    try:
        r = subprocess.run(
            ["sudo", "-u", "www", "bash", "-c", command],
            capture_output=True, text=True, timeout=15,
        )
        out = r.stdout.strip()
        err = r.stderr.strip()

        lines = [f"执行: {command}", "-" * 30]
        if out:
            lines.append(out[:1500])
        if err:
            lines.append(f"[stderr] {err[:500]}")
        if not out and not err:
            lines.append("(无输出)")
        lines.append(f"\n返回码: {r.returncode}")
        return "\n".join(lines)
    except subprocess.TimeoutExpired:
        return f"执行超时: {command}"
    except FileNotFoundError:
        return "[错误] sudo 命令不可用"
    except Exception as e:
        return f"[错误] {e}"