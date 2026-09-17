# `common_config.json` 配置说明

文件位于 `core/src/main/res/raw/common_config.json`，保存 SDK 通用服务和风控参数。初始化先读取资源，再以 `Preference` 中保存的字段覆盖；运行时修改后调用 `saveCommonConfig()` 持久化。

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | ---: | --- |
| `highEcpm` | 小数 | `0.0` | 高 eCPM 阈值，用于风险判定中的首个广告收入比较。 |
| `isAutoLogin` | 布尔值 | `true` | 是否在 `Core.init` 时自动执行游客登录；关闭后仍可手动调用登录接口，运行时修改不会主动触发登录。 |
| `serverAppID` | 整数 | `20046` | 服务端产品 ID，对应 `CommonConfig.serverAppID: Int`，必须大于 0。 |
| `serverUrl` | 字符串 | `https://api.newminigame.online` | SDK 后端服务基地址。 |
| `ABTestName` | 字符串 | `Unknow` | AB 实验名称，按业务实验配置填写。 |
| `PlayIntegrityID` | 整数 | `0` | Google Cloud 项目号，用于 Play Integrity。 |
| `parseTokenPath` | 字符串 | `/parseToken` | Play Integrity Token 解析接口路径。 |
| `ipInfoPath` | 字符串 | `/getIpInfoV2` | IP 信息接口路径。 |

请求通常将路径拼接到 `serverUrl`。路径应以 `/` 开头，修改服务地址前请确认后端接口兼容。示例资源：[`common_config.json`](../../core/src/main/res/raw/common_config.json)。
