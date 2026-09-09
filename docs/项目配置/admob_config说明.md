# `admob_config.json` 配置说明

文件位于 `core/src/main/res/raw/admob_config.json`，用于配置 AdMob 正式广告位、缓存有效期和缓存池大小。初始化时先读取资源，再用本地 `Preference` 中已保存的字段覆盖；调用 `saveAdmobConfig()` 可保存运行时修改。TEST/DEBUG 模式会自动使用 SDK 内置测试广告位，`release*` 仅在正式模式生效。

## 字段

| 字段 | 类型 | 资源默认值 | 说明 |
| --- | --- | ---: | --- |
| `releaseOpenID` | 字符串 | 示例测试 ID | 正式开屏广告位 ID。 |
| `releaseInterID` | 字符串 | 示例测试 ID | 正式插屏广告位 ID。 |
| `releaseVideoID` | 字符串 | 示例测试 ID | 正式激励视频广告位 ID。 |
| `openTimeout` | 整数（毫秒） | `12600000` | 开屏广告缓存有效期（约 3.5 小时）。 |
| `interTimeout` | 整数（毫秒） | `3000000` | 插屏广告缓存有效期（约 50 分钟）。 |
| `videoTimeout` | 整数（毫秒） | `3000000` | 激励视频广告缓存有效期（约 50 分钟）。 |
| `openPoolSize` | 整数 | `1` | 开屏广告缓存池容量。 |
| `interPoolSize` | 整数 | `1` | 插屏广告缓存池容量。 |
| `videoPoolSize` | 整数 | `1` | 激励视频广告缓存池容量。 |

示例资源：[`admob_config.json`](../../core/src/main/res/raw/admob_config.json)。广告位 ID 应替换为发行版后台创建的值；超时时间和池容量应使用非负数。
