# AdMob 展示前价格读取

移植来源：`C:\Users\admin\AdvertiseExample` 的 `v_2.1.x_通知，比价，探价` 分支，commit `a3ae8e8f08d873f3e14bb5802df2215698ee1d6e` 中的 `AdMobVerifiedPriceExtractors.kt`、`AdMobPreShowPriceProbe.kt` 和 `MediationPriceUnits.kt`。

## 使用

`AdmobLoader` 在开屏/插屏/激励视频的 `onAdLoaded` 中读取一次价格，并按广告实例保存快照。可通过广告池中的实例查询：

```kotlin
val ad = AdmobLoader.openPool.keys.firstOrNull()
val price = ad?.let { AdmobLoader.getLoadedPrice(it) }
val ecpm: Double? = price?.ecpm // 美元 / 千次展示
```

若广告由调用方自行加载，可在 `onAdLoaded` 中使用 `AdmobPriceProbe.read(ad)`。应在主线程、广告展示前读取。返回 `null` 表示没有有效价格，不能解释为零价。快照使用弱引用关联广告，不延长广告实例的生命周期。

价格字段：

| 字段 | 含义 |
| --- | --- |
| `valueMicros` | 单次展示金额，百万分之一美元 |
| `revenue` | 单次展示金额，美元 |
| `ecpmMicros` | 千次展示金额，百万分之一美元，等于 `valueMicros * 1000` |
| `ecpm` | 千次展示金额，美元，等于 `valueMicros / 1000.0` |
| `currencyCode` | 当前只接受 `USD` |
| `precisionType` | SDK 原始精度值，接受 ESTIMATED / PUBLISHER_PROVIDED / PRECISE |

例如 `valueMicros = 12345` 时，`revenue = 0.012345`、`ecpmMicros = 12345000`、`ecpm = 12.345`。

成功读取时，`ad_finish_loading` 事件增加 `ad_price_micros`、`ad_price_currency`、`ad_price_precision`、`ad_ecpm`。未读取到价格时省略这些字段。

## 支持范围

仅支持 **Google Mobile Ads 25.3.0** 的以下本地对象路径，逐节点校验运行时类名、字段声明类、字段名和字段类型：

- 开屏：`zzbgm.zzb → zzcvn.zza → zzcvm` 的父类 `zzcya.zzb → zzfkn.zzae`（本项目新增，见[开屏分析](admob-app-open-analysis.md)）。
- 插屏：`zzbss.zzc → zzets.zzj → zzdmh` 的父类 `zzcya.zzb → zzfkn.zzae`。
- 激励视频：`zzccy.zzb → zzfke.zzi → zzdvu` 的父类 `zzcya.zzb → zzfkn.zzae`。

开屏额外支持同进程 **AdsDynamite 260480602**：`zzbgm.zzb → zzbgo` 父类 `zzbel.zza → appopen.client.c.a → nonagon.ad.common.b.l → nonagon.transaction.a.ae`。末端事件为 `ads.internal.client.q`，字段 `a/b/c/d` 与本地 `zzt` 的 `zza/zzb/zzc/zzd` 含义相同。读取前校验 Binder 所属 ClassLoader 的模块 ID 和版本；其他动态模块版本返回 `null`。

最终读取本地 `zzt` 或已支持动态模块的 `q` 事件，要求事件类型为 3、精度有效、币种为 USD、金额为正数且转换为 eCPM micros 不溢出。版本不符、跨进程 Binder、字段变化、空值、非法数据或反射异常均返回 `null`，广告仍正常进入加载流程。

源项目将这两条路径标记为 `SHADOW_ONLY`；此移植保留其观察用途，未接入竞价排序或用户价值判断，官方 `OnPaidEventListener` 仍负责实际收入上报。真实广告能否提供该字段取决于运行时 SDK 实现及返回的数据；结构验证和单测不代表真实广告一定能读到价格。

`core/consumer-rules.keep` 包含精确的内部类/字段保留规则，通过 `consumerProguardFiles` 随 AAR 发布。升级广告 SDK 时，需要重新反编译确认路径、更新版本限制及规则，并验证真实广告加载；不能仅修改版本号。
