# `ad_config.json` 配置说明

`ad_config.json` 是 SDK 的广告策略配置文件，默认文件位于 `core/src/main/res/raw/ad_config.json`，本文同目录的 [ad_config.json](ad_config.json) 是配置示例。SDK 初始化时先读取打包资源，再读取本地保存的配置；本地已保存的字段会覆盖资源中的值。修改打包资源后需要重新打包应用才能生效，仅修改文档目录中的示例不会改变运行配置。

## 顶层字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `defaultPlatform` | 字符串 | 默认广告平台。可选值：`ADMOB`、`MAX`、`UNITY`、`TRADPLUS`、`TOPON`。必须使用枚举名称的大写形式。 |
| `activePlatforms` | 字符串数组 | 额外启用的广告平台列表，供平台初始化和广告填充流程使用。数组中的每个值都必须是上述平台枚举值。 |
| `isOpen` | 布尔值 | 广告展示总开关。`true` 允许进入展示策略检查，`false` 时展示请求会被策略拦截。 |
| `showMaxTime` | 整数 | 广告池没有可用广告时，等待加载完成的最长时间，单位为毫秒。超时后本次展示失败；不用于限制广告播放时长。 |
| `showMinTime` | 整数 | 展示广告前的最短等待时间，单位为毫秒；广告加载耗时也计入该等待时间。 |
| `showMod` | 整数 | 预留的广告展示模式标识，示例为 `555`；当前代码仅读取和保存该值，尚未据此选择展示行为。 |
| `1HMax` | 整数 | 全局滚动 1 小时内允许展示的广告数量上限。达到上限后暂停展示。 |
| `24HMax` | 整数 | 全局滚动 24 小时内允许展示的广告数量上限。达到上限后暂停展示。 |
| `ad_units` | 对象 | 广告点位配置集合。对象键是业务调用时使用的 `areaKey`（例如 `content1_start`）。 |

`defaultPlatform` 和 `activePlatforms` 是资源 JSON 的必填字段。其他顶层标量字段首次加载时的缺省值为：`isOpen=true`、`showMaxTime=10000`、`showMinTime=500`、`showMod=555`、`1HMax=50`、`24HMax=50`。

`showMaxTime` 建议大于 `0`，`showMinTime`、各次数上限及点位 `interval` 应设置为非负数。全局上限和点位上限会同时检查，任一上限达到后都不会展示广告；上限为 `0` 表示不允许展示。次数与最近一次展示时间依据本地 `ad_impression` 日志计算。

## `ad_units` 点位字段

每个点位对象支持以下字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `rate` | 小数 | 展示概率，取 `0.0` 到 `1.0`；`1.0` 表示通过概率检查，`0.0` 表示始终不展示，仍需满足其他展示条件。 |
| `1HMax` | 整数 | 该点位滚动 1 小时内的展示次数上限。 |
| `24HMax` | 整数 | 该点位滚动 24 小时内的展示次数上限。 |
| `interval` | 整数 | 该点位两次展示之间的最短间隔，单位为秒。 |
| `format` | 字符串 | 必填。广告展示类型：`OPEN`（开屏）、`INTER`（插屏）、`VIDEO`（激励视频）；组合类型 `OPEN_INTER` 定义为开屏优先、插屏候补，`INTER_VIDEO` 定义为插屏优先、视频候补，当前实现情况见下文。 |
| `fromRoutes` | 字符串数组 | 允许展示广告的来源路由。使用 `"*"` 表示任意来源路由；未匹配时不会展示。 |
| `toRoutes` | 字符串数组 | 允许展示广告的目标路由。使用 `"*"` 表示任意目标路由；未匹配时不会展示。 |

点位的 `areaKey` 必须与业务请求传入的键完全一致（区分大小写）。`fromRoutes` 和 `toRoutes` 同时满足时，点位才会进入展示概率判断。路由按完整字符串匹配，只有单独的 `"*"` 表示任意路由，不支持部分通配。省略路由数组或设置为空数组均无法通过匹配；点位次数上限省略时为 `0`，因此建议完整填写点位字段。

当前广告填充实现仅支持 `ADMOB`，展示分发也会回退到 AdMob；其他平台枚举可被解析，但不能据此认定已有完整的加载和展示支持。`OPEN_INTER`、`INTER_VIDEO` 的展示方法目前仍是占位实现，尚未执行组合广告展示；需要实际展示时请使用已实现的 `OPEN`、`INTER`、`VIDEO`。

使用 `BaseScreen` 页面广告导航时，SDK 会按页面名生成以下点位键：`<页面名>_start`（首次进入）、`<页面名>_back`（返回）和 `<页面名>_leave`（离开页面前导航）。例如页面名为 `content1` 时，对应点位为 `content1_start`、`content1_back`、`content1_leave`。

## 示例

```json
{
  "defaultPlatform": "ADMOB",
  "activePlatforms": ["TRADPLUS", "TOPON"],
  "isOpen": true,
  "showMaxTime": 10000,
  "showMinTime": 500,
  "showMod": 555,
  "1HMax": 50,
  "24HMax": 50,
  "ad_units": {
    "content1_start": {
      "rate": 1.0,
      "1HMax": 4,
      "24HMax": 20,
      "interval": 30,
      "format": "OPEN",
      "fromRoutes": ["*"],
      "toRoutes": ["*"]
    }
  }
}
```

JSON 中的平台和广告类型值必须使用代码定义的枚举名称；拼写错误或使用未定义值会导致配置解析失败。`ad_units` 为空或缺少指定点位时，该点位不会展示广告。
