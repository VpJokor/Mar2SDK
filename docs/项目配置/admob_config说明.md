# `admob_config.json` 配置说明

文件位于 `core/src/main/res/raw/admob_config.json`，用于配置 AdMob 正式广告位、缓存有效期、缓存池大小和探价参数。初始化时先读取资源，再用本地 `Preference` 中已保存的广告配置覆盖；调用 `saveAdmobConfig()` 可保存运行时修改。TEST/DEBUG 模式会自动使用 SDK 内置测试广告位，PRE_RELEASE/RELEASE 模式使用配置中的 `id`。

## 字段

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | ---: | --- |
| `openConfig` | 对象 | — | 开屏广告配置。 |
| `interConfig` | 对象 | — | 插屏广告配置。 |
| `videoConfig` | 对象 | — | 激励视频广告配置。 |

三个广告配置对象使用相同的字段结构：

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | ---: | --- |
| `id` | 字符串 | 示例测试 ID | 正式广告位 ID。 |
| `timeout` | 整数（毫秒） | 开屏 `12600000`，其他 `3000000` | 广告缓存有效期。 |
| `poolSize` | 整数 | `1` | 广告缓存池容量。 |
| `probeConfig.mod` | 字符串 | `Reflect` | 探价模式。 |
| `probeConfig.timeout` | 整数（毫秒） | `3000` | 探价超时时间。 |
| `probeConfig.currency` | 字符串 | `USD` | 探价币种。 |
| `probeConfig.instances` | 数组 | 三个示例实例 | 探价实例列表，可使用空数组。 |
| `probeConfig.instances[].instanceId` | 字符串 | `instanceA` 等 | 探价实例 ID。 |
| `probeConfig.instances[].label` | 字符串 | `labelA` 等 | 探价实例标签。 |
| `probeConfig.instances[].ecpm` | 数字 | `200.00` 等 | 实例 eCPM，币种由 `currency` 指定。 |
| `probeConfig.instances[].param` | 字符串 | 空字符串 | 实例参数。 |

Kotlin 中通过 `AdmobConfig.openConfig`、`interConfig`、`videoConfig` 访问配置，修改时使用数据类的 `copy()` 后调用 `saveAdmobConfig()`。`openID`、`interID`、`videoID` 根据当前运行模式返回实际使用的广告位 ID。

Remote Config 可只提供需要更新的顶层广告配置对象；提供的对象必须包含完整字段，并整体替换对应配置。本地按 `openConfig`、`interConfig`、`videoConfig` 三个键保存 JSON，不再读取旧版平铺字段及其存储键。

示例资源：[`admob_config.json`](../../core/src/main/res/raw/admob_config.json)。广告位 ID 应替换为发行版后台创建的值；超时时间和池容量应使用非负数。
