#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI 问答客户端
— 拼接「人设卡提示词 + 用户问题」为最终 prompt
— 调用 Qwenjh 接口（OpenAI 风格，正文在 choices[0].message.content）
— 解析多格式兼容 + 失败自动重试，接口偶发抽风不会直接报「无法解析正文」
"""

import json
import sys
import time

import requests

from config import (
    AI_ENDPOINT,
    AI_APIKEY,
    AI_MODEL,
    AI_QQ,
    AI_TIMEOUT,
    PERSONA_PROMPT,
)

# 失败重试次数与退避基数（秒）
AI_MAX_RETRY = 3
AI_RETRY_BACKOFF = 2


def build_prompt(question: str) -> str:
    """把人设卡提示词与用户问题拼成最终 prompt。提示词为空时只用问题。"""
    question = (question or "").strip()
    persona = (PERSONA_PROMPT or "").strip()
    if persona:
        return f"{persona}\n\n用户问题：{question}"
    return question


def _content_to_text(c) -> str | None:
    """把 content 字段转成文本，兼容字符串 / 数组（OpenAI 多模态格式）。"""
    if isinstance(c, str):
        return c.strip() or None
    if isinstance(c, list):
        parts = []
        for item in c:
            if isinstance(item, dict):
                t = item.get("text") or item.get("content")
                if isinstance(t, str):
                    parts.append(t)
            elif isinstance(item, str):
                parts.append(item)
        joined = "".join(parts).strip()
        return joined or None
    return None


def _extract_content(data) -> str | None:
    """
    从各种可能的返回结构里取正文，尽量不因接口格式变化而失败。
    支持：
      - choices[0].message.content（OpenAI 风格）
      - choices[0].message.content 为数组（多模态）
      - choices[0].text / content
      - 顶层 content / answer / reply / result / msg / message / text
      - 再套一层 data / result / response 包装
      - 直接返回纯文本
    """
    if isinstance(data, str):
        return data.strip() or None
    if not isinstance(data, dict):
        return None

    # 1) OpenAI 风格：choices[0].message.content
    choices = data.get("choices")
    if isinstance(choices, list) and choices:
        first = choices[0]
        if isinstance(first, dict):
            message = first.get("message")
            if isinstance(message, dict):
                text = _content_to_text(message.get("content"))
                if text:
                    return text
            elif isinstance(message, str) and message.strip():
                return message.strip()
            for k in ("text", "content"):
                text = _content_to_text(first.get(k))
                if text:
                    return text

    # 2) 顶层常见字段
    for k in ("content", "answer", "reply", "result", "msg", "message", "text"):
        text = _content_to_text(data.get(k))
        if text:
            return text

    # 3) 再套一层包装：data / result / response
    for wrapper in ("data", "result", "response"):
        v = data.get(wrapper)
        if isinstance(v, dict):
            for k in ("content", "answer", "reply", "result", "msg", "message", "text"):
                text = _content_to_text(v.get(k))
                if text:
                    return text

    return None


def _once(payload: dict) -> tuple[str | None, str | None]:
    """
    发一次请求。返回 (正文, 错误描述)；正文非空即成功。
    """
    try:
        resp = requests.post(
            AI_ENDPOINT,
            data=payload,
            timeout=AI_TIMEOUT,
            headers={"Accept": "application/json"},
        )
    except requests.RequestException as e:
        return None, f"请求 AI 接口失败：{e}"

    if resp.status_code != 200:
        return None, f"AI 接口返回 HTTP {resp.status_code}"

    raw_text = (resp.text or "").strip()
    if not raw_text:
        return None, "AI 接口返回了空响应"

    try:
        data = resp.json()
    except ValueError:
        # 不是 JSON：如果像正文就直接用
        if not raw_text.startswith("{") and not raw_text.startswith("["):
            return raw_text, None
        return None, "AI 接口返回的不是合法 JSON"

    content = _extract_content(data)
    if content is None:
        err = data.get("error") if isinstance(data, dict) else None
        if isinstance(err, dict):
            err = err.get("message") or str(err)
        try:
            preview = json.dumps(data, ensure_ascii=False)[:500]
        except Exception:
            preview = str(data)[:500]
        print(f"[AI] 解析失败，原始返回: {preview}", file=sys.stderr, flush=True)
        return None, f"AI 接口返回异常：{err or '无法解析正文'}"

    text = content.strip()
    if not text:
        return None, "AI 返回了空内容"
    return text, None


def ask_ai(question: str, memory_id: str | None = None) -> str:
    """
    向 AI 接口提问并返回回答正文（阻塞调用）。

    :param memory_id: 记忆标识符，用于区分每个用户的独立记忆；
                      为空时回退到默认 AI_QQ。
    失败（含重试后仍失败）时抛出 RuntimeError，由上层转成错误卡片。
    """
    prompt = build_prompt(question)
    if not prompt:
        raise ValueError("问题为空")

    payload = {
        "apikey": AI_APIKEY,
        "model": AI_MODEL,
        "qq": str(memory_id) if memory_id else AI_QQ,
        "msg": prompt,
        "type": "json",
        "lw": "no",
        "sk": "no",
        "re": "no",
    }

    last_err = ""
    for attempt in range(1, AI_MAX_RETRY + 1):
        text, err = _once(payload)
        if text:
            return text
        last_err = err or "AI 接口调用失败"
        print(f"[AI] 第 {attempt}/{AI_MAX_RETRY} 次失败：{last_err}", file=sys.stderr, flush=True)
        if attempt < AI_MAX_RETRY:
            time.sleep(AI_RETRY_BACKOFF * attempt)

    raise RuntimeError(last_err or "AI 接口调用失败")
