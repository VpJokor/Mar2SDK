package com.mar2sdk.core.log

import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.status.AdPlatform
import com.singular.sdk.SingularAdData

internal fun ScreenAdContext.toAdLogParams(
	formatKey: String = LogAdParam.ad_format
): Map<String, Any> = buildMap {
	put(LogAdParam.request_id, requestId)
	put(LogAdParam.ad_areakey, areaKey)
	put(formatKey, adFormat.name)
	put(LogAdParam.ad_platform, adPlatform.name)
	put(LogAdParam.trigger, trigger.name)
	put(LogAdParam.from_route, fromRoute)
	put(LogAdParam.to_route, toRoute)
}

internal fun ScreenAdContext.toSingularAdData(revenue: Double): SingularAdData {
	val params = toAdLogParams()
	val platform = if (adPlatform == AdPlatform.ADMOB) LogAdParam.adMob else adPlatform.name
	return SingularAdData(platform, LogAdParam.USD, revenue)
		.withAdType(adFormat.name)
		.withAdPlacementName(areaKey)
		.apply {
			for ((key, value) in params) {
				// Keep Singular's platform and mediation platform names consistent.
				if (key != LogAdParam.ad_platform) put(key, value)
			}
		}
}
