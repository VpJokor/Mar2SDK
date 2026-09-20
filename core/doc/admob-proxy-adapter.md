# AdMob 探针价格区间

`AdmobProxyAdapter` 支持开屏、插屏、激励视频。三个加载入口都立即返回
`com.mar2sdk.admob.probe / 10001`，不请求广告、不展示、不触发奖励。

在每种格式的 AdMob 广告位对应瀑布流中配置自定义事件，完整类名为：

```text
com.mar2sdk.core.ad.impl.admob.probe.AdmobProxyAdapter
```

多个价格档位复用同一个类。将后台实际实例 ID、标签、Manual eCPM、Parameter
同步到对应 `openConfig` / `interConfig` / `videoConfig` 的 `probeConfig.instances`。
`instanceId` 匹配响应中的 `adSourceInstanceId`，`label` 匹配 `adSourceInstanceName`；
`ecpm` 是 USD 每千次展示的排序价格，必须与后台手动设置一致。Parameter 按原字符串
核对，未配置时使用空字符串；它不会替代或修改后台 Manual eCPM。

例如单个实例配置（ID 必须换成后台真实值）：

```json
{"instanceId":"<后台实例ID>","label":"Probe_30","ecpm":30.0,"param":""}
```

GMA 25.3.0 自带保留 `Adapter` 子类及公开构造器/方法的 consumer 规则。
适配器保留普通公开无参类即可，不要改成 Kotlin `object`。

## 区间含义

Reader 保留本次 `ResponseInfo.adapterResponses` 的原始顺序，唯一定位成功来源后：

- HPrice：成功来源之前，确认返回专用 no-fill 的探针价格最小值。
- LPrice：本次响应中实际存在于成功来源之后、错误为空且延迟为零的已登记探针价格最大值。
- 缺少某一侧时，该侧为 `null`。不会把配置中缺席于响应的档位补为 LPrice，也不会用零代表未知。

例如 `Probe_50(no-fill) → Probe_30(no-fill) → winner → Probe_20(未执行)`，
结果为 HPrice = `30_000_000`，LPrice = `20_000_000`，单位是 **USD eCPM 微单位**。
同价探针允许形成相等的上下界，不能把它们当成严格不等式。

配置价格必须为正数，精确支持最多六位小数，并能转换为 Long 微单位。
重复/未知实例、身份或 Parameter 不符、价格顺序升高、胜出项缺失、异常探针错误、
胜出项之后出现探针执行证据时，返回无价格的诊断结果。判断主动 no-fill 时检查错误域、
错误码以及最多八层 cause；零延迟或错误文本本身不构成执行证据。

上下界以后台与客户端配置一致、探针与胜出来源按同一价格口径降序排列为前提。
公开响应不能证明后台实际 Manual eCPM 没有被改动；结果约束的是排序估值，
不能当作精确报价或实际收益上报。正式使用前应分别确认三种广告位的实际响应
包含所配置的探针及错误标记。Google 公共测试广告位不会使用自己的聚合配置。

## 读取结果

`AdmobLoader` 在发起实际加载前冻结配置，成功后为每个广告对象保存结果。
开屏、插屏、激励对象均有以下扩展属性：

```kotlin
val high: Long? = ad.adapterHPrice
val low: Long? = ad.adapterLPrice
val result = ad.adapterProbeResult // 状态、responseId、配置快照、上下界探针 ID
```

三种格式均会保存 H/L，不受 `ProbeMod` 影响，便于观察。展示时按每个广告加载时保存的
`probeConfig` 快照选择比价模式及探针币种；运行时修改配置不影响已有广告。自行加载等未保存
配置快照的广告，使用所属格式的当前配置。

| `ProbeMod` | 比较价格（USD eCPM 微单位） |
| --- | --- |
| `REFLECT` | `reflectPrice.valueMicros * 1000`，币种独立取反射结果的 `currencyCode` |
| `ADAPTER_H` | HPrice |
| `ADAPTER_M` | 两侧均有效时取 `LPrice + (HPrice - LPrice) / 2`，整数向下取整 |
| `ADAPTER_L` | LPrice |

`ADAPTER_M` 在比价时计算，不新增 MPrice 扩展属性。所选模式缺少必需的价格、探针结果
异常、价格或区间无效、币种非 USD 时，比较价格为未知；不回退到反射价格或另一侧边界。
不同格式可以使用不同模式，统一单位后比较。单个缓存池选已知价格最高的广告，同价保留
最先缓存者；全部价格未知时选最先缓存者。组合格式的两个候选同价或任一价格未知时，
仍优先选择名称中的第一种格式。

探针读取同步完成，`probeConfig.timeout` 当前不产生额外等待。

写入 `adapterProbeResult` 会一起替换 H/L，设为 null 会清除整个探针快照。
为兼容现有接口，H/L 仍可单独写入；手动改价会清除原诊断结果，但保留加载时的配置快照，
后续比价仍按该快照选择模式及探针币种。
