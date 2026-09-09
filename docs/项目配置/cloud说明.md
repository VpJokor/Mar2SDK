# `cloud.json` 配置说明

文件位于 `core/src/main/res/raw/cloud.json`，是 Google Cloud 发布的 IP 网段（CIDR）数据资源，不是业务策略配置。SDK 的 `IPUtil` 读取 `prefixes` 中的 IPv4/IPv6 网段，用于判断 IP 是否属于 Google Cloud 网络。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `syncToken` | 字符串 | Google 网段数据版本标识。 |
| `creationTime` | 字符串 | 数据生成时间（ISO 8601）。 |
| `prefixes` | 对象数组 | 网段列表；每项含 `ipv4Prefix` 或 `ipv6Prefix`，并可能含 `service`、`scope`。 |

`service` 和 `scope` 仅用于描述网段，当前判断逻辑只使用 CIDR 字段。该文件应整体替换为上游最新数据，避免手工修改单条网段。示例资源：[`cloud.json`](../../core/src/main/res/raw/cloud.json)。
