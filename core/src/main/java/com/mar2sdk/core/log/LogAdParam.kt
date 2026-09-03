package com.mar2sdk.core.log

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

	// 通用广告事件参数。
	const val duration = "duration_time"
	const val ad_platform = "ad_platform"
	const val ad_platform_admob = "adMob"
	const val ad_areakey = "areakey"
	const val ad_format = "format"
	const val ad_format_open = "open"
	const val ad_format_inter = "inter"
	const val ad_format_video = "video"

	const val ad_format_banner = "merc"
	const val ad_format_native = "native"
	const val ad_source = "ad_source"
	const val ad_unit_name = "ad_unit_name"
	const val ad_reward_type = "reward_type"
	const val ad_reward_amount = "reward_amount"

	const val ad_preload = "ad_preload"


	// 收入上报相关字段和值。
	const val unknow = "unknow"
	const val USD = "USD"
	const val adMob = "AdMob"

}
