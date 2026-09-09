# `notification_config.json` 配置说明

文件位于 `core/src/main/res/raw/notification_config.json`，用于配置应用通知的发送策略；通知文案单独保存在 [`notification_content说明.md`](notification_content说明.md)。SDK 初始化先读取打包资源，再使用本地 `Preference` 中已保存的字段覆盖；修改资源后需要重新打包应用才能生效。

## 顶层字段

| 字段 | 类型 | 代码默认值（缺省时） | 说明 |
| --- | --- | ---: | --- |
| `isSend` | 布尔值 | `false` | 通知总开关。为 `false` 时不发送通知。 |
| `isForegroundSend` | 布尔值 | `false` | 应用处于前台时是否允许发送。 |
| `isScreenOffSend` | 布尔值 | `false` | 屏幕熄灭时是否允许发送。 |
| `isScreenLockSend` | 布尔值 | `false` | 设备锁屏时是否允许发送。 |
| `interval_second` | 整数（秒） | `60` | 所有通知批次之间的全局最小间隔。 |
| `24HMaxBatch` / `1HMaxBatch` | 整数 | `50` / `5` | 最近 24 小时 / 1 小时允许发送的批次数上限。 |
| `24HMaxItem` / `1HMaxItem` | 整数 | `50` / `5` | 最近 24 小时 / 1 小时允许发送的通知条数上限。 |
| `ChannelCount` | 整数 | `3` | 创建的 Android 通知渠道数量，并用于循环分配通知 ID；当前支持 `1`–`4`。 |
| `triggers` | 对象 | `{}` | 事件触发策略，键为场景名。 |
| `timer` | 对象 | `{}` | 定时触发策略，键为场景名。 |

发送上限按本地日志中成功的批次和条目统计，时间窗口为滚动的最近 1 小时或 24 小时。间隔或上限不满足时会跳过本次发送；上限设为 `0` 会禁止对应发送。

## `triggers` 事件策略

每个键是场景名。业务触发同名场景时，SDK 按以下字段排队发送一批通知：

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | ---: | --- |
| `first_delay` | 整数（秒） | `300` | 首次打开应用后，该场景首次发送前的等待时间。 |
| `delay` | 整数（秒） | `0` | 事件进入队列后等待时间。 |
| `count` | 整数 | `0` | 本批最多发送的通知条数；小于等于 `0` 时不发送。 |
| `styles` | 整数数组 | `[1,2,3,4]` | 允许使用的通知样式编号。 |
| `interval_batch` | 整数（秒） | `0` | 同一场景两批通知之间的最小间隔。 |
| `interval_item` | 整数（秒） | `5` | 同一批通知中相邻条目的发送间隔。 |

## `timer` 定时策略

每个键是场景名，调度器每天在指定本地时间触发一次：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `HH` | 整数 | 小时，范围 `0`–`23`。 |
| `MM` | 整数 | 分钟，范围 `0`–`59`。 |
| `count` | 整数 | 每次定时发送的通知条数；小于等于 `0` 时不创建任务。 |
| `styles` | 整数数组 | 允许使用的通知样式编号，默认 `[1,2,3,4]`。 |

## 示例

```json
{
  "isSend": true,
  "isForegroundSend": false,
  "isScreenOffSend": false,
  "isScreenLockSend": false,
  "interval_second": 60,
  "24HMaxBatch": 50,
  "1HMaxBatch": 5,
  "24HMaxItem": 50,
  "1HMaxItem": 5,
  "ChannelCount": 3,
  "triggers": {
    "screen_on_a": {
      "first_delay": 300,
      "delay": 5,
      "count": 3,
      "styles": [1, 2, 3, 4],
      "interval_batch": 60,
      "interval_item": 5
    }
  },
  "timer": {
    "morning": {"HH": 8, "MM": 30, "count": 1, "styles": [1]}
  }
}
```

缺失的顶层字段沿用代码默认值；`triggers` 或 `timer` 显式设为空对象会清空对应集合。修改配置对象后调用 `saveNotificationConfig()` 会保存策略并刷新定时任务。示例资源：[`notification_config.json`](../../core/src/main/res/raw/notification_config.json)。
