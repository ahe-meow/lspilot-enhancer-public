# libxposed API 102 审计记录

更新时间：2026-08-09

本文记录对 `libxposed` 官方组织仓库及本项目现代 LSPosed API 接入情况的审计结果，供后续开发、重构和发布前检查使用。

研究基线：

- `libxposed/api`：稳定 API `102.0.0`，研究 commit `39cac08`
- `libxposed/service`：稳定 service `102.0.0`，研究 commit `3318940`
- `libxposed/example`：API 102 官方示例，研究 commit `b94ee7e`
- `libxposed/helper`：研究 commit `d2fb47f`，默认分支主要面向 API 100，不作为 API 102 规范基线
- `libxposed/lint`：研究 commit `f02cf91`
- 本地研究目录：`/root/libxposed-research`

参考资料：

- https://github.com/libxposed/api
- https://github.com/libxposed/service
- https://github.com/libxposed/example
- https://github.com/libxposed/helper
- https://github.com/libxposed/lint
- https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API

## 结论摘要

项目已经使用现代 `libxposed` API，入口、`java_init.list`、`scope.list`、`module.prop` 和 `hook(Executable).intercept(...)` 的基本形态正确。当前最重要的问题不是迁移旧 API，而是 `autoHotReload=true` 与实际生命周期实现不一致，以及 API 102 提供的 Hook ID、HookHandle 替换和热重载能力尚未接入。

在热重载生命周期完成前，不应仅把 `onHotReloading()` 改为返回 `true`。本项目有后台线程、静态状态、Activity/ViewModel/宿主对象引用和异步回调，错误开启热重载可能导致旧 ClassLoader 无法回收、重复 Hook、旧回调继续执行或进程崩溃。

## P0：发布前必须处理

### 1. `autoHotReload` 配置与生命周期实现不一致

现状：

- `app/src/main/resources/META-INF/xposed/module.prop` 设置了 `autoHotReload=true`。
- `LSPilotEnhancerModule` 没有实现 `onHotReloading(HotReloadingParam)` 或 `onHotReloaded(HotReloadedParam)`。
- API 102 默认 `onHotReloading()` 返回 `false`，所以自动热重载请求会被拒绝。
- 当前代码在 `ManualCompressionManager`、`DebugLogger` 和 UI 控制器中持有静态 Executor、Handler、Activity、ViewModel、Repository、Method 和宿主 ClassLoader 相关引用。

后续方案，二选一：

1. 在正式实现热重载前，将 `autoHotReload` 改为 `false`，继续要求完全重启目标进程。
2. 完整实现热重载协议：保存所有 `HookHandle`，为每个 Hook 设置稳定 ID；在 `onHotReloading()` 中停止 Executor/线程、取消异步任务、解绑外部回调、清理宿主对象和 ClassLoader 引用；在 `onHotReloaded()` 中通过 `replaceHook()` 原子替换仍需保留的 Hook，并重新建立运行时状态。

验收条件：

- 热重载完成后不会出现重复 Hook。
- 旧代代码的后台任务、Handler 回调和宿主引用全部停止或失效。
- 旧 ClassLoader 不再被模块静态字段或 Hook 闭包强引用。
- 热重载失败时目标进程仍可继续运行，且不会留下半初始化状态。

### 2. Hook 没有使用 API 102 的稳定 ID 和句柄管理

相关位置：

- `LSPilotEnhancerModule.installRequestHook`
- `installSseUsageHook`
- `installUiHooks`
- `installChatRouteHook`
- `installChatViewModelHook`
- `installSendBeforeCompressionHook`
- `installChatButtonHook`

现状：

- 所有 Hook 都直接调用 `hook(method).intercept(...)`。
- 没有保存返回的 `HookHandle`。
- 没有调用 `setId(...)`。
- API 102 的 `replaceHook()` 和热重载传递的旧 Hook 句柄没有被利用。

后续建议：

- 为每个逻辑 Hook 使用稳定且唯一的 ID，例如 `request.buildOpenAiRequestBody`、`chat.sendMessage`。
- 将句柄保存到模块实例或明确生命周期对象中，不要无界存入静态集合。
- 初次安装和热重载安装路径分离：初次安装创建 Hook，热重载使用旧句柄 `replaceHook()` 或明确 `unhook()` 后重新创建。
- 不要依赖“重复安装不会发生”这一假设。

### 3. 热重载与模块级后台资源没有统一的关闭协议

相关位置：

- `ManualCompressionManager.EXECUTOR`
- `ManualCompressionManager.STATUS_EXECUTOR`
- `DebugLogger.FILE_EXECUTOR`
- `ManualCompressionManager.MAIN_HANDLER`
- `InjectedUiController` 的静态 Activity、Dialog 和 View 引用

现状：

- Executor 使用静态单例，未提供关闭、取消和 generation 级隔离 API。
- 压缩任务通过静态状态和回调继续向 UI、Repository 和宿主 ViewModel 写入。
- 日志线程、状态线程和主线程回调没有统一失效标记。

后续建议：

