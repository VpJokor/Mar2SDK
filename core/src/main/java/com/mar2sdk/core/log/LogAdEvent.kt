package com.mar2sdk.core.log

/**
 * 广告相关事件名常量。
 *
 * 这些字符串会同时用于 Firebase、ThinkingData 和 Singular，上报字段名需要和数据后台保持一致。
 */
object LogAdEvent {
	const val ad_occur = "ad_occur"
	const val ad_start_loading = "ad_start_loading"
	const val ad_finish_loading = "ad_finish_loading"
	const val ad_click = "ad_click"
	const val ad_close = "ad_close"
	const val ad_show_fail = "ad_show_fail"
	const val ad_show_timeout = "ad_show_timeout"

	const val ad_impression = "ad_impression"
	const val ad_revenue: String = "ad_revenue"

}
