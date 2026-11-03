#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KOOK 卡片构建工具
— 生成「@提问者 + AI 回答」的卡片
— KMD 特殊字符转义
"""

from khl.card import Card, Module, Element, Types as CardTypes

from config import CARD_MAX_LEN, ESCAPE_KMD


def escape_kmd(text: str) -> str:
    """转义 KMD 特殊字符（\\ ( ) [ ]），避免正文被当成卡片语法。"""
    if not text:
        return ""
    for ch in ("\\", "(", ")", "[", "]"):
        text = text.replace(ch, "\\" + ch)
    return text


def _render(text: str) -> str:
    """按需转义正文。"""
    return escape_kmd(text) if ESCAPE_KMD else (text or "")


def build_answer_card(uid, content: str, title: str = "AI 回答") -> Card:
    """
    构建回复卡片：标题 + @提问者 + 正文。

    :param uid:     提问者用户 id（用于 @）
    :param content: 正文（回答或错误说明）
    :param title:   卡片标题
    """
    body = content or ""
    if len(body) > CARD_MAX_LEN:
        body = body[: CARD_MAX_LEN - 20] + "\n\n[内容过长，已截断]"

    modules = [
        Module.Header(title),
        Module.Divider(),
        Module.Section(Element.Text(f"(met){uid}(met)", type=CardTypes.Text.KMD)),
        Module.Section(Element.Text(_render(body), type=CardTypes.Text.KMD)),
    ]
    return Card(*modules)


def build_pay_card(uid, content: str, link: str | None = None, title: str = "自助赞助",
                   button_text: str = "点此打开支付宝支付",
                   hint_text: str = "如果点不开，请长按复制下面这条链接，到支付宝或浏览器打开：") -> Card:
    """
    构建「带链接」的卡片：标题 + @用户 + 说明文字；有链接时额外给「可点击」和「可复制」两种形式。

    按钮/提示文案可自定义（默认是赞助场景的支付宝文案，其它场景请显式传入）。

    注意：链接行**不做** KMD 转义 —— 转义会把 markdown 链接语法 `[文字](链接)` 破坏掉。
    """
    body = content or ""
    modules = [
        Module.Header(title),
        Module.Divider(),
        Module.Section(Element.Text(f"(met){uid}(met)", type=CardTypes.Text.KMD)),
        Module.Section(Element.Text(_render(body), type=CardTypes.Text.KMD)),
    ]
    if link:
        modules.append(Module.Section(Element.Text(f"[{button_text}]({link})", type=CardTypes.Text.KMD)))
        modules.append(Module.Section(Element.Text(hint_text, type=CardTypes.Text.KMD)))
        modules.append(Module.Section(Element.Text(link, type=CardTypes.Text.KMD)))
    return Card(*modules)