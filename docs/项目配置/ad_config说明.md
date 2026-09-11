# `ad_config.json` 配置说明

文件位于 `core/src/main/res/raw/ad_config.json`，用于配置 SDK 的广告展示策略。初始化先读取资源，再以本地 `Preference` 覆盖；修改资源后需重新打包应用。广告位键（`ad_units` 的对象键）必须与业务调用传入的 `areaKey` 完全一致。

## 顶层字段

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | ---: | --- |
| `defaultPlatform` | 字符串 | `ADMOB` | 默认广告平台，可选 `ADMOB`、`MAX`、`UNITY`、`TRADPLUS`、`TOPON`。 |
| `activePlatforms` | 字符串数组 | `[TRADPLUS, TOPON]` | 额外启用的平台列表。 |
| `isOpen` | 布尔值 | `true` | 广告展示总开关。 |
| `showMaxTime` | 整数（毫秒） | `10000` | 广告加载等待的最长时间。 |
| `showMinTime` | 整数（毫秒） | `500` | 展示广告前的最短等待时间。 |
| `showMod` | 整数 | `555` | 展示模式标识，当前仅保存该值。 |
| `1HMax` / `24HMax` | 整数 | `50` / `50` | 全局滚动 1 小时 / 24 小时展示次数上限。 |
| `interval` | 整数（秒） | `30` | 全局两次广告展示之间的最小间隔。 |
| `ad_units` | 对象 | — | 广告点位配置集合。 |

## `ad_units` 点位字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rate` | 小数 | 展示概率，范围 `0.0`–`1.0`。 |
| `1HMax` / `24HMax` | 整数 | 该点位滚动 1 小时 / 24 小时展示次数上限。 |
| `interval` | 整数（秒） | 该点位两次展示之间的最小间隔。 |
| `format` | 字符串 | `OPEN`、`INTER`、`VIDEO`，或暂作为占位的组合值 `OPEN_INTER`、`INTER_VIDEO`。 |
| `fromRoutes` / `toRoutes` | 字符串数组 | 允许的来源 / 目标路由；单独使用 `"*"` 表示任意路由。 |

路由按完整字符串匹配，不支持部分通配；两组路由都匹配后才进入概率判断。当前广告填充实现主要支持 AdMob，组合格式仍是占位实现。

示例资源：[`ad_config.json`](../../core/src/main/res/raw/ad_config.json)。

`impl` 的 `Mar2Application` 会在应用从后台回到前台时请求 `app_foreground_open` 点位，冷启动不触发。此点位的 `format` 必须为 `OPEN`，并沿用全局及点位的频次、间隔和概率策略。请求的来源和目标路由均为空，通常配置为 `["*"]`。如使用 Preference 或 Remote Config 覆盖 `ad_units`，也需要包含此点位。
