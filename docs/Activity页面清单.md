# Activity 页面清单

本文档按当前仓库的 `app`、`impl`、`debug` 三个模块整理。依据各模块的 `AndroidManifest.xml` 和主源码，当前示例 APP 的**项目自有运行时 Activity 共 6 个**。

`core` 是 SDK 核心库，虽然部分 API 接收 `Activity` 参数，但 `core/src/main/AndroidManifest.xml` 没有注册 Activity，因此不单独提供页面。

最终 APK 的合并 Manifest 还会包含 Google Mobile Ads、Chartboost、Unity 等第三方广告 SDK 自带的 Activity。这些是 SDK 内部承载页或跳转页，不属于本项目的业务功能页面，本文不列入统计。

## 一、运行时 Activity

| Activity | 模块 / 完整类名 | Manifest 状态 | 页面类型 | 主要功能与入口 |
| --- | --- | --- | --- | --- |
| `MainActivity` | `app` / `com.mar2sdk.MainActivity` | `exported=true`；包含 `MAIN` + `LAUNCHER`，应用启动页 | Compose 主页面 | 展示示例 APP 主入口。可进入调试页、开屏页、Compose 内容页 1/2，以及 View/XML 内容页 1。页面内部使用 `NavHost` 管理 Compose 路由。 |
| `Content1Activity` | `app` / `com.mar2sdk.Content1Activity` | `exported=false` | View/XML 业务示例页 1 | 使用 `activity_content1.xml`，页面名为 `content1`。点击“下一页”时通过 `ContentActivity.navigateWithAd()` 跳转到 `Content2Activity`，跳转前可执行离开广告。 |
| `Content2Activity` | `app` / `com.mar2sdk.Content2Activity` | `exported=false` | View/XML 业务示例页 2 | 使用 `activity_content2.xml`，页面名为 `content2`。点击返回按钮或系统返回键时，先按 `ContentActivity` 的流程处理离开广告，再返回上一个页面。 |
| `AdActivity` | `impl` / `com.mar2sdk.impl.AdActivity` | `exported=false` | SDK 内部广告承载页 | 不作为业务页面直接进入。由 `AdActivity.showAd()` 启动，负责承载开屏、插屏、激励视频等广告展示，展示成功/失败/关闭后自动结束；右上角关闭按钮也可结束页面。 |
| `DebugActivity` | `debug` / `com.mar2sdk.debug.DebugActivity` | `exported=false` | 调试页 | 查看 App 模式、用户类型、IP/包名风险、ECPM 类型和测试模式；切换测试模式/测试用户；请求通知权限、发送测试通知、启动常驻通知；启动开屏、插屏、视频及组合广告测试；进入 `InfoActivity` 查看详细信息。 |
| `InfoActivity` | `debug` / `com.mar2sdk.debug.InfoActivity` | `exported=false` | 调试信息详情页 | 通过 Intent extra `label` 复用同一个页面展示不同信息：`User` 用户信息、`Log` 本地日志、`Config` 配置、`Content` 通知文案、`ad_policy` 广告策略、`notification_policy` 通知策略。广告/通知策略支持刷新，本地日志支持清空。 |

### Activity 之间的主要关系

```text
MainActivity（启动页）
├─ DebugActivity（调试页）
│  └─ InfoActivity（用户/日志/配置/通知/广告策略详情）
├─ Content1Activity（View/XML 内容页 1）
│  └─ Content2Activity（View/XML 内容页 2）
└─ Compose 路由页面（仍由 MainActivity 承载）

ContentActivity 页面发生进入、离开或返回时
└─ AdActivity（SDK 内部广告承载页，按广告策略决定是否启动）
```

## 二、MainActivity 内的 Compose 页面

下列页面是 `MainActivity` 的 Navigation Compose 路由，不是独立 Activity：

| 路由 | Composable | 页面用途 |
| --- | --- | --- |
| `main` | `MainScreen` | 示例 APP 主菜单，提供各功能入口。 |
| `splash` | `SplashScreen` | 开屏页示例；当前 Composable 为空实现。 |
| `content1` | `ContentScreen1` | Compose 内容页 1，可跳转到 `content2`。 |
| `content2` | `ContentScreen2` | Compose 内容页 2，可返回 `content1`。 |

`content1`/`content2` 这两个路由和 `Content1Activity`/`Content2Activity` 是两套示例实现：前者由 Compose 导航承载，后者是传统 View/XML Activity。

## 三、不是独立功能页面的类

- `BaseActivity`：业务 Activity 的基础类，负责通用生命周期和通知打开跟踪，不直接注册页面。
- `ContentActivity`：View/XML 页面基类，负责页面广告和返回/跳转流程，是抽象类，不直接作为页面启动。
- `src/androidTest` 下的测试 Activity：仅用于自动化测试，不属于 APP 运行时功能页面。

## 四、源码依据

- Activity 注册：[`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml)、[`impl/src/main/AndroidManifest.xml`](../impl/src/main/AndroidManifest.xml)、[`debug/src/main/AndroidManifest.xml`](../debug/src/main/AndroidManifest.xml)
- 主页面和 Compose 路由：[`app/src/main/java/com/mar2sdk/MainActivity.kt`](../app/src/main/java/com/mar2sdk/MainActivity.kt)
- View/XML 页面：[`app/src/main/java/com/mar2sdk/Content1Activity.kt`](../app/src/main/java/com/mar2sdk/Content1Activity.kt)、[`app/src/main/java/com/mar2sdk/Content2Activity.kt`](../app/src/main/java/com/mar2sdk/Content2Activity.kt)
- 广告承载页：[`impl/src/main/java/com/mar2sdk/impl/AdActivity.kt`](../impl/src/main/java/com/mar2sdk/impl/AdActivity.kt)
- 调试页面：[`debug/src/main/java/com/mar2sdk/debug/DebugActivity.kt`](../debug/src/main/java/com/mar2sdk/debug/DebugActivity.kt)、[`debug/src/main/java/com/mar2sdk/debug/InfoActivity.kt`](../debug/src/main/java/com/mar2sdk/debug/InfoActivity.kt)

当前 `app` 通过 `implementation(project(":debug"))` 引入调试模块，因此上表中的 `DebugActivity` 和 `InfoActivity` 会被当前示例 APP 使用；如果正式构建移除该依赖及对应入口，这两个调试页面也会随之移除。
