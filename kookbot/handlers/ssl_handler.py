#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/ssl 指令 - 查询域名 SSL 证书信息
"""

import requests

from config import API_SSL, API_REQUEST_TIMEOUT


async def handle_ssl(domain: str) -> str:
    if not domain or not domain.strip():
        return "请提供域名，例如: /ssl example.com"
    domain = domain.strip().split()[0]

    try:
        resp = requests.get(
            f"{API_SSL}?url={domain}",
            timeout=API_REQUEST_TIMEOUT,
            headers={"User-Agent": "Mozilla/5.0"},
        )
        resp.encoding = "utf-8"
        text = resp.text.strip()
        if not text:
            return f"SSL 查询返回为空: {domain}"
        return f"SSL 证书信息: {domain}\n{'-' * 30}\n{text}"
    except requests.RequestException as e:
        return f"[SSL 查询失败] {e}"