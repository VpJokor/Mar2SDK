# `ad_config.json` 配置说明

文件位于 `core/src/main/res/raw/ad_config.json`，用于配置 SDK 的广告展示策略。初始化先读取资源，再以本地 `Preference` 覆盖；修改资源后需重新打包应用。广告位键（`ad_units` 的对象键）必须与业务调用传入的 `areaKey` 完全一致。

## 顶层字段

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | ---: | --- |
| `defaultPlatform` | 字符串 | `ADMOB` | 默认广告平台，可选 `ADMOB`、`MAX`、`UNITY`、`TRADPLUS`、`TOPON`。 |
| `activePlatforms` | 字符串数组 | `[TRADPLUS, TOPON]` | 额外启用的平台列表。 |
| `explorePlatforms` | 字符串 | `TRADPLUS` | 探索广告平台，使用 `AdPlatform` 枚举名称。 |
| `priceMultiplier` | 数值 | `1.0` | 价格倍率。 |
| `priceOffset` | 数值 | `0.0` | 价格偏移量。 |
| `isOpen` | 布尔值 | `true` | 广告展示总开关。 |
| `isRisk` | 布尔值 | `false` | 开启时，`UNKNOW`、`NATURE` 用户使用 `RISK` 的广告和通知策略；其他用户类型不变。 |
| `showMaxTime` | 整数（毫秒） | `10000` | 广告加载等待的最长时间。 |
| `showMinTime` | 整数（毫秒） | `500` | 展示广告前的最短等待时间。 |
| `showMod` | 字符串 | `MODE_555` | `ShowMod` 枚举名称，可选 `MODE_555`、`MODE_666`、`MODE_777`、`MODE_888`。 |
| `1HMax` / `24HMax` | 整数 | `50` / `50` | 全局滚动 1 小时 / 24 小时展示次数上限。 |
| `interval` | 整数（秒） | `30` | 全局两次广告展示之间的最小间隔。 |
| `ad_units` | 对象 | — | 广告点位配置集合。 |

`showMod` 的 JSON 配置和本地 `Preference` 均使用枚举名称字符串，不再支持整数。已有旧整数数据时，需清理本地 `mar2sdk.ad_config.showMod` 配置或清除应用数据。

`isRisk` 不改变原始用户分类。应用 Remote Config 时，先读取原用户类型的 `ad_config_<用户类型>` 中的 `isRisk`，缺失则沿用当前值；开启后为 `UNKNOW`、`NATURE` 选择 `ad_config_RISK` 和 `notification_config_RISK`，通知中的风险分支也使用该策略分类。`ad_config_RISK` 内的开关值不会反向覆盖这次选择依据。关闭原用户类型配置中的开关后，下次应用配置恢复该类型的策略；远程分组缺失时保留当前配置。

## `ad_units` 点位字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rate` | 小数 | 展示概率，范围 `0.0`–`1.0`。 |
| `1HMax` / `24HMax` | 整数 | 该点位滚动 1 小时 / 24 小时展示次数上限。 |
| `interval` | 整数（秒） | 该点位两次展示之间的最小间隔。 |
| `format` | 字符串 | `OPEN`、`INTER`、`VIDEO`，或比价组合值 `OPEN_INTER`、`INTER_VIDEO`、`VIDEO_INTER`；同价或任一价格缺失时优先选择名称中的第一种格式。 |
| `fromRoutes` / `toRoutes` | 字符串数组 | 允许的来源 / 目标路由；单独使用 `"*"` 表示任意路由。 |

路由按完整字符串匹配，不支持部分通配；两组路由都匹配后才进入概率判断。当前广告填充及组合格式比价展示主要支持 AdMob。

示例资源：[`ad_config.json`](../../core/src/main/res/raw/ad_config.json)。

`impl` 的 `Mar2Application` 会在应用从后台回到前台时请求 `app_foreground_open` 点位，冷启动不触发。此点位的 `format` 必须为 `OPEN`，并沿用全局及点位的频次、间隔和概率策略。请求的来源和目标路由均为空，通常配置为 `["*"]`。如使用 Preference 或 Remote Config 覆盖 `ad_units`，也需要包含此点位。
