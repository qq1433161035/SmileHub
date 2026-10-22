# SmileHub 维护文档

面向接手开发、发版和排障的人。只写“怎么改、去哪看、为什么这样”，不重复 README
里的功能介绍。

## 1. 模块与目录

```
smilehub/
├─ app/                 应用层：UI、Agent、转发服务、工具注册
│  ├─ src/main/java/com/wbhub/app/
│  │  ├─ MainActivity.kt       状态机 + 多会话 AgentRun 调度（最大的文件）
│  │  ├─ ui/                   Compose 界面（HubApp / Screens / AgentScreen / SettingsScreen）
│  │  ├─ agent/                AgentClient（流式对话）、AgentTools（工具实现）、上下文压缩
│  │  ├─ bridge/               本地 OpenAI 兼容网关、悬浮窗、通知、调用记录
│  │  ├─ proto/                上游协议、凭据、OAuth、gzip
│  │  ├─ tools/                工具运行时、引擎封装、ADB、外部工具
│  │  └─ storage/              文件存储（AppStorage / FilePrefs）
│  ├─ src/main/python/         Chaquopy 内置脚本（wbhub_coderunner.py）
│  ├─ src/main/jniLibs/        aapt2 / zipalign 可执行文件（lib*_exec.so）
│  └─ libs/                    apktool 定制 jar（构建必需，勿删）
├─ engine/               逆向引擎实现：apktool / apksig / smali / jadx / Capstone
├─ engine-api/           引擎纯接口，供 app 编译期依赖
├─ testapp/              逆向工具测试样本（独立 APK，不随主应用发布）
└─ docs/                 设计文档与本文档
```

## 2. 构建环境

| 项 | 要求 |
| --- | --- |
| JDK | 21（`jvmToolchain(21)`；CI 由 setup-java 提供） |
| Python | 3.12（仅构建期，Chaquopy 需要；可用 `WBHUB_PYTHON` 覆盖启动命令） |
| Android SDK | compileSdk 35，需安装 build-tools |
| Gradle | 8.11.1（wrapper 已固定） |

```bash
./gradlew :app:assembleDebug          # Windows: .\gradlew.bat
./gradlew :app:assembleRelease
./gradlew test                        # JVM 单测
./gradlew :app:connectedDebugAndroidTest   # 需连接设备
```

JDK 路径不要写回仓库的 `gradle.properties`。本机 JAVA_HOME 不是 21 时，把
`org.gradle.java.home` 和 `org.gradle.java.installations.paths` 写到 Gradle 用户
目录的 `gradle.properties`（默认 `~/.gradle/`，以 `GRADLE_USER_HOME` 为准），
该文件不随仓库分发。

## 3. 架构速览

**UI 与状态**：`HubState` 是所有界面的单一状态；`MainActivity` 持有
`mutableStateOf(HubState())`，并把回调传给 `HubApp`。新增界面 = 加字段 + 加回调 +
在 `HubApp` 里渲染。

**Agent**：每个会话一个 `AgentRun`（独立 `AgentClient`、transcript、审批队列），
按 sessionId 隔离，跨会话并行、同会话串行。事件从工作线程 `post` 到主线程，
`applyAgentEvent` 只写自己 run 的 entries。

**工具层**：`AgentTools.definitions` 声明 schema，`run()` 分发实现；写操作与命令
走 `ToolPolicy.needsApproval`。外部工具由 `ToolRegistry` 合并，同名时优先外部实现
（`RESERVED` 用来挡住内置名）。

**转发**：`BridgeService`（前台服务）持有 `BridgeServer`（ServerSocket + TLS），
请求经 `UpstreamClient` 转发到上游，`ApiAdapters` 负责 OpenAI / Anthropic /
Responses 三种格式的转换。

**存储**：`AppStorage` 统一给出目录，`FilePrefs` 是 JSON 化的 SharedPreferences 替
代，`AgentStore` 落会话，`ArtifactStore` 落大工具输出。

## 4. 数据与目录

| 路径 | 内容 | 说明 |
| --- | --- | --- |
| `/sdcard/WBHub/` | 工作区、会话、凭据、adb 密钥、证书、设置 | 用户可见 |
| `/sdcard/WBHub/agent/sessions/<id>.json` | 会话记录 | 排障时直接看这里 |
| `/sdcard/WBHub/agent/sessions/<id>/artifacts/` | 被压缩掉的工具原文 | |
| `/sdcard/WBHub/credentials/` | OAuth token | 明文，**待迁移到私有目录** |
| `/sdcard/WBHub/adb/client.key`、`client.crt` | ADB 客户端密钥 | PEM，勿提交 |
| `/data/data/com.wbhub.app/files/WBHub/` | `bin/ lib/ py_modules/ site-packages/` | 可执行目录，targetSdk 28 的原因 |
| `/data/local/tmp/wb-hub/wb-auth.json` | 凭据镜像 | 供本机其它进程读取，**待移除** |

## 5. 关键设计决策

**targetSdk = 28**：Android 10+ 的 W^X 只限制 targetSdk ≥ 29 的应用从可写目录
执行 ELF。降到 28 后，`/data/data/.../files/WBHub/bin` 里的二进制可以直接
`ProcessBuilder` 执行（Termux 同款）。代价是新平台的存储、通知、组件导出等默认
安全策略不生效；升级 targetSdk 前必须先重新设计“下载即执行”这条链路。

**内置 Conscrypt 2.5.3**：系统 Conscrypt 在新 Android 上删掉了
`exportKeyingMaterial(SSLSocket, String, byte[], int)`，ADB 配对会抛
`NoSuchMethodException`。不要移除这个依赖。

