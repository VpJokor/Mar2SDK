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
private val adapterPrices = Collections.synchronizedMap(WeakHashMap<Any, AdapterPriceSnapshot>())

private data class AdapterPriceSnapshot(
	val hPrice: Long? = null,
	val lPrice: Long? = null,
	val result: AdmobAdapterProbeResult? = null,
)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var AppOpenAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 探针推断的高价，单位为 USD eCPM 微单位；手动赋值会清除原探针诊断结果。 */
var AppOpenAd.adapterHPrice: Long?
	get() = adapterPrices[this]?.hPrice
	set(value) = updateAdapterPrice(this) { copy(hPrice = value, result = null) }

/** 探针推断的低价，单位为 USD eCPM 微单位；手动赋值会清除原探针诊断结果。 */
var AppOpenAd.adapterLPrice: Long?
	get() = adapterPrices[this]?.lPrice
	set(value) = updateAdapterPrice(this) { copy(lPrice = value, result = null) }

/** 本次加载的探针结果；写入时一并替换 H/L，赋 null 清除探针快照。 */
var AppOpenAd.adapterProbeResult: AdmobAdapterProbeResult?
	get() = adapterPrices[this]?.result
	set(value) = cacheAdapterResult(this, value)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var InterstitialAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 探针推断的高价，单位为 USD eCPM 微单位；手动赋值会清除原探针诊断结果。 */
var InterstitialAd.adapterHPrice: Long?
	get() = adapterPrices[this]?.hPrice
	set(value) = updateAdapterPrice(this) { copy(hPrice = value, result = null) }

/** 探针推断的低价，单位为 USD eCPM 微单位；手动赋值会清除原探针诊断结果。 */
var InterstitialAd.adapterLPrice: Long?
	get() = adapterPrices[this]?.lPrice
	set(value) = updateAdapterPrice(this) { copy(lPrice = value, result = null) }

/** 本次加载的探针结果；写入时一并替换 H/L，赋 null 清除探针快照。 */
var InterstitialAd.adapterProbeResult: AdmobAdapterProbeResult?
	get() = adapterPrices[this]?.result
	set(value) = cacheAdapterResult(this, value)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var RewardedAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 探针推断的高价，单位为 USD eCPM 微单位；手动赋值会清除原探针诊断结果。 */
var RewardedAd.adapterHPrice: Long?
	get() = adapterPrices[this]?.hPrice
	set(value) = updateAdapterPrice(this) { copy(hPrice = value, result = null) }

/** 探针推断的低价，单位为 USD eCPM 微单位；手动赋值会清除原探针诊断结果。 */
var RewardedAd.adapterLPrice: Long?
	get() = adapterPrices[this]?.lPrice
	set(value) = updateAdapterPrice(this) { copy(lPrice = value, result = null) }

/** 本次加载的探针结果；写入时一并替换 H/L，赋 null 清除探针快照。 */
var RewardedAd.adapterProbeResult: AdmobAdapterProbeResult?
	get() = adapterPrices[this]?.result
	set(value) = cacheAdapterResult(this, value)

private fun cacheAdapterResult(ad: Any, result: AdmobAdapterProbeResult?) {
	cachePrice(adapterPrices, ad, result?.let { AdapterPriceSnapshot(it.hPrice, it.lPrice, it) })
}

private fun updateAdapterPrice(ad: Any, update: AdapterPriceSnapshot.() -> AdapterPriceSnapshot) {
	synchronized(adapterPrices) {
		val updated = (adapterPrices[ad] ?: AdapterPriceSnapshot()).update()
		cachePrice(adapterPrices, ad, updated.takeUnless {
			it.hPrice == null && it.lPrice == null && it.result == null
		})
	}
}

private fun <T : Any> cachePrice(prices: MutableMap<Any, T>, ad: Any, price: T?) {
	if (price == null) prices.remove(ad) else prices[ad] = price
}
