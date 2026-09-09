# `google.json` 配置说明

文件位于 `core/src/main/res/raw/google.json`，是 Google 公布的 IP 网段（CIDR）数据资源。SDK 的 `IPUtil` 读取 `prefixes` 中的 IPv4/IPv6 网段，用于 IP 归属判断；它不通过 `Preference` 覆盖，也不提供运行时保存接口。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `syncToken` | 字符串 | Google 网段数据版本标识。 |
| `creationTime` | 字符串 | 数据生成时间（ISO 8601）。 |
| `prefixes` | 对象数组 | 网段列表，每项含 `ipv4Prefix` 或 `ipv6Prefix`。 |

更新时请从 Google 官方来源获取完整文件并替换资源，保持合法 CIDR 格式。示例资源：[`google.json`](../../core/src/main/res/raw/google.json)。
