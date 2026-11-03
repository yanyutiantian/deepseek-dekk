#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
网络工具模块
— DNS 解析
— TCP/HTTP 请求测时
— 域名处理
"""

import re
import time
import socket
import ssl
import requests
from urllib.parse import urlparse

from config import HTTP_TIMEOUT, SOCKET_TIMEOUT


def resolve_host(target: str) -> str | None:
    """
    DNS 解析域名 → IP。
    如果 target 已经是 IP 则直接返回。
    """
    # 简单判断是否为 IP
    ip_pattern = re.compile(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$")
    if ip_pattern.match(target):
        return target
    try:
        return socket.gethostbyname(target)
    except socket.gaierror:
        return None


def measure_tcp_http(host: str, port: int = 80, use_tls: bool = False) -> dict:
    """
    建立 TCP 连接 → 发送 HTTP GET → 接收完整响应，测量耗时。

    Returns:
        {
            "connect_ms": TCP握手耗时(ms),
            "total_ms": 总耗时(ms),
            "total_s": 总耗时(秒),
            "status_code": HTTP状态码,
            "body_size": 响应体字节数,
            "error": 错误信息或None
        }
    """
    start = time.time()
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(SOCKET_TIMEOUT)

    try:
        # TCP 三次握手
        sock.connect((host, port))
        connect_time = (time.time() - start) * 1000

        # TLS 握手（如需要）
        if use_tls:
            context = ssl.create_default_context()
            sock = context.wrap_socket(sock, server_hostname=host)
            tls_time = (time.time() - start) * 1000
        else:
            tls_time = connect_time

        # 发送 HTTP GET
        request = (
            f"GET / HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            f"User-Agent: Mozilla/5.0 (compatible; QQBot/1.0)\r\n"
            f"Connection: close\r\n"
            f"\r\n"
        )
        sock.send(request.encode())

        # 接收完整响应
        response = b""
        while True:
            try:
                data = sock.recv(4096)
                if not data:
                    break
                response += data
            except socket.timeout:
                break

        total_time = (time.time() - start) * 1000

        # 解析 HTTP 状态码
        status_code = 0
        header_end = response.find(b"\r\n\r\n")
        if header_end != -1:
            status_line = response[: header_end].split(b"\r\n")[0].decode(errors="replace")
            code_match = re.search(r"HTTP/\S+\s+(\d{3})", status_line)
            if code_match:
                status_code = int(code_match.group(1))

        return {
            "connect_ms": round(connect_time, 2),
            "total_ms": round(total_time, 2),
            "total_s": round(total_time / 1000, 3),
            "status_code": status_code,
            "body_size": len(response),
            "error": None,
        }

    except Exception as e:
        return {
            "connect_ms": 0,
            "total_ms": 0,
            "total_s": 0,
            "status_code": 0,
            "body_size": 0,
            "error": str(e),
        }
    finally:
        try:
            sock.close()
        except Exception:
            pass


def get_root_domain(domain: str) -> str:
    """
    将二级/三级域名拆为一级域名。
    例: www.example.com → example.com
         api.sub.example.co.uk → example.co.uk
    """
    # 移除协议前缀
    domain = re.sub(r"^https?://", "", domain)
    # 移除路径
    domain = domain.split("/")[0]
    # 移除端口
    domain = domain.split(":")[0]

    # 常见二级 TLD 后缀列表
    known_second_tlds = {
        "co.uk", "ac.uk", "gov.uk", "org.uk", "net.uk", "sch.uk",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "com.au", "net.au", "org.au", "gov.au", "edu.au",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "com.br", "net.br", "org.br", "gov.br",
        "co.kr", "or.kr", "ne.kr", "go.kr",
        "com.tw", "net.tw", "org.tw", "gov.tw",
        "co.in", "net.in", "org.in", "gov.in", "firm.in",
    }

    parts = domain.split(".")
    if len(parts) <= 2:
        return domain

    # 检查最后两段是否为已知二级 TLD
    last_two = ".".join(parts[-2:]).lower()
    if last_two in known_second_tlds:
        if len(parts) >= 3:
            return ".".join(parts[-3:])
        return domain

    # 默认返回最后两段
    return ".".join(parts[-2:])


def download_image(url: str, save_path: str) -> bool:
    """下载图片到本地，返回是否成功。"""
    try:
        resp = requests.get(url, timeout=HTTP_TIMEOUT, headers={
            "User-Agent": "Mozilla/5.0 (compatible; QQBot/1.0)",
        })
        if resp.status_code == 200:
            with open(save_path, "wb") as f:
                f.write(resp.content)
            return True
        return False
    except Exception:
        return False


def validate_github_repo(url: str) -> tuple[bool, str]:
    """
    验证是否为有效的 GitHub 仓库地址。
    自动清洗不可见字符、重复协议前缀等脏数据。
    """
    # ── 清洗：去掉所有不可见控制字符 ──
    import unicodedata
    cleaned = "".join(ch for ch in url if unicodedata.category(ch)[0] != "C" or ch in ("\r", "\n"))
    cleaned = cleaned.strip().strip("<>")

    # ── 清洗：去掉重复的 http(s):// 前缀 ──
    cleaned = re.sub(r"^(https?://)+", "https://", cleaned)

    # ── 如果没有协议，补上 ──
    if not cleaned.startswith("http"):
        cleaned = "https://" + cleaned

    parsed = urlparse(cleaned)

    if parsed.scheme != "https":
        return False, "请使用 HTTPS 协议的 GitHub 链接"

    if parsed.hostname != "github.com":
        return False, "仅支持 github.com 的仓库链接"

    path = parsed.path.strip("/")
    parts = path.split("/")
    if len(parts) < 2 or not parts[0] or not parts[1]:
        return False, "请提供完整的仓库地址（https://github.com/用户名/仓库名）"

    invalid_paths = {"settings", "notifications", "explore", "topics", "trending",
                     "marketplace", "pulls", "issues", "discussions", "orgs"}
    if parts[0] in invalid_paths:
        return False, "请提供有效的仓库地址"

    clean_url = f"https://github.com/{parts[0]}/{parts[1]}"
    return True, clean_url
