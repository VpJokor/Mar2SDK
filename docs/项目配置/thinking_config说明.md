# `thinking_config.json` 配置说明

文件位于 `core/src/main/res/raw/thinking_config.json`，用于配置 ThinkingData 日志上报。初始化先读取资源，再以本地 `Preference` 覆盖；调用 `saveThinkingConfig()` 保存运行时修改。

| 字段 | 类型 | 资源示例值 | 说明 |
| --- | --- | --- | --- |
| `key` | 字符串 | 资源中的示例值 | ThinkingData 项目标识。 |
| `url` | 字符串 | `https://mar2.top` | ThinkingData 上报地址。 |
| `logEndTime` | 整数（小时） | `48` | 用户首次打开应用后允许持续上报日志的时长；小于等于 `0` 时停止该上报窗口。 |

请根据 ThinkingData 控制台填写 `key` 和 `url`。示例资源：[`thinking_config.json`](../../core/src/main/res/raw/thinking_config.json)。
