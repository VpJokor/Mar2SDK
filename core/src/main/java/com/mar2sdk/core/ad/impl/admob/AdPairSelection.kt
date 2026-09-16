package com.mar2sdk.core.ad.impl.admob

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class AdPairSelection<T>(val ad: T?, val timedOut: Boolean)

/** 两种广告共用一个等待期限；超时只取消等待，保留已取得的候选广告。 */
internal suspend fun <T : Any> selectAdPair(
	primaryAd: T?,
	secondaryAd: T?,
	maxWaitTimeMs: Long,
	loadPrimary: suspend () -> T?,
	loadSecondary: suspend () -> T?,
	priceMicros: (T) -> Long?,
): AdPairSelection<T> {
	var availablePrimary = primaryAd
	var availableSecondary = secondaryAd
	val timedOut = if (primaryAd != null && secondaryAd != null) {
		false
	} else {
		withTimeoutOrNull(maxWaitTimeMs) {
			coroutineScope {
				if (primaryAd == null) launch { availablePrimary = loadPrimary() }
				if (secondaryAd == null) launch { availableSecondary = loadSecondary() }
			}
			true
		} == null
	}
	return AdPairSelection(higherPricedAd(availablePrimary, availableSecondary, priceMicros), timedOut)
}

internal fun <T : Any> higherPricedAd(
	primary: T?,
	secondary: T?,
	priceMicros: (T) -> Long?,
): T? = when {
	primary == null -> secondary
	secondary == null -> primary
	else -> {
		val primaryPrice = priceMicros(primary)
		val secondaryPrice = priceMicros(secondary)
		// 未知价格不能当作零；缺价或同价时优先选择第一种广告。
		if (primaryPrice != null && secondaryPrice != null && secondaryPrice > primaryPrice) secondary else primary
	}
}
