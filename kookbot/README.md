# KOOK 站长工具 Bot

一个能运行在 KOOK 频道/私聊的站长工具机器人，支持 16 种功能，完全自主部署可控。

## 功能列表

| 命令 | 功能 |
|------|------|
| `/ping 域名/IP` | Ping 测试 |
| `/ssl 域名` | SSL 证书查询 |
| `/whois 域名` | WHOIS 查询 |
| `/ip 域名/IP` | IP 归属地查询 |
| `/http 域名` | HTTP 测时 + 截图 |
| `/https 域名` | HTTPS 测时 + 截图 |
| `/github 用户名/仓库` | GitHub 仓库预览 |
| `/dns 域名` | DNS A 记录 + CNAME 查询 |
| `/xt` | 服务器状态监控 |
| `/tcp IP:端口` | TCP 端口连通测试 |
| `/port IP` | 常用端口扫描（35 个端口） |
| `/webping 域名` | HTTP/HTTPS 自动探测测时 |
| `/ssh 命令` | 服务器命令执行（www 权限） |
| `/tr IP/域名` | 路由追踪（traceroute） |
| `/bgp IP` | BGP/双线/单线检测 + 速度测试 |
| `/help` | 命令列表 |

## 部署

```bash
# 1. 克隆
git clone https://github.com/yanyutiantian/kookbot.git
cd kookbot

# 2. 安装依赖
pip install -r requirements.txt

# 3. 配置 Token（编辑 config.py，填入你的 KOOK Bot Token）
#    BOT_TOKEN = "你的Token"

# 4. 启动
python3 main.py
```

## 依赖

- Python 3.10+
- khl.py
- requests

## 系统要求

- `/tcp` `/port` `/tr` `/bgp` 需要 Linux 环境
- `/tr` 需要安装 `traceroute`
- `/ssh` 需要 `sudo` 和 `www` 用户
