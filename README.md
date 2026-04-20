# 洛克王国 Android 客户端（Shadow 副本）

> 这是用于并行开发的隔离副本版本，应用标识与展示名称已和主项目区分。

基于 [Ruffle](https://ruffle.rs/) 开源 Flash 引擎，在 Android WebView 中运行洛克王国网页游戏。

## 技术架构

```
┌─────────────────────────────────────────────┐
│                 Android App                   │
│  ┌───────────────────────────────────────┐  │
│  │           WebView (Chrome 120)        │  │
│  │  ┌─────────┐  ┌──────────────────┐   │  │
│  │  │ Ruffle  │  │  洛克王国 SWF     │   │  │
│  │  │ (WASM)  │←→│  (Flash 游戏)    │   │  │
│  │  └────┬────┘  └──────────────────┘   │  │
│  │       │                               │  │
│  │  shouldInterceptRequest               │  │
│  │  (注入 Ruffle、代理资源、拦截 login3)  │  │
│  └───────┼───────────────────────────────┘  │
│          │                                   │
│  ┌───────┴───────────────────────────────┐  │
│  │         SocketProxyServer             │  │
│  │  WebSocket ←→ TGW ←→ 游戏服务器       │  │
│  │  (端口 9000/9100/9101/19000/19001)    │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

### 核心组件

| 组件 | 文件 | 功能 |
|------|------|------|
| `MainActivity` | `MainActivity.kt` | WebView 初始化、SocketProxy 启动、调试面板 |
| `RuffleWebViewClient` | `RuffleWebViewClient.kt` | 请求拦截、Ruffle 注入、资源代理、login3 处理 |
| `SocketProxyServer` | `SocketProxyServer.kt` | WebSocket→TGW→游戏服务器的 TCP 桥接 |
| `Constants` | `Constants.kt` | User-Agent 等常量 |

### QQ 登录流程

```
login.html (QQ OAuth 二维码)
  → 用户扫码
  → logintarget.html?code=xxx (OAuth 回调)
  → login3?code=xxx&platfrom_src=2 (获取登录态)
  → login3 返回游戏页面 HTML + Set-Cookie
  → main005.js 加载 SWF → 游戏运行
```

**login3 处理：** `login3` 在 OAuth iframe 中执行，返回游戏页面 HTML。
`RuffleWebViewClient` 拦截 `login3` 响应，注入 Ruffle 并修补跨域引用，
使游戏 SWF 能在 iframe 中正常加载。

### Socket 代理（TGW）

游戏通过 Flash Socket 连接服务器，使用腾讯 TGW（Tencent Gateway）协议：

```
Ruffle WASM → ws://127.0.0.1:9000 → SocketProxy
  → stat.17roco.qq.com:9000 (TGW 网关)
  → tgw_l7_forward → zone5/zone6.17roco.qq.com:443 (游戏服务器)
```

代理路由配置：

| 监听端口 | TGW Zone | 用途 |
|---------|----------|------|
| 9000 | zone5 | 频道 1-50 |
| 9100 | zone5 | 频道 1-50（备用） |
| 9101 | zone5 | 频道 1-50（备用） |
| 19000 | zone6 | 频道 51-100 |
| 19001 | zone6 | 频道 51-100（备用） |

## SWF 加载链

```
17roco.qq.com/default.html
  → ROCO-Z8.swf (主 Shell, 92KB)
    → LayerLogin.js (登录逻辑)
    → login.html → QQ OAuth → login3
    → main.swf (游戏主程序, 54KB)
      → ver.config (版本配置)
      → Global.xml (全局配置)
      → Angel.config (加密配置, 1.4MB)
      → bb.swf + AngelClientLibs_s.swf + AngelProtocal.swf
      → 45+ 插件 SWF
```

## 构建

### 自动构建（推荐）

推送代码到 `main` 分支后，GitHub Actions 会自动编译 arm64 debug APK。
编译产物在 Actions 页面的 Artifacts 中下载。

### 手动构建

```bash
git clone https://github.com/klmk/lkwg.git
cd lkwg
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

## 调试工具

项目包含 `roco_debug.py` 调试脚本，通过 ADB + Chrome DevTools Protocol (CDP)
远程调试 WebView，无需每次编译 APK。

### 使用方式

```bash
# 连接 WebView
python3 roco_debug.py connect

# 获取当前 URL
python3 roco_debug.py url

# 获取所有 frame
python3 roco_debug.py frames

# 执行 JavaScript
python3 roco_debug.py eval "document.title"

# Cookie 管理
python3 roco_debug.py cookies           # 查看所有 Cookie
python3 roco_debug.py cookies save       # 保存到文件
python3 roco_debug.py cookies load       # 从文件加载
python3 roco_debug.py cookies set angel_key=xxx 17roco.qq.com

# 截图
python3 roco_debug.py screenshot /path/to/file.png

# 查看 logcat 日志
python3 roco_debug.py logs RuffleWebViewClient 50

# 导航到指定 URL
python3 roco_debug.py navigate https://17roco.qq.com/default.html
```

### 输出格式

所有命令输出 JSON，方便脚本解析。

## 项目结构

```
lkwg/
├── .github/workflows/build.yml           # CI 自动构建
├── app/src/main/
│   ├── java/com/roco/app/
│   │   ├── MainActivity.kt               # 主 Activity
│   │   ├── RuffleWebViewClient.kt        # WebView 请求拦截核心
│   │   ├── SocketProxyServer.kt          # Socket 代理
│   │   └── Constants.kt                  # 常量定义
│   ├── assets/ruffle/                    # Ruffle 引擎文件
│   │   ├── ruffle.js                     # Ruffle 加载器
│   │   ├── core.ruffle.*.js              # Ruffle 核心
│   │   └── *.wasm                        # WASM 模块
│   └── res/                              # Android 资源
└── README.md
```

## 已知问题

- **Ruffle `unreachable` 错误**：Ruffle 遇到未实现的 AVM2 指令时会抛出 `RuntimeError: unreachable`。
  这是 Ruffle 的兼容性问题，不影响基本游戏加载。
- **字体缺失**：SimHei、SimSun 等中文字体在 Android 上不可用，Ruffle 会回退到默认字体。
- **`URLLoader.close()` 未实现**：Ruffle 对此 API 是 stub，可能影响部分加载逻辑。
