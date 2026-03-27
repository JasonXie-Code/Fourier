# 预编译 APK · Prebuilt APK

## 与 GitHub「Releases」侧栏的区别 · GitHub “Releases” sidebar

- **仓库目录 `releases/`**：只是普通文件，**不会自动**出现在网页右侧的 **Releases** 列表里。  
- **侧栏 Releases**：需要在 GitHub 上**单独创建一次「发行版」**（绑定标签 + 可选上传附件），才会显示版本条目。

**仓库已推送标签 `v1.0.0`。** 请打开下方链接，在「Attach binaries」处**上传**本目录中的 APK（或拖入文件），再点击 **Publish release**，侧栏即可看到 **Fourier v1.0.0**：

**[→ 创建与标签 v1.0.0 对应的 Release（已预选 tag）](https://github.com/JasonXie-Code/Fourier/releases/new?tag=v1.0.0)**

建议 Release title：`Fourier v1.0.0`；说明可从本文复制 SHA-256 与安装提示。

---

*English:* Files under `releases/` in the repo are **not** the same as GitHub **Releases**. You must **publish a Release** (tag + optional assets) for it to appear in the right-hand sidebar. Tag **`v1.0.0`** is already pushed—use the link above, **attach** the APK, then **Publish release**.

---

## 当前版本 · Current build

| 项目 | 值 |
|------|-----|
| 文件名 | `Fourier-audio-analyzer-v1.0.0.apk` |
| 版本名 / `versionName` | 1.0（与 `android/app/build.gradle.kts` 一致） |
| 最低系统 | Android 7.0（API 24）及以上 |
| 包名 | `com.fourier.audioanalyzer` |

**完整性校验（SHA-256）**

```
6970F2638AAE56EE2B60A8277265407DD274F19CBA4FF5E51B3CAD70A9FED452
```

下载后可在本地核对：

```bash
# Windows PowerShell
Get-FileHash -Algorithm SHA256 .\Fourier-audio-analyzer-v1.0.0.apk
```

---

## 签名说明 · Signing

本 APK 由 **Release 构建产物**（`assembleRelease`）经 **Android 调试证书**（`~/.android/debug.keystore`）签名，便于侧载安装与体验；**非** Google Play 上架用的发布证书。若需公开分发或应用商店发布，请使用您自己的 keystore 重新签名。

---

## 安装提示 · Install

1. 在设备上允许「安装未知来源应用」或「通过 USB/ADB 安装」。  
2. 将 APK 传输至手机后点击安装，或使用：  
   `adb install -r Fourier-audio-analyzer-v1.0.0.apk`  
3. 若系统提示与已安装应用签名冲突，请先卸载旧版本再安装。

---

## 直链下载 · Direct link

仓库内文件（GitHub 界面亦可从本目录浏览）：

`releases/Fourier-audio-analyzer-v1.0.0.apk`

原始文件直链（便于脚本下载，以仓库默认分支为准）：

`https://github.com/JasonXie-Code/Fourier/raw/main/releases/Fourier-audio-analyzer-v1.0.0.apk`

---

## English

This folder ships a **release-built**, **debug-keystore-signed** APK for sideloading and evaluation. It is **not** intended as a Play Store production signing setup. Verify integrity using the SHA-256 above before installing.
