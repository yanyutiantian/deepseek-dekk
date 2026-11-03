#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/https - 同 /http，使用 TLS (443 端口)
"""

from handlers.http_handler import _handle_http_common


async def handle_https(target: str) -> dict:
    return await _handle_http_common(target, use_tls=True)