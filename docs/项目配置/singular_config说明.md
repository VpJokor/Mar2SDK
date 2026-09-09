# `singular_config.json` 配置说明

文件位于 `core/src/main/res/raw/singular_config.json`，用于配置 Singular 归因 SDK。初始化先读取资源，再使用本地 `Preference` 覆盖；调用 `saveSingularConfig()` 保存运行时修改。

| 字段 | 类型 | 资源示例值 | 说明 |
| --- | --- | --- | --- |
| `key` | 字符串 | 资源中的示例值 | Singular SDK 的 API Key。 |
| `secret` | 字符串 | 资源中的示例值 | Singular SDK 的 Secret。 |
| `trackRevenue` | 布尔值 | `true` | 是否向 Singular 上报收入事件；设为 `false` 时停用收入上报。 |

正式发布前应填写发行环境的凭据，并避免将不同应用的 Key 混用。示例资源：[`singular_config.json`](../../core/src/main/res/raw/singular_config.json)。
