package com.mar2sdk.core.log

/**
 * 广告相关事件名常量。
 *
 * 这些字符串会同时用于 Firebase、ThinkingData 和 Singular，上报字段名需要和数据后台保持一致。
 */
object LogAdEvent {
	// 广告 SDK 初始化与广告生命周期事件。
	const val adv_sdk_initcomplete = "adv_sdk_initcomplete"
	const val ad_occur = "ad_occur"
	const val ad_preload_start = "ad_preload_start"
	const val ad_start_loading = "ad_start_loading"
	const val ad_finish_loading = "ad_finish_loading"
	const val ad_impression = "ad_impression"
	const val ad_click = "ad_click"
	const val ad_close = "ad_close"
	const val ad_show_fail = "ad_show_fail"
	const val ad_show_timeout = "ad_show_timeout"

	const val ad_revenue: String = "ad_revenue"

	// 收入和展示聚合事件。
	const val ad_display = "display"

	const val ad_Impression_Revenue = "Ad_Impression_Revenue"

	const val total_Ads_Revenue_001 = "Total_Ads_Revenue_001"

}
