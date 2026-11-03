#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/ai 指令处理 — 调用 AI 接口并返回回答文本
"""

import asyncio

from utils.ai_client import ask_ai


async def handle_ai(question: str, memory_id: str | None = None) -> str:
    """校验问题并调用 AI；阻塞的网络请求放到线程池执行。

    :param memory_id: 记忆标识符（每个用户独立），透传给接口的 qq 参数。
    """
    question = (question or "").strip()
    if not question:
        raise ValueError("请在 /ai 后面写上你的问题，例如：/ai 你好")

    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, ask_ai, question, memory_id)