- 引入模块代际或运行时上下文对象，所有异步任务携带 generation token。
- 关闭旧代时取消任务、清空 Handler 回调、禁止状态消息写入，并等待线程退出。
- Activity、ViewModel、Repository、ClassLoader 和动态 Proxy 只允许由当前代上下文持有。
- 如果暂不实现热重载，至少补充显式的进程级清理接口，便于异常和测试处理。

## P1：下一轮优化处理

### 4. 缺少官方推荐的 Hook 异常模式显式配置

现状：

- `module.prop` 未声明 `exceptionMode`。
- 单个 Hook 也没有统一调用 `setExceptionMode(...)`。
- 当前行为依赖框架默认值。API 文档说明默认通常为 protective，但模块没有将这一安全意图固化。

建议：

- 对请求修改、聊天 UI 和状态观察类 Hook 明确使用 `ExceptionMode.PROTECTIVE`，避免模块异常破坏宿主。
- 对专门的调试 Hook 才考虑 `PASSTHROUGH`，并只在诊断构建或明确测试场景启用。
- 在 `module.prop` 中写明 `exceptionMode=protective`，让发布配置自描述。
- 无论采用哪种模式，都要保证 Hook 内部不会吞掉 `chain.proceed()` 抛出的宿主异常。

### 5. 模块配置使用目标 App 私有 SharedPreferences，而不是现代 Remote Preferences

相关位置：

- `ModuleSettings.initialize(...)`
- `ModuleSettings.preferences()`
- `InjectedUiController.prepare(...)`

现状：

- `ModuleSettings` 从注入进程的 `Context` 获取 SharedPreferences。
- 该 Context 是目标 App 的 Context，因此设置保存在 `me.yun.lspilot` 的私有数据中。
- 项目没有使用 `libxposed/service` 的 Remote Preferences，也没有模块 App 与 Hook 进程之间的配置同步机制。

这不一定是错误：如果设计目标是仅在目标 App 内配置，这种方式可以工作。但它带来以下限制：

- 配置依赖目标 App 数据生命周期，清除目标 App 数据会丢失设置。
- 不方便从独立模块 App 或外部控制面板管理配置。
- 多进程或多代热重载时缺少统一的配置来源。
- 目标 App 的备份、迁移和权限模型会影响模块配置。

当前决策：

- 配置入口仅保留 LSPilot 宿主内的设置弹窗。
- 设置直接保存在 `me.yun.lspilot` 的私有 SharedPreferences 中，Hook 与设置 UI 在同一进程读写。
- 不提供独立配置 App，也不引入 `libxposed/service` 或跨进程配置同步。

### 6. Manifest 缺少官方现代 API 建议的 `android:description`

相关位置：`app/src/main/AndroidManifest.xml`

现状：

- 已设置 `android:label`，但未设置 `android:description`。
- 现代 API 规范使用 Android Manifest 的 label 作为模块名、description 作为模块描述。

建议：

- 增加字符串资源并设置 `android:description`。
- 不要把描述依赖写死在 Manifest，便于本地化和发布版本管理。

### 7. R8/混淆规则缺失

相关位置：`app/build.gradle.kts`

现状：

- 当前 release 构建关闭了 minification，因此暂时不会触发入口被移除的问题。
- 项目没有官方 API README 推荐的 ProGuard/R8 规则。

