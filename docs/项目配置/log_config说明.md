# `log_config.json` 配置说明

文件位于 `core/src/main/res/raw/log_config.json`，用于按日志渠道筛选需要上报的事件。初始化先读取资源，再以本地 `Preference` 覆盖；调用 `saveLogConfig()` 保存运行时修改。

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | --- | --- |
| `fbEvents` | 字符串数组 | `["*"]` | Firebase Analytics。 |
| `localEvents` | 字符串数组 | `["*"]` | 本地日志。 |
| `thEvents` | 字符串数组 | `["*"]` | ThinkingData。 |
| `netEvents` | 字符串数组 | `["*"]` | 自有服务端事件上报，支持任意非空白事件名。 |
| `reportBatchSize` | 正整数 | `1` | 每批最多事件条数，累计达到该条数立即发送。 |
| `reportFlushIntervalMillis` | 正整数 | `5000` | 批量等待时间，单位毫秒，从本轮首条事件开始计时。 |

数组中的 `"*"` 表示启用该渠道的全部事件；填写事件名列表时仅启用列表中的事件。空数组表示不启用任何事件。示例资源：[`log_config.json`](../../core/src/main/res/raw/log_config.json)。

`BaseScreen` 和 `ContentActivity` 自动记录页面打开 `screen_open`、关闭 `screen_close` 及通过导航包装器执行的跳转 `screen_navigate`，参数包含页面或来源/目标路由、触发原因及容器类型（`compose` / `activity`）。`ContentActivity` 的系统返回也记录跳转事件；Compose 直接调用 `popBackStack()` 或系统返回仅触发生命周期事件。打包资源中的 `thEvents: ["*"]` 已包含这些事件；使用事件白名单时需显式加入三个页面事件，Remote Config 示例已同步配置。

`netEvents` 支持任意非空白事件名。当前打包资源为 `"netEvents": ["*"]`，启用通过 `LogUtil.log` 记录的全部事件；也可使用 `"netEvents": ["app_start", "level_complete", "ad_revenue"]` 只启用指定事件。例如调用 `LogUtil.log("level_complete", mapOf("level" to 3))` 后，该事件会按配置进入批量上报队列。

## 广告加载结果

AdMob 开屏、插屏、激励视频、原生和 Banner 使用以下事件，预加载也包含在内：

| 事件 | 含义 |
| --- | --- |
| `ad_start_loading` | 开始一次广告请求。Banner 自动刷新由 SDK 发起，没有对应的开始回调。 |
| `ad_finish_loading` | 单次 SDK 请求加载成功，沿用已有事件名；不表示广告已展示。 |
| `ad_load_fail` | 单次加载失败，包含失败原因。 |

加载事件包含 `request_id`、`areakey`、`format`、`ad_platform`、`ad_unit_name`、`ad_preload` 和入口/路由信息。开屏、插屏和激励视频成功事件继续携带可用的价格字段。失败事件额外包含：

| 字段 | 含义 |
| --- | --- |
| `failure_reason` | `SDK_ERROR`：SDK 加载失败回调；`LOAD_EXCEPTION`：发起请求抛出异常；`LOAD_TIMEOUT`：原生加载预算耗尽；`INVALID_CONFIG`：原生无有效广告位。 |
| `error_message` | SDK 错误信息、异常信息或具体失败说明。 |
| `error_code` / `error_domain` | SDK 原始错误码和错误域，仅 `SDK_ERROR` 时提供。 |
| `error_type` | 异常完整类名，仅 `LOAD_EXCEPTION` 时提供。 |

例如无广告填充时，`ad_load_fail` 会携带 `failure_reason: "SDK_ERROR"`、`error_code: 3`、`error_domain: "com.google.android.gms.ads"` 和 SDK 返回的 `error_message`。

原生广告按高、中、低档位分别记录加载结果，即使后一档成功，前一档的失败也会保留。Banner 每次自动刷新的成功/失败回调分别记录结果。复用正在进行的加载或命中广告池不新增请求事件。全屏广告等待展示超时仍记录 `ad_show_timeout`；实际 SDK 请求继续执行，后续回调再记录真实加载结果。配置切换后旧请求的回调使用旧广告位信息记录，不归因到新广告位。

