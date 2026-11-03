# dekk 热更新壳

以后改功能不用再让用户重新下载模块：**功能代码（payload）放在服务器上的 `classes.dex` 里，壳 APK 每次冷启动把它拉下来运行。**

## 两个工程的分工

| 目录 | 里面有什么 | 什么时候要重新打包 |
|---|---|---|
| `../dekk`（主模块） | 全部功能代码 + **门面类** + `DekkEntry` 入口 | 改功能（发布 dex）；改门面（还要同步壳） |
| `dekk热更新`（本目录） | 门面实现 + 下载/加载 dex + 状态界面 | 改壳逻辑或门面时才要 |

## 关键：为什么要有一层「门面」

LSPosed 加载模块时**会重写模块 APK 的 dex**：把模块代码里写的 `de.robv.android.xposed.*`
换成它内部的混淆名（日志里能看到 `j.DGy.CncwKrJl.Vxj.yBk.IXposedHookLoadPackage$Wrapper`）。
因此：

* 壳自己的类被重写过 → 照常能用 Xposed API；
* 我们下载的 payload dex 是**原始 dex** → 直接引用那些公开类名会 `ClassNotFoundException` / `NoClassDefFoundError`。

真机踩出来的四条硬约束（`verify_hot.py` 全部有断言守着）：

| 约束 | 现象 |
|---|---|
| 只把父加载器设成壳的加载器没用 | 公开类名运行时根本不存在 |
| LSPosed **不重写 `const-class`** | 壳里写 `XposedBridge.class` 直接 `NoClassDefFoundError: de.robv…XposedBridge` |
| **ART 会校验 loadClass 返回的类名** | `Initiating class loader … returned class j.…XposedBridge instead of de.robv…` → 「名字映射」方案不可行 |
| `handleLoadPackage` 在宿主主线程 | 主线程联网 → `NetworkOnMainThreadException` |
| Android 14+ | 加载**可写** dex → `SecurityException: Writable dex file`，需 chmod 444 |

所以最终方案是**门面**：

```
payload（原始 dex，不引用真 API）
   └─ HotHook / HotParam / HotUnhook / HotMethodReplacement / HotBridge / HotHelpers / HotApp
        ↑ 继承/调用
壳（被 LSPosed 重写过，能调真 API）
   └─ 同一批门面类（HotHooks 把 HotHook 包成真 XC_MethodHook、把真 MethodHookParam 转成 HotParam）
```

* 门面类名和 API 的**方法名、字段名保持一致**（`beforeHookedMethod` / `afterHookedMethod` / `args` / `setResult` …），
  所以 payload 的 113 个文件只是把类名换了个前缀，回调体和逻辑一行没动；
* 门面在**两个工程里各放一份，内容必须完全相同**（`tools/sync_facade.py --check` 会校验）：
  payload 那份保证 payload 能独立编译；**热更新时真正跑的是壳里那份**（payload 加载器的父链优先命中壳的版本）。

## 运行流程（宿主进程里发生的事）

1. `HotEntry.handleLoadPackage` 命中 `com.deepseek.chat`；
2. `HotDex.start()` —— **主线程只做文件系统判断**：
   - 有缓存 → 当场 `DexClassLoader` 加载并回调，之后每 6 小时在后台线程查一次版本；
   - 没缓存 → 工作线程下载（按 `https://dekk.example.com/dex/version.txt` 里的 md5 校验）；
   - 缓存文件按 md5 命名（`classes-<md5>.dex`）并 chmod 444，避免旧 oat / Android 14 的只读要求；
3. `new DexClassLoader(dex, opt, null, 壳的加载器)` → payload 的 `com.dekk.yanyu.*` 从 dex 取，
   而 `Hot*` 门面优先命中**壳**里的那份；
4. 反射调用 dex 里的 `com.dekk.yanyu.DekkEntry.install(宿主的 ClassLoader)`，由它逐项装 hook
   （每一项单独 try/catch，谁失败单独报，其余照装）。

## 自动更新（用户侧无感）

每次 **DeepSeek 冷启动**都会：

1. 拉一次 `version.txt`，和本机生效的 dex md5 比对（检查间隔 `CHECK_INTERVAL_MS = 0`，即每次都查）；
2. md5 不同 → 后台线程下载（带 md5 校验、chmod 444）→ 写入版本文件；
3. 下载完成 → `HotUpdate` 在宿主界面弹出提示（「检测到新版本 xxx，已下载完成，正在重启」），
   4 秒后（或用户点「立即重启」）**自动重启 DeepSeek**：
   先给系统下 1.2 秒后的闹钟拉起宿主主界面，再 `killProcess` 结束宿主进程；
   闹钟在系统服务里，进程被杀也照样触发，所以不用 root；
4. 重启后加载的就是新 dex，版本已一致 → 不会再提示，不会循环重启。

壳的首页和 dekk 首页是同一个布局（`activity_main` + ActionBar「已激活/未激活」），
下面接一块热更新信息：说明、目标宿主、服务器当前版本、「立即检查服务器版本」按钮。

## 发布流程

1. **改功能**：AIDE 构建 `../dekk` → 发布 dex（壳不用动）：

   ```bash
   python3 tools/publish_dex.py                                   # 只抽 dex + 写 version.txt
   DEKK_SSH_PASS=*** python3 tools/publish_dex.py --upload root@<服务器>
   ```

   落地 `https://dekk.example.com/dex/classes.dex` + `version.txt`（内容是 dex 的 md5）。
   多 dex（`classes2.dex`）脚本会直接报错，提醒先拆分。

2. **改门面**（`Hot*.java`）：改主模块那份 → `python3 tools/sync_facade.py` →
   **重新构建壳 + 重新发布 dex**（两边都要更新）。

3. 用户侧：**强停 DeepSeek 再打开**（hook 是进程级安装的）；日志搜索 `[DEKK][热更新]`。

## 装壳（只需一次）

- AIDE 构建本目录（`applicationId` 故意保持 `com.dekk.yanyu`：装上去就是替换原模块，
  LSPosed 的勾选与作用域不用重配，`res/` 也保证 dex 用到的资源 id 一致）；
- LSPosed 里确认已勾选、作用域含 `com.deepseek.chat`；
- 壳界面可看：模块是否激活、dex 地址、服务器当前版本。

## 校验

```bash
python3 verify_hot.py                    # 结构/门面一致/禁 const-class/线程/只读等约束
python3 /sdcard/dekk_hot_typecheck.py    # 壳源码真类型检查
python3 tools/sync_facade.py --check     # 门面两份是否一致
python3 tools/check_dex_deps.py --apk ../dekk/app/build/bin/app.apk   # dex 引用体检
```

主模块侧：`python3 /sdcard/dekk_typecheck.py` + 14 个 `verify_*.py`。

## 已知限制

- 只支持单个 `classes.dex`；
- dex 换新后要宿主冷启动才生效（hook 是进程级装上去的；首轮下载完成后已装好，重启一次即可全量生效）；
- 壳的 `res/` 要与主模块保持一致（`verify_hot.py` 比对默认头像 md5 守着）；
- 老的「整包安装」入口 `HookInit` 仍保留（它用真 API，编译需要），但**热更新下不会被加载**；
  走热更新时请用壳安装，不要直接装主模块 APK。