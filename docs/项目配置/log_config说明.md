# `log_config.json` 配置说明

文件位于 `core/src/main/res/raw/log_config.json`，用于按日志渠道筛选需要上报的事件。初始化先读取资源，再以本地 `Preference` 覆盖；调用 `saveLogConfig()` 保存运行时修改。

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | --- | --- |
| `fbEvents` | 字符串数组 | `["*"]` | Firebase Analytics。 |
| `localEvents` | 字符串数组 | `["*"]` | 本地日志。 |
| `thEvents` | 字符串数组 | `["*"]` | ThinkingData。 |
| `netEvents` | 字符串数组 | `["ad_revenue", "ad_impression", "ad_click"]` | 自有服务端事件上报，支持任意非空白事件名。 |
| `reportBatchSize` | 正整数 | `20` | 每批最多事件条数，累计达到该条数立即发送。 |
| `reportFlushIntervalMillis` | 正整数 | `5000` | 批量等待时间，单位毫秒，从本轮首条事件开始计时。 |

数组中的 `"*"` 表示启用该渠道的全部事件；填写事件名列表时仅启用列表中的事件。空数组表示不启用任何事件。示例资源：[`log_config.json`](../../core/src/main/res/raw/log_config.json)。

`netEvents` 支持任意非空白事件名。默认仅启用广告收入、展示和点击；在 `log_config.json` 中设为 `"netEvents": ["*"]` 可启用通过 `LogUtil.log` 记录的全部事件，也可使用 `"netEvents": ["app_start", "level_complete", "ad_revenue"]` 只启用指定事件。例如调用 `LogUtil.log("level_complete", mapOf("level" to 3))` 后，该事件会按配置进入批量上报队列。

仅 `ad_revenue` 要求 `value` 为有限数值、`currency` 为三个 ASCII 字母的币种代码；其他事件不要求这两个属性，也不对同名属性应用收入校验。所有事件仍需满足登录账号、包名、事件标识和完整事件格式的校验。

网络事件采集从登录成功或恢复有效登录缓存后开始。需配置 `CommonConfig.serverAppID`、`serverClientKey` 和 `serverUrl`；默认随 `Core.init()` 自动登录，关闭 `isAutoLogin` 时需主动调用 `NetUtil.login()`。登录前的事件不会生成匿名账号或补记到后来的账号。

现有广告回调通过 `LogUtil.log` 按配置自动进入 `logNet`，无需再次手动发送同一事件。采集时固定账号、时间、事件 ID、数数预置属性和事件参数，并在后台保存到独立 SQLite 队列。达到 `reportBatchSize` 条或等待 `reportFlushIntervalMillis` 毫秒后触发批量发送，默认 20 条或 5 秒；单条事件最多 64 KiB，每批事件 JSON 最多 256 KiB，实际批次也受此大小限制。

两个批量参数必须在打包资源 `log_config.json` 中提供，并设为正整数；同时支持本地保存和配置更新。条数在后续入队判断和取批次时读取最新值；等待时间从下一次批处理窗口生效，已开始的计时不重置。运行时下发的零、负数和非法数值不会覆盖当前有效配置。例如可在 `log_config.json` 中设置 `"reportBatchSize": 10`、`"reportFlushIntervalMillis": 2000`，改为累计 10 条或等待 2 秒发送。

发送使用 `/report/data/report`，同一批只包含同一产品和账号的事件。只有响应 `code == 0` 才删除该批记录；每轮最多尝试 3 次，失败间隔为 1 秒、2 秒。连续失败后冷却 60 秒，保留原事件，由后续事件、登录或网络恢复再次触发。重试保留原账号、时间、`#uuid` 和 `#event_id`；进程重启后会在恢复对应登录身份时继续处理待发记录。
