# App 包构建说明（v4.1 / 最终版：原生相机拍照 + 双引擎解码 + UA 环境标记）

本目录是 **Android App 原生壳源码**，用于上传到你的 GitHub 仓库，由 GitHub Actions 云端构建出 APK。
**无需在本机装 Android SDK / Node 全量环境**——全在云端完成。

## 目录内容
```
App/
├── package.json              # 已加 @capacitor/camera（原生相机）+ @zxing/library（本地解码）
├── capacitor.config.ts       # Camera 插件 + appendUserAgent（v34 环境标记）；已去掉 ML Kit，避免 gms 依赖
├── tsconfig.json
├── .github/workflows/build-android.yml   # 构建流程（拷贝 zxing UMD + 相机权限硬校验）
├── www/index.html            # 启动页（填 NAS 地址、系统相机拍照 → zxing 本地解码 → NAS 服务端解码兜底）
└── NAS_WEB_PATCH/            # NAS 端 app.js 等，备查/离线部署用（不影响 App 构建）
```

## 为什么从 ML Kit 换方案
原版扫码用 `@capacitor-mlkit/barcode-scanning`，其默认 flavor **依赖 Google Play 服务（gms）**。
在国产 ROM（无 GMS 的 OPPO/realme/vivo/小米/华为等）上表现就是**启动页黑屏**或 `scan failed`——
这是整类手机的通病，不是个别机型问题。

新方案：**`@capacitor/camera` 调系统相机拍照 + zxing 纯 JS 本地静态解码**。
- 系统相机是所有安卓机 100% 支持的原生能力，**完全绕开 gms 与 CameraX 预览兼容问题**；
- zxing 在 WebView 内运行，离线可用、零原生依赖，支持 QR 与常见一维码；
- 与上传页拍照键**同一条原生相机链路**，整机行为一致、最稳。

## 构建步骤
1. **新建/复用**一个 GitHub 仓库（公开私有均可）。
2. 把本目录里的文件**按原结构上传/覆盖**到仓库根目录：
   - 根目录：`package.json`、`capacitor.config.ts`、`tsconfig.json`
   - `.github/workflows/build-android.yml`
   - `www/index.html`
   - （`NAS_WEB_PATCH/` 可一并上传，便于归档，不影响构建）
   > 注意 `.github` 是隐藏目录：用 GitHub 网页「Add file → Create new file」手动建路径 `.github/workflows/build-android.yml`，或一次性拖整个仓库文件夹（别只拖部分文件）。
3. push 到 `main` / `master` 分支（或手动在 Actions 点 `Run workflow`）。
4. 等待 `Build Android APK` 跑完（约 3–6 分钟）。
   - **必须看到日志** `✅ 相机权限已包含在 APK 中`；否则构建会标红（缺相机权限=哑包）。
   - 另需看到 `www/zxing.min.js` 拷贝成功（Copy 步骤的输出）。
5. 在 Actions 右侧 **Artifacts** 下载 `photo-uploader-debug-apk` → 解压得 `app-debug.apk`。

## 安装与验证
1. **先卸载手机上的旧 App**（避免版本混淆）。
2. 装 `app-debug.apk` → 「设置-应用信息」确认：版本 **4.1**、权限列表出现 **相机**。
3. 打开 App → 填/探测 `http://<NAS_IP>:3080` → 测试连接 → **开始扫码** → 调起系统相机拍照 → 自动识别后跳上传页。
   - 识别不出时提示「没认出条码，请对准后重拍」，不会卡死；也可用「手动填写追溯码」兜底。
4. 上传页点**拍照键** → 调起原生相机 → 拍完自动落库上传。
5. 查询输**后 8 位**追溯码命中。

## 说明
- App 不写死 NAS 地址：启动页运行时填，换 IP/网段不需要重新打包。
- 扫码与拍照都走原生 `@capacitor/camera`，解码用 zxing（**无任何 Google 服务依赖**）——适配所有安卓机型。
- 版本号固定在 4.1（workflow 自动改 `build.gradle`），装好后看到 4.1 即确认装的是新包。
- **v34 的 UA 标记**：`capacitor.config.ts` 追加了 `appendUserAgent: ' PhotoUploaderShell/1.0'`，
  NAS 上传页靠它稳定识别「此刻在 App 里」，不会再把 App 误判成浏览器。这是**原生工程配置**，
  **改了必须重新构建 APK**才会写进 WebView 设置；NAS 那半边用 `deploy-nas.sh` 热注入即可，两边不必同批次。
