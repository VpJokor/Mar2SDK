# `notification_config.json` 配置说明

`notification_config.json` 是 SDK 的应用通知策略配置文件。默认配置位于 `core/src/main/res/raw/notification_config.json`，本文同目录的 [notification_config.json](notification_config.json) 为可参考和维护的示例。SDK 初始化时先读取打包资源，再读取本地 `Preference` 中已保存的值；本地已保存的字段会覆盖资源文件中的同名字段，修改资源文件后需要重新打包应用才能生效。

## 顶层字段

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | ---: | --- |
| `isSend` | 布尔值 | `false` | 通知总开关。设为 `false` 时不发送任何通知。 |
| `isForegroundSend` | 布尔值 | `false` | 应用位于前台时是否允许发送通知。 |
| `isScreenOffSend` | 布尔值 | `false` | 屏幕熄灭时是否允许发送通知。 |
| `isScreenLockSend` | 布尔值 | `false` | 设备锁屏时是否允许发送通知。 |
| `interval_second` | 整数（秒） | `60` | 所有通知批次之间的全局最小间隔。 |
| `24HMaxBatch` | 整数 | `50` | 最近 24 小时内允许发送的通知批次数上限。 |
| `1HMaxBatch` | 整数 | `5` | 最近 1 小时内允许发送的通知批次数上限。 |
| `24HMaxItem` | 整数 | `50` | 最近 24 小时内允许发送的通知条数上限。 |
| `1HMaxItem` | 整数 | `5` | 最近 1 小时内允许发送的通知条数上限。 |
| `ChannelCount` | 整数 | `3` | 创建的 Android 通知通道数量，同时用于循环分配通知 ID。 |
| `triggers` | 对象 | `{}` | 事件触发的通知批次配置，键为场景名称。 |
| `timer` | 对象 | `{}` | 定时通知配置，键为场景名称。 |
| `contents` | 数组 | `[]` | 通知文案和点击跳转信息。 |

发送上限按本地日志中标记成功的批次或条目统计，时间窗口为滚动的最近 1 小时或 24 小时。`interval_second`、各时间窗口上限以及场景的 `interval_batch` 任一条件未满足时，事件批次会被跳过。建议将数量和间隔设置为非负值；次数上限设为 `0` 会阻止对应发送。当前发送器内置 4 个通知图标，`ChannelCount` 请设置为 `1`–`4`。

## `triggers` 事件触发配置

`triggers` 的每个键都是一个场景名。应用触发该场景时，SDK 会按对应配置排队发送一个批次；未配置的场景不会产生通知。字段如下（时间单位均为秒）：

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | ---: | --- |
| `first_delay` | 整数 | `300` | 首次打开应用后，允许该场景发送前需等待的时间。 |
| `delay` | 整数 | `0` | 本次事件进入队列后，等待多久再创建通知批次。 |
| `count` | 整数 | `0` | 每个批次的通知条数；小于等于 `0` 时不发送该批次。 |
| `styles` | 整数数组 | `[1, 2, 3, 4]` | 该场景允许使用的通知样式编号。 |
| `interval_batch` | 整数 | `0` | 同一场景两次批次发送之间的最小间隔。 |
| `interval_item` | 整数 | `5` | 同一批次内相邻通知的发送间隔。 |

`first_delay` 是相对首次打开应用时间的准入条件，`delay` 是每次事件入队后的等待时间。批次到期时若未满足 `first_delay`、发送状态或限流条件，会直接跳过，不会自动延后重试；已有批次的通知仍在队列中时，也会拒绝新批次。批次通过检查后，每条通知在发送前仍会单独检查状态和条数上限，所以实际发送数量可能少于 `count`。

SDK 内置并在业务代码中使用的场景名包括：

`unlock_home_launcher`（解锁回到桌面）、`screen_on_a`、`screen_on_b`、`screen_on_c`（亮屏阶段）、`screen_off_locked`（熄屏锁定）、`power_connected`（接入电源）、`return_to_home`（返回桌面）、`ad_click`（广告点击）、`app_exit`（退出应用）、`package_added` / `package_removed`（安装或卸载应用）、`volume_changed`（音量变化）、`network_changed`（网络变化）、`boot_restore`（开机恢复）和 `media_changed`（媒体变化）。也可以使用业务自定义场景名，但触发方传入的名称必须与键完全一致（区分大小写）。

## `timer` 定时配置

`timer` 的每个键是要触发的场景名。调度器每天在指定的本地时间触发该场景；同一配置的多条通知按 5 秒间隔依次发送。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `HH` | 整数 | 小时，取值 `0`–`23`。 |
| `MM` | 整数 | 分钟，取值 `0`–`59`。 |
| `count` | 整数 | 每次定时触发的通知条数；小于等于 `0` 时不创建定时任务。 |
| `styles` | 整数数组 | 允许使用的通知样式编号，默认 `[1, 2, 3, 4]`。 |

例如，`"timer-a": {"HH": 6, "MM": 0, "count": 3}` 表示每天计划在 06:00:00、06:00:05、06:00:10 各发送一条通知。无效的时间值会被调度器忽略。定时通知通过系统闹钟调度，省电模式下可能延迟；发送时仍检查总开关、前台/屏幕状态及全局条数上限，但不经过事件批次的间隔和批次数限制。

## `contents` 通知内容

数组中的每个对象代表一组通知文案：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `Title` | 字符串 | 通知标题。 |
| `Content` | 字符串 | 通知正文。 |
| `Button` | 字符串 | 通知卡片上的按钮文字。 |
| `Scenes` | 字符串数组 | 该文案适用的场景名列表，应与 `triggers` 或 `timer` 中的键对应。 |
| `Route` | 字符串 | 用户点击通知后传递给宿主应用的路由字符串。SDK 会同时传递触发场景 `Scene`。 |
| `Languages` | 对象 | 按语言代码提供本地化文案。 |

`Languages` 的键使用语言代码（例如 `ja`、`ko`），值对象包含小写字段 `title`、`content`、`button`，分别保存该语言的标题、正文和按钮文字。缺少文案字段时解析为空字符串。

当前发送器固定读取 `contents` 的第一条，并使用其中的 `Title`、`Content` 和 `Button`。`Scenes`、`Languages` 和 `styles` 已支持解析与保存，但当前尚未用于筛选文案、切换语言或选择通知布局。启用通知时至少配置一条内容；`Route` 随通知点击传给宿主应用，由宿主导航层处理。

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
  },
  "contents": [
    {
      "Title": "Lost Photos Found!",
      "Content": "We found 23 deleted photos. Tap to restore them instantly.",
      "Button": "Check",
      "Scenes": ["screen_on_a", "morning"],
      "Route": "/recoverPhotos",
      "Languages": {
        "ja": {"title": "写真が見つかりました", "content": "復元できます。", "button": "確認"}
      }
    }
  ]
}
```

打包 JSON 中省略的顶层字段使用代码默认值，本地 `Preference` 中未保存的字段沿用打包资源的值。`triggers`、`timer` 和 `contents` 按集合整体读取；显式设置 `"triggers": {}`、`"timer": {}` 或 `"contents": []` 会清空对应集合。业务修改 `NotificationConfig` 后调用 `saveNotificationConfig()` 会保存配置并刷新定时任务，重新启动应用也会读取已保存的值。
