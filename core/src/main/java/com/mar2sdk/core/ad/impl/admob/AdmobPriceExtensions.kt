package com.mar2sdk.core.ad.impl.admob

import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import java.util.Collections
import java.util.WeakHashMap

// 扩展属性没有实例字段，使用弱引用关联快照，不延长广告实例的生命周期。
private val reflectPrices = Collections.synchronizedMap(WeakHashMap<Any, AdmobPrice>())
private val adapterHPrices = Collections.synchronizedMap(WeakHashMap<Any, Long>())
private val adapterLPrices = Collections.synchronizedMap(WeakHashMap<Any, Long>())

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var AppOpenAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 供 AdMob 价格探针保存的高价快照，单位为 USD eCPM 微单位；赋 null 清除快照。 */
var AppOpenAd.adapterHPrice: Long?
	get() = adapterHPrices[this]
	set(value) = cachePrice(adapterHPrices, this, value)

/** 供 AdMob 价格探针保存的低价快照，单位为 USD eCPM 微单位；赋 null 清除快照。 */
var AppOpenAd.adapterLPrice: Long?
	get() = adapterLPrices[this]
	set(value) = cachePrice(adapterLPrices, this, value)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var InterstitialAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 供 AdMob 价格探针保存的高价快照，单位为 USD eCPM 微单位；赋 null 清除快照。 */
var InterstitialAd.adapterHPrice: Long?
	get() = adapterHPrices[this]
	set(value) = cachePrice(adapterHPrices, this, value)

/** 供 AdMob 价格探针保存的低价快照，单位为 USD eCPM 微单位；赋 null 清除快照。 */
var InterstitialAd.adapterLPrice: Long?
	get() = adapterLPrices[this]
	set(value) = cachePrice(adapterLPrices, this, value)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var RewardedAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cachePrice(reflectPrices, this, value)

/** 供 AdMob 价格探针保存的高价快照，单位为 USD eCPM 微单位；赋 null 清除快照。 */
var RewardedAd.adapterHPrice: Long?
	get() = adapterHPrices[this]
	set(value) = cachePrice(adapterHPrices, this, value)

/** 供 AdMob 价格探针保存的低价快照，单位为 USD eCPM 微单位；赋 null 清除快照。 */
var RewardedAd.adapterLPrice: Long?
	get() = adapterLPrices[this]
	set(value) = cachePrice(adapterLPrices, this, value)

private fun <T : Any> cachePrice(prices: MutableMap<Any, T>, ad: Any, price: T?) {
	if (price == null) prices.remove(ad) else prices[ad] = price
}
