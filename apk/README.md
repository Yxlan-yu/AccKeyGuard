# AccGuard 1.0.0

无障碍服务保活的 **LSPosed 模块 + 原生 APK** 形态，与仓库根目录的 `AccKeyGuard`（Magisk / KernelSU 模块 + WebUI）并存，
两者互不依赖，可以只装其中一个。

- 包名：`com.yxlanyu.acckeyguard`
- 版本：`1.0.0`（versionCode 100）
- 最低系统：Android 8.0（API 26），目标 34
- 权限：仅 `RECEIVE_BOOT_COMPLETED`，**不申请网络权限，不上传任何数据**

## 保活原理（零轮询）

zip 版是靠 `accd.sh` 每 20 秒比对并回写；APK 版改成 **框架层双层拦截**，被移除的瞬间就补回，没有轮询、没有常驻脚本：

1. **写侧**：hook `com.android.providers.settings` 进程里的
   `SettingsProvider.call` / `insert` / `bulkInsert`。当写入的是
   `Settings.Secure.enabled_accessibility_services` 时，先把「已勾选守护的组件」合并回新值再落库。
   组件名统一归一化为全类名（`.Foo` 自动展开成 `pkg.Foo`）。
2. **读侧**：hook `android`（system_server）里的 `Settings$Secure.getStringForUser`，
   读取时再兜一层，避免某些系统路径读到被裁剪后的值。

配置与状态都不落文件，走 **安全设置通道**：

- 配置存 `Settings.Secure.akg_guard`，JSON：`{"v":1,"enabled":true,"services":["pkg/cls"]}`
  （选它是因为这是设置 provider 与 system_server 都能直接读到的唯一共享存储）
- App 与 hook 之间用 `ContentResolver.call("content://settings/secure", ...)`：
  `akg_cfg`（读配置）、`akg_status`（读 hook 状态）、`akg_cmd`（开关 / 应用 / 一键恢复 / 清理失效项）

hook 侧缓存 2000ms，system 侧用 `ThreadLocal` 防递归，回调全部包 `try/catch`，
任何异常都不会抛进系统进程。

### 传输降级

| 顺序 | 通道 | 说明 |
| --- | --- | --- |
| 1 | framework hook | 正常路径，插件生效时可用 |
| 2 | 设置通道（provider hook） | hook 存活但 `call` 不经过 system 侧时使用 |
| 3 | root | `su -c settings put secure ...`，没有 LSPosed 时的兜底 |
| 4 | 无 | 界面直接提示「需要 LSPosed 或 root」，不静默失败 |

## 界面

纯 Java + 原生 View + 自绘 Canvas 控件（`Ring` / `Bar` / `Toggle` / `TabIcon`），无第三方依赖、
无 AndroidX、无 Compose。深色仪表盘风格，顶部三个图标切换：

- **列表**：总览卡（守护数 / 在线数 / 待恢复数）+ 筛选 + 服务行，点行开底部弹层看详情
- **诊断**：hook 通道、计数、存储原值、合并预览、心跳标记、一键复制诊断
- **设置**：保活总开关、立即应用、一键恢复、清理失效项、清理日志、关于

## 构建

CI：`.github/workflows/build-apk.yml`（Actions 手动触发或 `apk/**` 变更时自动跑）

- JDK 17（temurin）、Gradle 9.5.1、AGP 9.2.1、compileSdk 37
- 工程根目录是仓库的 `apk/` 子目录
- 签名固定用 `app/akg.keystore`（别名 `akg`，口令 `akg2026guard`），保证每次构建签名一致，可直接覆盖安装
- 产物：`app/build/outputs/apk/release/app-release.apk`

本地构建（需要 JDK 17 + Android SDK 37）：

```bash
cd apk
gradle :app:assembleRelease
```

`xposedapi/` 只提供编译期桩类（`IXposedHookLoadPackage`、`XC_MethodHook`、`XposedBridge` 等），
以 `compileOnly` 引入，**不会打进 APK**；运行时由 LSPosed 提供真实实现。

## 安装

1. 安装 APK（LSPosed 管理器里也要能识别到「AccGuard」）
2. LSPosed → 模块 → 启用 AccGuard，作用域勾选 **系统框架（android）** 与 **设置存储（com.android.providers.settings）**
3. 重启手机（或重启这两个进程）
4. 打开 AccGuard，勾选需要保活的无障碍服务 —— 立即生效，不需要再次重启

卸载后 hook 消失，`Settings.Secure.akg_guard` 里残留的配置不再起作用，可忽略。

## 目录

```
apk/
├── .github/workflows/build-apk.yml   CI
├── build.gradle / settings.gradle / gradle.properties
├── app/
│   ├── akg.keystore                  固定签名
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init        入口类声明
│       ├── java/com/yxlanyu/acckeyguard/
│       │   ├── MainActivity.java     三页仪表盘 + 底部弹层
│       │   ├── BootReceiver.java     开机兜底（仅 root 通道时有用）
│       │   ├── data/                 Engine / Store / Services / Status / Shell / HookLink / Model
│       │   ├── ui/                   Theme / Ring / Bar / Toggle / TabIcon
│       │   └── xposed/               HookEntry / Hooks / Guard
│       └── res/                      图标、颜色、主题、字符串
├── xposedapi/                        编译期桩类（不打包）
├── check_text.py                     UTF-8 / BOM / CRLF / U+FFFD 体检
└── make_assets.py                    重新生成图标与签名密钥
```