**Chaquopy + CPython 3.12**：`python` 工具在进程内执行，构建期需要本机 Python。
`pyc.src/stdlib = false` 是为了缩短构建时间，代价是首次导入在设备上编译。

**AarMetadata 校验被关闭**：`app/build.gradle.kts` 里 `tasks.configureEach` 关掉了
`AarMetadata`，用于绕过 alpha 版 Material3 的元数据检查。升级 Compose 时优先去掉
这个 hack。

**`Connection: close`**：网关会单方面关闭空闲 keep-alive 连接，复用旧连接会报
`Software caused connection abort`，因此 Agent 与 Bridge 的请求都不复用连接。

## 6. 常见维护任务

### 新增一个 Agent 工具

1. `AgentTools.definitions` 加 schema（名称、描述、参数）。
2. `AgentTools.run()` 加分发分支，实现返回 `Outcome`。
3. 写操作/命令要在 `ToolPolicy.summarize` 补审批摘要，并确认
   `needsApproval` 的归类正确。
4. 如果是高频能力，加进 `ExternalToolSource.RESERVED`，防止被工作区脚本覆盖。
5. 同步更新 README 的工具说明与 `docs/tooling-expansion-design.md`。

### 上游协议变了

先看 `proto/Wire.kt`（URL、请求头、角色改写）与 `proto/UpstreamClient.kt`（流式解
析、models、额度）。用 `adb logcat -s WBHub:*` 抓状态码与错误体，再改
`Wire.chatHeaders` / `ApiAdapters`。不要改 `AgentClient` 的传输层，它只负责 SSE。

### 升级依赖

- Compose / Material3：先去掉 `AarMetadata` 的关闭逻辑再升级，确认能编译。
- AGP / Kotlin / Gradle：三者版本联动，见根 `build.gradle.kts`。
- Chaquopy：升级后同步改 `app/build.gradle.kts` 的 `chaquopy` 块和 Python 版本。
- 改完跑 `assembleDebug` + 真机安装，不要只看编译通过。

### 替换工具链二进制

`aapt2` / `zipalign` 以 `libaapt2_exec.so`、`libzipalign_exec.so` 的形式放在
`app/src/main/jniLibs/arm64-v8a/`，运行时从 `nativeLibraryDir` 执行。替换时保持
文件名与 `packaging.jniLibs.keepDebugSymbols` 的配置一致。

Capstone 的 JNI 封装是 `engine/src/main/jniLibs/arm64-v8a/libwbcapstone.so`；
`engine/src/main/cpp` 里的 C 源码目前**没有**接入 CMake，改源码不会生效。

### 发布

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`。
2. 本地 `keystore.properties`（git 忽略）或 CI secrets：
   `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。
3. push 到 `main` 触发 `.github/workflows/build.yml`，产物名
   `smilehub-<version>-<sha>-{debug,release}.apk`。
4. 没有签名 secret 时 release 会产出 unsigned APK，这是预期行为。

## 7. 排障对照

| 现象 | 先看 | 常见原因 |
| --- | --- | --- |
| 回复中途停止 | `logcat -s WBHub:*` | `Software caused connection abort`：连接被中断，客户端会自动续写一次 |
| ADB 配对失败 | 设置页状态 + logcat `adb pair` / `mdns` | 离开无线调试页导致配对服务下线；缺 Conscrypt；mDNS 超时 |
| 下载的二进制跑不了 | 是否在 `tools/bin` | `/sdcard` 是 noexec，必须放私有目录；架构要 arm64 |
| `dlopen failed: needs libc.so.6` | 被加载的 .so | glibc 构建，Android 用 bionic，无法加载 |
| `exec format error` | 二进制架构 | x86_64 二进制不能跑在 arm64 设备 |
| Python 崩溃 | 是否调用了 ctypes | `wbhub_coderunner` 已禁用 ctypes/cffi，原生段错误会杀进程 |
| 悬浮窗不显示 | 权限 + 高级设置开关 | `overlayEnabled` 默认关闭；addView 失败后不会自动重试 |
| 转发端口被占用 | `BridgeServer.start()` 日志 | 端口冲突时状态仍可能显示“转发中”，先停再启 |

## 8. 已知技术债（接手优先处理）

1. 退出登录链路未接通：`LogoutDialog` 未渲染，`logout()`/`clearAgent()` 无调用。
2. 修改“本机密钥”后 `restartBridge()` 仍用旧 `apiKey`。
3. `BridgeServer` 在鉴权/限额前按 `Content-Length` 分配内存，且线程池无界。
4. `AgentTools.bash` 先 `readText()` 再 `waitFor()`，超时参数实际不生效。
5. `Gzip.postChat` 重试时泄漏第一个 `HttpURLConnection`。
6. 模型列表请求失败会清空已保存的 model/effort/context。
7. 删除会话后，已排队的 `ToolEnd/Failure` 事件仍会把它写回磁盘。
8. `CallLogStore` 的锁是实例级，MainActivity 与 BridgeService 会互相覆盖。
9. `python` 工具并发时共享解释器全局状态（`os.chdir`、`sys.path`、stdout）。
10. 凭据、ADB 私钥、TLS CA 私钥明文存放在 `/sdcard/WBHub`。
11. `targetSdk 28` 的平台安全策略缺失，长期需要重新设计执行模型。

## 9. 第三方与许可

完整依赖清单见 README 的「技术栈与依赖」。项目本体为 MIT；内置的
apktool / jadx（Apache-2.0）、smali、Capstone（BSD）、Chaquopy 等遵循各自许可，
分发时保留对应声明。`app/libs/apktool-lib-2.10.0-wbhub.jar` 为定制构建，升级或再
分发前确认其来源与条款。
