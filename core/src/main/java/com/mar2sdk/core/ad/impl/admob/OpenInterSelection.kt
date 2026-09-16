package com.mar2sdk.core.ad.impl.admob

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class OpenInterSelection<T>(val ad: T?, val timedOut: Boolean)

/** 两种广告共用一个等待期限；超时只取消等待，保留已取得的候选广告。 */
internal suspend fun <T : Any> selectOpenInterAd(
	openAd: T?,
	interAd: T?,
	maxWaitTimeMs: Long,
	loadOpen: suspend () -> T?,
	loadInter: suspend () -> T?,
	priceMicros: (T) -> Long?,
): OpenInterSelection<T> {
	var availableOpen = openAd
	var availableInter = interAd
	val timedOut = if (openAd != null && interAd != null) {
		false
	} else {
		withTimeoutOrNull(maxWaitTimeMs) {
			coroutineScope {
				if (openAd == null) launch { availableOpen = loadOpen() }
				if (interAd == null) launch { availableInter = loadInter() }
			}
			true
		} == null
	}
	return OpenInterSelection(higherPricedOpenInterAd(availableOpen, availableInter, priceMicros), timedOut)
}

internal fun <T : Any> higherPricedOpenInterAd(
	open: T?,
	inter: T?,
	priceMicros: (T) -> Long?,
): T? = when {
	open == null -> inter
	inter == null -> open
	else -> {
		val openPrice = priceMicros(open)
		val interPrice = priceMicros(inter)
		// 未知价格不能当作零；缺价或同价时沿用开屏优先。
		if (openPrice != null && interPrice != null && interPrice > openPrice) inter else open
	}
}
