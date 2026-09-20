package com.mar2sdk.core.ad.impl.admob

import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProbeResult
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import java.util.Collections
import java.util.WeakHashMap

// 扩展属性没有实例字段，使用弱引用关联快照，不延长广告实例的生命周期。
private val reflectPrices = Collections.synchronizedMap(WeakHashMap<Any, AdmobPrice>())
private val adapterResults = Collections.synchronizedMap(WeakHashMap<Any, AdmobAdapterProbeResult>())

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var AppOpenAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 探针推断的高价，单位为 USD eCPM 微单位。 */
val AppOpenAd.adapterHPrice: Long?
	get() = adapterResults[this]?.hPrice

/** 探针推断的低价，单位为 USD eCPM 微单位。 */
val AppOpenAd.adapterLPrice: Long?
	get() = adapterResults[this]?.lPrice

/** 本次加载的探针结果；写入时一并替换 H/L，赋 null 清除探针快照。 */
var AppOpenAd.adapterProbeResult: AdmobAdapterProbeResult?
	get() = adapterResults[this]
	set(value) = cachePrice(adapterResults, this, value)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var InterstitialAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 探针推断的高价，单位为 USD eCPM 微单位。 */
val InterstitialAd.adapterHPrice: Long?
	get() = adapterResults[this]?.hPrice

/** 探针推断的低价，单位为 USD eCPM 微单位。 */
val InterstitialAd.adapterLPrice: Long?
	get() = adapterResults[this]?.lPrice

/** 本次加载的探针结果；写入时一并替换 H/L，赋 null 清除探针快照。 */
var InterstitialAd.adapterProbeResult: AdmobAdapterProbeResult?
	get() = adapterResults[this]
	set(value) = cachePrice(adapterResults, this, value)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var RewardedAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 探针推断的高价，单位为 USD eCPM 微单位。 */
val RewardedAd.adapterHPrice: Long?
	get() = adapterResults[this]?.hPrice

/** 探针推断的低价，单位为 USD eCPM 微单位。 */
val RewardedAd.adapterLPrice: Long?
	get() = adapterResults[this]?.lPrice

/** 本次加载的探针结果；写入时一并替换 H/L，赋 null 清除探针快照。 */
var RewardedAd.adapterProbeResult: AdmobAdapterProbeResult?
	get() = adapterResults[this]
	set(value) = cachePrice(adapterResults, this, value)

/** 只使用加载时冻结的模式和币种；没有快照时价格未知。 */
internal fun comparisonPriceEcpmMicros(ad: Any): Long? {
	val result = adapterResults[ad] ?: return null
	return resolveComparisonPriceEcpmMicros(result.config, reflectPrices[ad], result.hPrice, result.lPrice)
}

private fun <T : Any> cachePrice(prices: MutableMap<Any, T>, ad: Any, price: T?) {
	if (price == null) prices.remove(ad) else prices[ad] = price
}
