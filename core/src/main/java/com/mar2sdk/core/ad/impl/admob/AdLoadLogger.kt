package com.mar2sdk.core.ad.impl.admob

import android.util.Log
import com.google.android.gms.ads.LoadAdError
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.log.LogAdEvent
import com.mar2sdk.core.log.LogAdParam
import com.mar2sdk.core.log.toAdLogParams

/** 记录单次广告请求的结果；上报异常不能影响广告回调或广告池。 */
internal object AdLoadLogger {
	fun started(adContext: ScreenAdContext) = log(LogAdEvent.ad_start_loading, adContext)

	fun succeeded(
		adContext: ScreenAdContext,
		price: AdmobPrice? = null,
		durationMillis: Long? = null,
		adSource: String? = null,
	) {
		val params = buildMap<String, Any> {
			if (price != null) {
				put(LogAdParam.ad_price_micros, price.valueMicros)
				put(LogAdParam.ad_price_currency, price.currencyCode)
				put(LogAdParam.ad_price_precision, price.precisionType)
				put(LogAdParam.ad_ecpm, price.ecpm)
			}
			if (durationMillis != null) put(LogAdParam.duration, durationMillis)
			if (adSource != null) put(LogAdParam.ad_source, adSource)
		}
		log(LogAdEvent.ad_finish_loading, adContext, params)
	}

	fun failed(adContext: ScreenAdContext, error: LoadAdError) = log(
		LogAdEvent.ad_load_fail,
		adContext,
		mapOf(
			LogAdParam.failure_reason to "SDK_ERROR",
			LogAdParam.error_code to error.code,
			LogAdParam.error_domain to error.domain,
			LogAdParam.error_message to error.message.ifBlank { "AdMob SDK load failed (code=${error.code})" },
		),
	)

	fun failed(adContext: ScreenAdContext, error: Exception) = log(
		LogAdEvent.ad_load_fail,
		adContext,
		mapOf(
			LogAdParam.failure_reason to "LOAD_EXCEPTION",
			LogAdParam.error_type to error.javaClass.name,
			LogAdParam.error_message to (error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name),
		),
	)

	fun failed(adContext: ScreenAdContext, reason: String, message: String) = log(
		LogAdEvent.ad_load_fail,
		adContext,
		mapOf(
			LogAdParam.failure_reason to reason,
			LogAdParam.error_message to message.ifBlank { reason },
		),
	)

	private fun log(event: String, adContext: ScreenAdContext, extra: Map<String, Any> = emptyMap()) {
		try {
			Core.log(event, adContext.toAdLogParams() +
				(LogAdParam.ad_preload to (adContext.areaKey == "preload")) + extra)
		} catch (error: Exception) {
			Log.e("AdLoadLogger", "Failed to log $event", error)
		}
	}
}
