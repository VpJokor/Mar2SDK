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

### 通知事件触发点位

下表列出当前 SDK 会写入 `triggers` 的全部事件键。事件发生后，SDK 查找同名配置并按上表的延迟、数量和间隔规则排队；未配置或 `count <= 0` 时不会发送。

| 触发键 | 触发条件 | 备注 |
| --- | --- | --- |
| `unlock_home_launcher` | 检测到设备解锁，或用户按下 HOME 键/最近任务键。 | 解锁判断来自屏幕状态广播；HOME 与最近任务键会共用此场景。 |
| `screen_on_a` | 屏幕变为点亮（交互）状态时。 | 与 `screen_on_b`、`screen_on_c` 在同一次亮屏事件中一起入队；当前资源配置 `delay=5` 秒。 |
| `screen_on_b` | 屏幕变为点亮（交互）状态时。 | 与 A/C 同时入队；当前资源配置 `delay=10` 秒。 |
| `screen_on_c` | 屏幕变为点亮（交互）状态时。 | 与 A/B 同时入队；当前资源配置 `delay=65` 秒。 |
| `screen_off_locked` | 屏幕熄灭且设备处于锁定状态时。 | 仅在 `isScreenOn=false` 且 `isLocked=true` 时触发。 |
| `power_connected` | 电池状态变为充电，或 USB 设备连接时。 | 仅处理连接/开始充电；断开电源或 USB 不触发此键。 |
| `return_to_home` | 应用从前台变为后台（没有已启动的 Activity）时。 | 覆盖返回桌面等导致应用退到后台的情况；事件名沿用历史命名。 |
| `ad_click` | SDK 记录一次广告点击事件时。 | 由 `LogUtil` 将广告点击打点映射到此场景。 |
| `app_exit` | 应用任务从最近任务列表移除时。 | 常驻 `CommonService` 的 `onTaskRemoved` 写入该事件；不等同于进程崩溃。 |
| `package_added` | 系统收到应用安装或应用更新完成事件时。 | 包括新安装和替换更新；替换广播不会重复按安装/卸载处理。 |
| `package_removed` | 系统收到应用卸载完成事件时。 | 应用更新过程中的临时移除会被过滤，不触发此键。 |
| `volume_changed` | 监听到受支持音频流的音量值发生变化时。 | 监听闹钟、音乐、通知、铃声、系统和通话音量。 |
| `network_changed` | 默认网络状态发生变化，或 Wi-Fi 连接状态发生变化时。 | 覆盖网络连接/断开、能力变化和传输类型变化；Wi-Fi 事件仅在已连接且已验证时触发。 |
| `boot_restore` | 系统发送开机完成（`BOOT_COMPLETED`）广播时。 | 同时启动常驻服务，并恢复通知调度。 |
| `media_changed` | 图片、视频、音频或下载媒体库内容发生变化时。 | 由 MediaStore 内容观察器触发，可能因新增、修改或删除而触发。 |

`test` 是仅供测试使用的保留场景，不在默认资源中触发。`triggers` 也可以增加自定义键，但必须由宿主或业务代码使用同名场景显式入队。

## `timer` 定时策略

每个键是场景名，调度器每天在指定本地时间触发一次：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `HH` | 整数 | 小时，范围 `0`–`23`。 |
| `MM` | 整数 | 分钟，范围 `0`–`59`。 |
| `count` | 整数 | 每次定时发送的通知条数；小于等于 `0` 时不创建任务。 |
| `styles` | 整数数组 | 允许使用的通知样式编号，默认 `[1,2,3,4]`。 |

### 定时通知触发点位

定时器按设备当前本地时区每天触发。`count` 会为同一时间点创建多条定时通知，每条之间固定间隔 5 秒；时间无效或 `count <= 0` 时不创建任务。

| 定时键 | 每日触发时间（本地时间） | 触发条件与备注 |
| --- | ---: | --- |
| `timer-a` | `06:00` | 每天 06:00 触发；当前资源配置 `count=3`，按 5 秒间隔尝试发送 3 条。 |
| `timer-b` | `09:00` | 每天 09:00 触发；当前资源配置 `count=3`，按 5 秒间隔尝试发送 3 条。 |
| `timer-c` | `14:00` | 每天 14:00 触发；当前资源配置 `count=3`，按 5 秒间隔尝试发送 3 条。 |
| `timer-d` | `19:25` | 每天 19:25 触发；当前资源配置 `count=3`，按 5 秒间隔尝试发送 3 条。 |

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
