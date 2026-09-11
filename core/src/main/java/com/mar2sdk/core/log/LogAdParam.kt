package com.mar2sdk.core.log

import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.singular.sdk.SingularAdData

// 通知/广告跳转时复用的 Intent 参数名和默认广告页路由。
const val FROM = "from"
const val AREA_KEY = "area_key"
const val ROUTE = "/ad_page"


/**
 * 广告埋点参数名常量。
 *
 * 这里的字段名需要与 Firebase Analytics 标准字段、ThinkingData 字段和 Singular 收入字段保持一致。
 */
object LogAdParam {

	// 填充广告池场景
	const val scene = "scene"
	// 打开APP
	const val scene_open_app = "scene_open_app"

	// 通用广告事件参数。
	const val duration = "duration_time"
	const val ad_platform = "ad_platform"
	const val ad_areakey = "areakey"
	const val ad_format = "format"
	const val request_id = "request_id"
	const val trigger = "trigger"
	const val from_route = "from_route"
	const val to_route = "to_route"

	const val ad_source = "ad_source"
	const val ad_unit_name = "ad_unit_name"
	const val ad_reward_type = "reward_type"
	const val ad_reward_amount = "reward_amount"
	/** 应用入口来源，用于归因广告事件（桌面启动/通知启动）。 */
	const val traffic_source = "traffic_source"

	const val ad_preload = "ad_preload"


	// 收入上报相关字段和值。
	const val unknow = "unknow"
	const val USD = "USD"
	const val adMob = "AdMob"

}

internal fun ScreenAdContext.toAdLogParams(
	formatKey: String = LogAdParam.ad_format
): Map<String, Any> = buildMap {
	put(LogAdParam.request_id, requestId)
	put(LogAdParam.ad_areakey, areaKey)
	put(formatKey, adFormat.name)
	put(LogAdParam.ad_platform, adPlatform.name)
	put(LogAdParam.ad_unit_name, adUnitId)
	put(LogAdParam.trigger, trigger.name)
	put(LogAdParam.from_route, fromRoute)
	put(LogAdParam.to_route, toRoute)
}

internal fun ScreenAdContext.toSingularAdData(revenue: Double): SingularAdData {
	val params = toAdLogParams()
	return SingularAdData(adPlatform.name, LogAdParam.USD, revenue)
		.withAdType(adFormat.name)
		.withAdPlacementName(areaKey)
		.withAdUnitId(adUnitId)
		.apply {
			for ((key, value) in params) {
				// Keep Singular's platform and mediation platform names consistent.
				if (key != LogAdParam.ad_platform) put(key, value)
			}
		}
}