如果未来启用混淆，应加入：

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```

启用后必须验证 `java_init.list` 中的类名已被 `adaptresourcefilecontents` 正确重写，并在最终 APK 中检查入口文件。

### 8. 构建配置明显落后于官方 API 102 示例，需要独立验证兼容性

现状：

- 本项目 `compileSdk=29`、`targetSdk=29`、Java 8。
- 官方 API 102 example 使用更高的 compile/target SDK 和 Java 21。
- 项目使用本地 `lib/libxposed-api-102.0.0.aar`，而不是 Maven 坐标。

这不代表必须直接升级到官方 example 的全部版本。当前模块最低版本 26，旧 target 可能是为了兼容宿主或安装环境。但需要明确验证：

- API 102 AAR 在当前 Android Gradle Plugin 和 Java 8 编译设置下是否稳定。
- AGP 9.3.0 与 compileSdk 29 的组合是否仍受支持。
- 目标设备 Android 版本和 LSPosed 实现是否满足 API 102 要求。
- 是否应改为 Maven `compileOnly("io.github.libxposed:api:102.0.0")`，或在仓库中记录本地 AAR 的来源、校验值和更新流程。

当前工具链已成功完成 `:app:assembleDebug` 和 `:app:assembleRelease`，并核验 release APK 的 package、version 和 SDK metadata。由于 `targetSdk=29` 是当前宿主兼容策略且模块不通过 Google Play 分发，构建仅禁用了 `ExpiredTargetSdkVersion` 这一项 Play 分发 lint；目标设备与 API 102 运行时兼容性仍需继续实机验证。

## P2：持续改进项

### 9. 生命周期回调和目标进程过滤需要显式化

当前主要安装逻辑都在 `onPackageReady()` 中完成，并通过 package name 过滤目标 App。现代 API 文档提醒：一个进程可能加载多个 package，生命周期回调也可能被多次触发。

后续建议：

- 在 `onModuleLoaded()` 中记录 process name、framework name、API version 和 properties。
- 明确记录并校验目标进程名，必要时只对 `me.yun.lspilot` 主进程安装 Hook。
- 使用一次性安装状态或按 ClassLoader 维度去重，防止重复回调导致重复初始化。
- 不要把 `param.isFirstPackage()` 当作唯一过滤条件；它只表示进程内第一个 package。
- 对非目标 package 尽早 `detach()` 只应在确认该 entry 不再需要后使用，避免影响同一进程中的后续目标回调。

### 10. 反射 ABI 假设集中且缺少自动回归验证

项目大量依赖宿主混淆后的精确类名、方法名、参数顺序和 Compose synthetic 方法签名。API 102 本身不解决宿主 ABI 变化问题。

重点风险：

- `AiChatScreenMiuix` 参数列表。
- `SubScreenActivity.onCreate$lambda$0$1`。
- `ArrowPreference` 的 16 参数 ABI。
- `AiChatMessage` 的长构造函数。
- `AiChatRepository.addMessage`。
- Provider `buildOpenAiRequestBody` 和 `scanSseData`。

后续建议：

- 为每个 Hook 建立 ABI 探测和版本化日志。
- 把反射查找从业务逻辑中抽成可测试的 resolver。
- 使用稳定签名、返回值和参数类型组合匹配，减少只按方法名查找。
- 在无法匹配时让单个功能降级，不要让整个模块初始化失败。
- 对关键宿主版本保存离线 fixture 或最小反射测试。

### 11. 现有自定义反射缓存需要防止 ClassLoader 泄漏

`ManualCompressionManager` 的无参 Method 缓存以 `targetClass.getName() + '#' + methodName` 为 key，并保存 `Method` 强引用。API 102 热重载会产生新的模块代际和潜在的新宿主 ClassLoader；如果旧缓存不清理，可能保留旧 ClassLoader。

后续建议：

- 缓存按 ClassLoader 或 Class 对象隔离，而不是只按类名字符串隔离。
- 热重载或宿主进程切换时清空缓存。
- 若需要跨代缓存，使用弱引用并验证 declaring class 属于当前 ClassLoader。
- 同样检查静态 `Method`、`Class`、Proxy、Activity 和 ViewModel 字段。

### 12. 本地研究仓库中的 API 103 内容暂不作为实现依据

`libxposed/api` master 当前包含 API 102 稳定代码及未发布的 API 103 方向提交，例如 Dex SQL/object scan 相关分支。它们不是当前稳定 Maven API 规范。

后续规则：

- 生产代码以 tag `102.0.0` 和官方 example API 102 为准。
- 只有在明确切换到 API 103 后，才研究对应分支、坐标和 framework 支持情况。
- 不要根据 master 中未发布的接口修改当前模块。

## 已确认正确的部分

以下部分当前无需迁移：

- `LSPilotEnhancerModule` 继承 `io.github.libxposed.api.XposedModule`。
- `META-INF/xposed/java_init.list` 使用完整 Java 类名。
- `META-INF/xposed/scope.list` 使用 `me.yun.lspilot`。
- `module.prop` 包含 `minApiVersion`、`targetApiVersion` 和 `staticScope`。
- Hook 使用 `Executable` 和 interceptor chain，而不是旧版 `XposedBridge` API。
- 项目未使用旧版 `de.robv.android.xposed`、`XposedHelpers`、`XC_MethodHook` 或 `assets/xposed_init`。
- 现代 API 已移除资源 Hook，当前项目的运行时 View/Compose 注入方向符合这一限制。

## 后续开发前检查清单

- [ ] 决定关闭 `autoHotReload`，或完成完整 API 102 热重载实现。
- [ ] 为全部 Hook 增加稳定 ID，并保存/管理 `HookHandle`。
- [ ] 建立后台线程、Handler、异步回调和宿主引用的清理协议。
- [x] 明确采用宿主进程 SharedPreferences，且只保留宿主设置弹窗。
- [ ] 增加 Manifest `android:description`。
- [ ] 若启用 R8，加入官方入口保留和资源列表改写规则。
- [ ] 解决 Android SDK/构建环境后重新执行完整 debug/release 构建。
- [ ] 增加按宿主版本的反射 ABI 探测和回归测试。
- [ ] 检查反射缓存和静态字段是否会跨 ClassLoader 泄漏。
- [ ] 继续以 API 102 稳定 tag 为规范，不使用 API 103 未发布内容。

## 当前工作区注意事项

记录本文时没有修改现有源码。创建本文前工作区已经存在以下未提交内容，后续开发必须保留并单独审阅：

- `CHANGELOG.md`
- `app/src/main/java/dev/operit/lspilot/enhancer/ContextCompression.java`
- `app/src/main/java/dev/operit/lspilot/enhancer/DebugLogger.java`
- `app/src/main/java/dev/operit/lspilot/enhancer/ManualCompressionManager.java`
- `app/src/main/java/dev/operit/lspilot/enhancer/ModuleSettings.java`
- 未跟踪目录 `.backup/`