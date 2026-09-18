# AdMob 25.3.0 开屏展示前价格分析

## 结论

开屏广告存在通往加载响应中价格事件的对象链，可复用插屏和激励视频的价格校验与换算逻辑。`AdmobReflectProbe` 已接入 AAR 的本地实现，以及设备实际加载的 AdsDynamite 260480602 实现；`AdmobLoader` 原有的统一加载成功入口会自动保存开屏价格快照。

分析对象是 Gradle 实际依赖的 `com.google.android.gms:play-services-ads:25.3.0` 和 `play-services-ads-api:25.3.0` 的 `classes.jar`，使用 `javap -p -c` 检查字节码。不是 Google 发布的原始源码。提取后文件的 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| play-services-ads classes.jar | `2a45ad35d93b74b60e2749fc1ee56db2cba6d35708c5e2c7090d8756767e7973` |
| play-services-ads-api classes.jar | `d23aa3311867d425b7fd8015fd9ad7121f48259b78b595b8d15bbd0b94d88a1e` |

## 加载后对象关系

1. `AppOpenAd.load()` 创建 `zzbgy` 加载器。加载器注册 `zzbgl` 作为回调。
2. 内部加载成功时，`zzfgr.zzb()` 用已加载的 `zzcvm` 构造 `zzcvn`，通过 `zzfgm.zzt()` 发送加载成功事件。
3. `zzbgl.zzb(zzbgq)` 把这个对象包装为 `zzbgm`，调用应用的 `onAdLoaded()`；`zzbgm` 是 `AppOpenAd` 的实现。
4. `zzcvn.zza` 持有 `zzcvm`。`zzcvm` 继承 `zzcya`，父类构造时已将 `zzfkn` 响应保存到 `zzb`。

除最终事件对象外，以下短类名均位于 `com.google.android.gms.internal.ads`：

| 当前运行时类 | 字段声明类 | 字段 | 声明类型 | 下一级运行时类 |
| --- | --- | --- | --- | --- |
| `zzbgm` | `zzbgm` | `zzb` | `zzbgq` | `zzcvn` |
| `zzcvn` | `zzcvn` | `zza` | `zzcvm` | `zzcvm` |
| `zzcvm` | `zzcya` | `zzb` | `zzfkn` | `zzfkn` |
| `zzfkn` | `zzfkn` | `zzae` | `com.google.android.gms.ads.internal.client.zzt` | 同声明类型 |

第三步必须向父类查找字段。开屏的 Binder 声明类型是 `zzbgq`，与插屏的 `zzbu` 不同；不能直接套用插屏的前两个节点。

## AdsDynamite 对象关系

在 Pixel 8 / Android 16 上加载官方测试广告时，实际首段为 `zzbgm.zzb → zzbgo`。检查代理的 Binder 后确认它继承 `android.os.Binder`，是由独立 `DelegateLastClassLoader` 加载的同进程对象，而非 `android.os.BinderProxy`。仅看到 `zzbgo` 不能判定广告运行在另一个进程。

从设备提取 `split_AdsDynamite_installtime.apk` 并用 `apkanalyzer dex code --class ...` 分析，得到以下路径（所有类名以 `com.google.android.gms.` 开头，`IBinder` 除外）：

| 当前运行时类 | 字段声明类 | 字段 | 声明类型 |
| --- | --- | --- | --- |
| `internal.ads.zzbgm` | 同运行时类 | `zzb` | `internal.ads.zzbgq` |
| `internal.ads.zzbgo` | `internal.ads.zzbel` | `zza` | `android.os.IBinder` |
| `ads.internal.appopen.client.c` | 同运行时类 | `a` | `ads.nonagon.ad.appopen.interstitial.i` |
| `ads.nonagon.ad.appopen.interstitial.i` | `ads.nonagon.ad.common.b` | `l` | `ads.nonagon.transaction.a` |
| `ads.nonagon.transaction.a` | 同运行时类 | `ae` | `ads.internal.client.q` |