调用方主动取消原生广告请求后，后续 SDK 回调会被忽略，不补记成功或失败；原生加载超时则记录一次 `LOAD_TIMEOUT`，后续迟到回调不重复上报。

这些事件沿用 `LogConfig` 的各渠道筛选规则。若使用事件白名单，需加入 `ad_start_loading`、`ad_finish_loading` 和 `ad_load_fail`；Remote Config 示例已包含这些事件。自有服务端仍只采集登录成功后的事件。

仅 `ad_revenue` 要求 `value` 为有限数值、`currency` 为三个 ASCII 字母的币种代码；其他事件不要求这两个属性，也不对同名属性应用收入校验。所有事件仍需满足登录账号、包名、事件标识和完整事件格式的校验。

网络事件采集从登录成功或恢复有效登录缓存后开始。需配置 `CommonConfig.serverAppID`、`serverClientKey` 和 `serverUrl`；默认随 `Core.init()` 自动登录，关闭 `isAutoLogin` 时需主动调用 `NetUtil.login()`。登录前的事件不会生成匿名账号或补记到后来的账号。

现有广告回调通过 `LogUtil.log` 按配置自动进入 `logNet`，无需再次手动发送同一事件。采集时固定账号、时间、事件 ID、数数预置属性和事件参数，并在后台保存到独立 SQLite 队列。达到 `reportBatchSize` 条或等待 `reportFlushIntervalMillis` 毫秒后触发批量发送；当前打包资源配置为 1 条或 5 秒，通常每条入队即触发发送。单条事件最多 64 KiB，每批事件 JSON 最多 256 KiB，实际批次也受此大小限制。

两个批量参数必须在打包资源 `log_config.json` 中提供，并设为正整数；同时支持本地保存和配置更新。条数在后续入队判断和取批次时读取最新值；等待时间从下一次批处理窗口生效，已开始的计时不重置。运行时下发的零、负数和非法数值不会覆盖当前有效配置。例如可在 `log_config.json` 中设置 `"reportBatchSize": 10`、`"reportFlushIntervalMillis": 2000`，改为累计 10 条或等待 2 秒发送。

发送使用 `/report/data/report`，同一批只包含同一产品和账号的事件。只有响应 `code == 0` 才删除该批记录；每轮最多尝试 3 次，失败间隔为 1 秒、2 秒。连续失败后冷却 60 秒，保留原事件，由后续事件、登录或网络恢复再次触发。重试保留原账号、时间、`#uuid` 和 `#event_id`；进程重启后会在恢复对应登录身份时继续处理待发记录。

## 广告展示失败

AdMob 开屏、插屏、激励视频及组合比价广告收到 SDK 展示失败回调时，`ad_show_fail` 除广告上下文、耗时和广告来源外，还携带：

| 字段 | 含义 |
| --- | --- |
| `failure_reason` | `SDK_ERROR`。 |
| `error_code` | SDK 原始错误码，整数。 |
| `error_domain` | SDK 原始错误域。 |
| `error_message` | SDK 原始错误信息；为空白时使用包含错误码的说明。 |

原生广告最终失败的 `ad_show_fail` 使用最后一档的错误信息：SDK 加载失败携带上述字段；请求或广告监听器设置异常携带 `failure_reason: "LOAD_EXCEPTION"`、`error_type` 和 `error_message`；无有效广告位和 Activity 不可用分别记录 `INVALID_CONFIG` 和 `ACTIVITY_IS_FINISHING` 及具体说明。没有 SDK 错误码时不填写 `error_code`、`error_domain`。原生加载超时仍记录 `ad_show_timeout`，附带 `LOAD_TIMEOUT` 和超时信息，不沿用前一档的错误码。

`ad_show_fail` 沿用各渠道白名单配置；需要接收该事件的渠道应包含 `ad_show_fail` 或 `"*"`。
