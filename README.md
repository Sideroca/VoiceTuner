# 语音调控台 · VoiceTuner

安卓端语音合成调参台（自用）：把「调参 → 请求 → 试听」收进一个 App。
直连阿里云百炼 CosyVoice v3.5-plus 复刻音色，无任何中转服务器。

## 当前版本
**v0.1**：调参面板（音色 / 文本 / 情绪指令 / 语速 / 音调 / 音量 / 种子 / 格式 / 高级参数）→ 生成 → 试听 / 分享 / 导出到「下载」 → 本地记录（回填参数、换种子重抽）。

## 安装（latest 直链，永远最新）
手机浏览器打开或书签收藏：

https://github.com/Sideroca/VoiceTuner/releases/latest/download/VoiceTuner.apk

点开即下最新版，覆盖安装即可（签名固定）。

## 使用
1. 打开 App → 右上角「设置」→ 粘贴 API Key（`sk-ws-…`，仅保存在本机 App 私有空间）
2. 选音色 → 输入文本与情绪指令 → ⚡ 生成

## 构建
- 工具链：AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.9 / JDK 17 / compileSdk 35
- 签名：固定 `debug.keystore` 随仓库保存（任何环境构建的 APK 均可互相覆盖安装）
- CI：push 后 GitHub Actions 自动编译 → Artifacts 下载 / latest 直链

## 红线
- 代码零预置密钥；API Key 由用户自行填写、仅存本机
