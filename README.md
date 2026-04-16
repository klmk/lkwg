# 洛克王国 Android 客户端

基于 [Ruffle](https://ruffle.rs/) 开源 Flash 引擎，在 Android 设备上运行洛克王国网页游戏。

## 技术路线

使用 Ruffle Android 原生版（Rust + wgpu），而非 WebView + WASM 方案：
- ✅ 原生 TCP Socket 直连（无需 WebSocket 代理）
- ✅ 直接加载远程 SWF
- ✅ 不受浏览器沙箱限制
- ✅ 更好的渲染性能

## 构建

### 自动构建（推荐）

推送代码到 `main` 分支后，GitHub Actions 会自动编译 arm64 debug APK。
编译产物在 Actions 页面的 Artifacts 中下载。

### 手动构建

```bash
git clone --recursive https://github.com/klmk/lkwg.git
cd lkwg/ruffle-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug -PABI_FILTERS=arm64-v8a
```

## 洛克王国 SWF 加载链

```
17roco.qq.com/default.html
  → ROCO-Z8.swf (主 Shell, 92KB)
    → preloader.swf (预加载器)
      → preloadlist.xml (47个文件)
        → Angel.config (加密配置, 1.4MB)
        → bb.swf + AngelClientLibs_s.swf + AngelProtocal.swf
        → 45+ 插件 SWF
        → scene.swf (场景)
```

## 项目结构

```
lkwg/
├── .github/workflows/build.yml  # CI 配置
├── ruffle-android/               # Ruffle Android 子模块
└── docs/                         # 项目文档
```
