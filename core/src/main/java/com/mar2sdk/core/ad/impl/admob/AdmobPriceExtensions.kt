package com.mar2sdk.core.ad.impl.admob

import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import java.util.Collections
import java.util.WeakHashMap

// 扩展属性没有实例字段，使用弱引用关联快照，不延长广告实例的生命周期。
private val reflectPrices = Collections.synchronizedMap(WeakHashMap<Any, AdmobPrice>())

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var AppOpenAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cacheReflectPrice(this, value)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var InterstitialAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cacheReflectPrice(this, value)

/** 加载成功时保存的反射价格快照；读取不触发反射，赋 null 清除快照。 */
var RewardedAd.reflectPrice: AdmobPrice?
	get() = reflectPrices[this]
	set(value) = cacheReflectPrice(this, value)

private fun cacheReflectPrice(ad: Any, price: AdmobPrice?) {
	if (price == null) reflectPrices.remove(ad) else reflectPrices[ad] = price
}
