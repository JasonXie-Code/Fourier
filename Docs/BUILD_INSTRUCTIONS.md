# 构建说明

## 快速开始

### 使用Android Studio（推荐）

1. 打开Android Studio
2. 选择 "Open an Existing Project"
3. 选择项目根目录
4. 等待Gradle同步完成
5. 连接Android设备或启动模拟器
6. 点击运行按钮

### 使用命令行

#### Windows
```bash
gradlew.bat assembleDebug
```

#### Linux/Mac
```bash
chmod +x gradlew
./gradlew assembleDebug
```

## 依赖管理

本项目使用Gradle进行依赖管理。所有依赖定义在 `app/build.gradle.kts` 中。

首次构建时，Gradle会自动下载所需依赖到本地缓存（通常在 `~/.gradle/caches/`）。

### 离线构建

如果需要完全离线构建：

1. 先在有网络的环境下执行一次完整构建
2. 将 `~/.gradle/caches/` 目录复制到离线环境
3. 在离线环境下使用 `--offline` 参数：
   ```bash
   ./gradlew assembleDebug --offline
   ```

## 生成APK

### Debug版本
```bash
./gradlew assembleDebug
```
输出位置: `app/build/outputs/apk/debug/app-debug.apk`

### Release版本
```bash
./gradlew assembleRelease
```
输出位置: `app/build/outputs/apk/release/app-release.apk`

注意：Release版本需要配置签名，编辑 `app/build.gradle.kts` 中的 `signingConfigs`。

## 安装到设备

### 使用ADB
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 使用Android Studio
直接点击运行按钮，Android Studio会自动安装并启动应用。

## 常见问题

### Gradle同步失败
- 检查网络连接
- 确认Android SDK已正确安装
- 尝试清理并重新同步：`File > Invalidate Caches / Restart`

### 构建错误：找不到SDK
- 设置 `local.properties` 文件，添加：
  ```
  sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
  ```
  或使用Android Studio自动生成

### 权限问题（Windows）
- 确保 `gradlew.bat` 有执行权限
- 以管理员身份运行命令提示符

## 开发环境要求

- **JDK**: 17 或更高版本
- **Android SDK**: API 35 (Android 15)
- **Gradle**: 8.2（通过wrapper自动管理）
- **Android Studio**: Hedgehog | 2023.1.1 或更高版本（推荐）

## 项目配置

### 修改应用ID
编辑 `app/build.gradle.kts`:
```kotlin
defaultConfig {
    applicationId = "com.your.package.name"
    // ...
}
```

### 修改最低SDK版本
编辑 `app/build.gradle.kts`:
```kotlin
defaultConfig {
    minSdk = 24  // 修改为你需要的版本
    // ...
}
```

### 修改目标SDK版本
编辑 `app/build.gradle.kts`:
```kotlin
defaultConfig {
    targetSdk = 35  // 修改为你需要的版本
    // ...
}
```
