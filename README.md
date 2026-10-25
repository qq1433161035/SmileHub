# SmileHub

[English](./README.en.md) | 中文

把 WorkBuddy 账号接入任意 OpenAI 兼容客户端。App 在手机本地启动一个转发端点，
编辑器、Agent 或脚本把 `baseUrl` 指过来即可使用，不需要官方开放 API，也不需要电脑。

```
手机
├─ SmileHub
│    ├─ OAuth 登录（国内版 / 国际版）
│    ├─ 本地转发端点  127.0.0.1:8765
│    └─ 悬浮窗状态面板（避免后台被系统冻结）
│
└─ 任意 OpenAI 兼容客户端
     └─ baseUrl = http://127.0.0.1:8765/v1
```

## 功能

- **账号接入**：走官方 CLI 的 OAuth 流程获取凭证，登录一次长期可用，Token 过期自动刷新。
- **双版本并存**：国内版与国际版是两套独立账号体系，可各登录一个并随时切换，切换即时生效。
- **本地转发端点**：兼容 OpenAI（`/v1/models`、`/v1/chat/completions`），同时支持
  Anthropic Messages 与 OpenAI Responses 格式，SSE 流式原样透传。
- **悬浮窗保活**：显示可拖动、可收起的轻量状态面板，让应用保持可见，避免服务在后台被冻结。
- **调用记录**：记录每次调用的模型、Tokens 与积分消耗，并按账号汇总。
- **签到与余额**：查看剩余积分、执行每日签到。
- **内置 Agent**：对话页可读写文件、执行 shell / Python、调用逆向与设备工具；
  支持多会话并发、上下文压缩、断流续写、跨会话长期记忆与子代理并行。
- **多模态**：相册选图或直接提供图片路径，模型可以真正读到图片内容。
- **自扩展工具箱**：内置工具不足时，Agent 可自行下载可执行文件、Python 包或脚本，
  放入指定目录后直接调用，无需等待版本更新。
- **逆向工具链**：APK 解码 / 回打包、DEX 反编译、SO 解析与反汇编、签名、对齐。
- **设备能力**：无线 ADB 配对与 shell、无障碍界面调试、截图与界面操作。

## 环境要求

| 项 | 要求 |
| --- | --- |
| 运行 | Android 8.0（API 26）及以上，arm64-v8a |
| 构建 | JDK 21、Android SDK platform 35 与 build-tools |
| Gradle | 使用仓库自带 wrapper，无需单独安装 |

## 构建

### Linux / macOS

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
```

### Windows

在 PowerShell 中：

```powershell
"sdk.dir=$env:LOCALAPPDATA\Android\Sdk" | Out-File -Encoding ascii local.properties
.\gradlew.bat assembleDebug
```

`local.properties` 里的 SDK 路径按实际安装位置调整。

- 调试包：`app/build/outputs/apk/debug/app-debug.apk`
- 单元测试：`./gradlew test`
- 正式包：`./gradlew assembleRelease`

正式包默认开启 R8 混淆与资源压缩。签名信息从 `keystore.properties` 读取
（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）；该文件已被 git 忽略。
没有签名材料时产物为 `app-release-unsigned.apk`，可用 debug keystore 临时签名做真机
冒烟，但不要作为正式分发包。

## 安装

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. **登录**：打开 App →「个人信息」→ 选择版本 → 点登录，在浏览器完成授权。
2. **启动服务**：「API」→ 打开开关。
3. **开启悬浮窗**：点「开启悬浮窗」并授权，让服务在后台保持运行。
4. **接入客户端**：「API」页会显示 `baseUrl` 与 `apiKey`，填入你的客户端。

### 客户端配置

以 `models.json` 风格的配置为例：

```json
{
  "providers": {
    "workbuddy": {
      "baseUrl": "http://127.0.0.1:8765/v1",
      "api": "openai-completions",
      "apiKey": "wb-local",
      "models": [
        { "id": "hy3" }
      ]
    }
  }
}
```

模型名以 App「API」页的列表为准，点模型名可复制。首次运行会随机生成本机密钥，
设置页可查看、复制或修改。

## 为什么需要悬浮窗

Android 会在应用进入后台后冻结进程，被冻结的进程不再处理网络请求，表现就是客户端
调用超时。前台服务并不足以避免这一点；悬浮窗让应用保持“可见”状态，进程重要性因此
提升，不再被冻结。该方案不需要 root，在系统设置里授权一次即可。

## 工作原理

```
客户端请求
  │  OpenAI / Anthropic / Responses 协议
  ▼
本地转发端点（127.0.0.1:8765）
  │  ① 改写请求体（上游要求 stream、拒绝 developer 角色）
  │  ② 补上 WorkBuddy 专有的身份头
  │  ③ 带上你的 access token
  ▼
WorkBuddy 上游
  │  返回 SSE 流
  ▼
