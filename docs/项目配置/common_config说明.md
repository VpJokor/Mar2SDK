# `common_config.json` 配置说明

文件位于 `core/src/main/res/raw/common_config.json`，保存 SDK 通用服务和风控参数。初始化先读取资源，再以 `Preference` 中保存的字段覆盖；运行时修改后调用 `saveCommonConfig()` 持久化。

`package` 用于校验宿主 APP 的实际包名（`applicationId`）。每次读取打包资源时，在应用其他通用配置前进行区分大小写的精确匹配；不一致、缺失、空值或类型错误都会立即终止当前 APP 进程，所有运行模式均生效。接入其他 APP 时必须在其 `res/raw/common_config.json` 中填写对应包名。该字段不保存到 `Preference`；远程配置包含 `package` 时也会先校验，未包含时允许正常更新其他字段。

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | ---: | --- |
| `package` | 字符串 | `com.mar2sdk` | 必填，必须与宿主 APP 的实际包名完全一致，否则立即终止 APP 进程。 |
| `highEcpm` | 小数 | `0.0` | 高 eCPM 阈值，用于风险判定中的首个广告收入比较。 |
| `isAutoLogin` | 布尔值 | `true` | 是否在 `Core.init` 时调用统一登录入口；本地凭据在预留 60 秒后的有效期内使用 Token 登录，否则执行游客登录。关闭后仍可手动调用登录接口，运行时修改不会主动触发登录。 |
| `serverAppID` | 整数 | `20046` | 服务端产品 ID，对应 `CommonConfig.serverAppID: Int`，必须大于 0。 |
| `serverUrl` | 字符串 | `https://api.newminigame.online` | SDK 后端服务基地址。 |
| `ABTestName` | 字符串 | `Unknow` | AB 实验名称，按业务实验配置填写。 |
| `PlayIntegrityID` | 整数 | `0` | Google Cloud 项目号，用于 Play Integrity。 |
| `parseTokenPath` | 字符串 | `/parseToken` | Play Integrity Token 解析接口路径。 |
| `ipInfoPath` | 字符串 | `/getIpInfoV2` | IP 信息接口路径。 |

请求通常将路径拼接到 `serverUrl`。路径应以 `/` 开头，修改服务地址前请确认后端接口兼容。示例资源：[`common_config.json`](../../core/src/main/res/raw/common_config.json)。
