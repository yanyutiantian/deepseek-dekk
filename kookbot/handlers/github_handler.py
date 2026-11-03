#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/github github用户名/仓库 — GitHub 仓库预览截图
"""

from config import API_GITHUB_PREVIEW


async def handle_github(repo: str) -> dict:
    if not repo or not repo.strip():
        return {"type": "text", "content": "请按格式提供: /github 用户名/仓库\n例如: /github torvalds/linux"}
    repo = repo.strip().strip("<>").split()[0]

    # 统一为 https://github.com/用户名/仓库
    repo = repo.lstrip("/")
    if repo.startswith("github.com/"):
        repo = repo[len("github.com/"):]
    clean_url = f"https://github.com/{repo}"

    img_url = f"{API_GITHUB_PREVIEW}?url={clean_url}"
    return {
        "type": "image",
        "image_url": img_url,
        "caption": f"GitHub: {clean_url}",
    }