原样透传给客户端
```

端点默认只监听 `127.0.0.1`，每个请求都要带共享密钥，同设备的其它应用无法直接调用。
可在设置中开启局域网访问，并使用独立的局域网密钥鉴权；也可在 `端口 + 1` 提供 HTTPS。

上游接口是 WorkBuddy 客户端使用的私有接口，不是官方开放 API。上游改动可能导致功能
失效，届时需要跟随调整。

## 技术栈与依赖

构建环境：AGP 8.9.1 · Gradle 8.11.1 · Kotlin 2.0.21 · JDK 21 · compileSdk 35 ·
minSdk 26 · targetSdk 28 · 仅打包 arm64-v8a。

### 界面与基础

| 库 | 版本 | 用途 |
| --- | --- | --- |
| Kotlin | 2.0.21 | 语言与协程 |
| Jetpack Compose BOM | 2025.05.00 | ui / foundation / animation / ui-tooling-preview |
| Material3 | 1.4.0-alpha18 | Material You 组件 |
| material-icons-extended | 随 Compose BOM | 图标 |
| activity-compose | 1.10.1 | Activity 与 Compose 集成 |
| AndroidX Core / Lifecycle | 随 Compose 传递 | 通知、FileProvider、生命周期 |

### 网络、协议与安全

| 库 | 版本 | 用途 |
| --- | --- | --- |
| Conscrypt Android | 2.5.3 | ADB 配对所需 TLS 1.3 `exportKeyingMaterial` |
| BouncyCastle bcprov / bcpkix | 1.78.1 | 自签证书、ADB 密钥、签名密钥 |
| libadb-android | 3.1.1（JitPack） | 设备内 ADB 客户端、无线调试配对 |
| HttpURLConnection（系统） | — | 上游请求与本地转发 |

### 逆向引擎（`:engine`）

| 库 | 版本 | 用途 |
| --- | --- | --- |
| apktool-lib | 2.10.0-wbhub.jar | APK 解码 / 回打包 |
| brut.j.common / brut.j.util / brut.j.dir | 2.10.0 | apktool 运行时组件 |
| xpp3 | 1.1.6 | XML 解析 |
| Guava | 33.3.0-android | apktool 依赖 |
| commons-lang3 / commons-io / commons-text | 3.17.0 / 2.16.1 / 1.12.0 | 通用工具 |
| apksig | 8.9.1 | APK 签名与校验 |
| smali / smali-baksmali | 3.0.8 | DEX 反汇编 / 汇编 |
| jadx-core / jadx-dex-input / jadx-input-api / jadx-zip | 1.5.6 | DEX 反编译为 Java |
| slf4j-nop | 2.0.18 | jadx 日志空实现 |
| gson | 2.14.0 | SO / ELF 结构序列化 |
| Capstone | 预编译 `libwbcapstone.so` | SO 反汇编 |
| aapt2 / zipalign | Android build-tools | 资源编译、APK 对齐（打包为 `lib*_exec.so`） |

### Python 运行时

| 库 | 版本 | 用途 |
| --- | --- | --- |
| Chaquopy | 17.1.0 | 在 APK 内嵌 CPython |
| CPython | 3.12 | Agent 的 `python` 工具，仅标准库 |

以上库的版权与许可证归各自作者所有，使用与分发时请遵守对应项目的许可条款。

## 隐私

- 账号凭据、会话、记忆与设置只存在本机，不上传、不参与云端同步。
- 凭据、ADB 私钥、TLS CA 私钥与签名密钥存放在应用私有目录，不使用共享存储。
- 会话与调用记录默认保存在本机，用户可自行查看、备份或删除。

## 已知限制

- 依赖 WorkBuddy 私有接口，上游更新后可能需要适配。
- 悬浮窗保活取决于系统重要性判定，激进的后台管理策略下仍可能被限制。
- 内置工具链与 Python 运行时只打包 arm64-v8a。
- `targetSdk 28` 用于允许执行下载到私有目录的 ELF；代价是新系统的部分存储、
  通知与前台服务限制不生效。
- 仅在 Android 上验证。

## 免责声明

- 本项目仅供个人学习与研究使用，仅驱动使用者自己的账号在本机调用。
- 使用者需遵守 WorkBuddy 的服务条款；因使用本项目产生的任何后果由使用者自行承担。
- 本项目与腾讯、WorkBuddy 无关联，未获其授权或认可；相关名称仅用于描述兼容关系。

## 贡献与反馈

- 本项目开源后不再修复任何 Bug，也不接受功能请求。
- Issue 与 PR 仅作留档，不保证回复或合并。
- 作者：微笑（weixiao）· QQ：791940981

## 参考项目

- [ardeyouxipianyi/workbuddy2api-hub](https://github.com/ardeyouxipianyi/workbuddy2api-hub)
- [xc3qaq/WorkBuddy-Hub-Android](https://github.com/xc3qaq/WorkBuddy-Hub-Android)

感谢以上项目在协议适配与实现思路上的参考。

## 许可

[MIT](./LICENSE)
