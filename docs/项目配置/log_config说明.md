# `log_config.json` 配置说明

文件位于 `core/src/main/res/raw/log_config.json`，用于按日志渠道筛选需要上报的事件。初始化先读取资源，再以本地 `Preference` 覆盖；调用 `saveLogConfig()` 保存运行时修改。

| 字段 | 类型 | 资源默认值 | 渠道 |
| --- | --- | --- | --- |
| `fbEvents` | 字符串数组 | `["*"]` | Facebook。 |
| `localEvents` | 字符串数组 | `["*"]` | 本地日志。 |
| `thEvents` | 字符串数组 | `["eventA", "eventB", "eventC"]` | ThinkingData。 |
| `netEvents` | 字符串数组 | `["eventA", "eventB", "eventC"]` | 网络日志。 |

数组中的 `"*"` 表示启用该渠道的全部事件；填写事件名列表时仅启用列表中的事件。空数组表示不启用任何事件。示例资源：[`log_config.json`](../../core/src/main/res/raw/log_config.json)。