`q` 的 `a/b/c/d` 分别对应事件类型、精度、币种、金额。模块中 `transaction.a` 同样在构造时解析 `ad_event_value`；曝光处理 `nonagon.ad.event.bj.i()` 明确包含开屏格式 6，向官方 paid 回调传递同一事件。

模块描述类 `com.google.android.gms.dynamite.descriptors.com.google.android.gms.ads.dynamite.ModuleDescriptor` 的 `MODULE_ID` 为 `com.google.android.gms.ads.dynamite`，`MODULE_VERSION` 为 **260480602**。实现要求该描述类与 Binder 使用同一个 ClassLoader，且 ID/版本精确匹配。源码标记中的 `260480608` 并不是此版本字段值。

分析的模块 APK SHA-256：`c0c63ede4285146544766b4a60154f37ef30ccf548461f65317cbacf743ce29b`。生产读取只访问已确认的字段和模块描述常量，不发送 Binder transaction；跨进程代理或未知动态版本返回 `null`。

## 价格来源与回调时机

`zzfkn(JsonReader)` 在 `ad_event_value` 分支调用 `zzt.zza(JSONObject)`，构造完成时就将结果保存在 `final zzae`。其字段映射为：

| 响应键 | zzt 字段 | 类型 | 含义 |
| --- | --- | --- | --- |
| `type_num` | `zza` | int | 事件类型，收益事件为 3 |
| `precision_num` | `zzb` | int | 金额精度 |
| `currency` | `zzc` | String | 币种 |
| `value` | `zzd` | long | 单次展示金额 micros |

曝光入口 `zzdfo.zzdr()` 明确处理格式 6（APP_OPEN_AD），与插屏/激励共用内部方法 `zzb()`。该方法在 `gads:paid_event_listener:enabled` 开启、`zzae` 存在、事件类型为 3 且尚未发送时，通知收益监听器。`zzfo.zze()` 将 `zzb/zzc/zzd` 原样传给 `AdValue.zza()`，再调用 `OnPaidEventListener.onPaidEvent()`。

因此价格数据可能在加载成功时已经存在，曝光触发的是回调。读取这些字段不会触发展示或消费 paid 回调。响应也可能缺少该对象，或者值为零/未知精度，此时不会生成有效价格快照。

## 接入行为与验证范围

调用方式见[价格读取说明](admob-price.md)。开屏沿用精确版本和字段签名校验，并要求事件类型 3、精度 1/2/3、USD、正数金额及换算不溢出。收入上报仍使用官方回调。

设备结构测试覆盖三种 AAR 广告路径、真实 SDK 事件对象的解码，以及真实 `zzfkn(JsonReader)` 对带价格/缺少价格的开屏响应解析。另有通过 `admobAppOpenSmoke=true` 参数显式启用的官方测试广告加载检查；它不展示广告，只记录实际字段链、模块版本和读取结果。运行时是否命中有效价格须根据该检查的实际记录判断，不能仅由字段结构通过推断。

2026-09-16 在 Pixel 8 / Android 16 的验证结果：21 个 JVM 单元测试、4 个设备测试、release AAR 构建通过；AAR 内已包含新增的本地路径及代理字段保留规则。官方测试开屏广告加载成功，完整走通上述 Dynamite 链，描述类与 Binder 的 ClassLoader 相同，模块版本为 260480602。实际读到 `eventType=3, precision=0, currency=USD, valueMicros=0`，所以按现有规则 `probe=null`。这验证了实际广告对象的字段可达性，尚未验证正式广告的非零价格或与展示后 paid 回调的一致性。

复现设备检查（需要连接设备及网络）：

```powershell
.\gradlew.bat :core:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.mar2sdk.core.ad.impl.admob.AdmobReflectProbeInstrumentedTest,com.mar2sdk.core.ad.impl.admob.AdmobAppOpenPriceSmokeTest' `
  '-Pandroid.testInstrumentationRunnerArguments.admobAppOpenSmoke=true'
adb logcat -d -s AdmobAppOpenSmoke:I '*:S'
```

本次字节码证据保存在 `core/build/admob-analysis`，可通过上述依赖和 `javap` 命令重新生成。升级 SDK 后应重新确认字段链与金额语义。
