package com.mar2sdk.core.log

/**
 * 广告相关事件名常量。
 *
 * 这些字符串会同时用于 Firebase、ThinkingData 和 Singular，上报字段名需要和数据后台保持一致。
 */
object LogAppEvent {
	const val app_foreground = "app_foreground"
	const val app_background = "app_background"
	// APP 从最近任务列表中移除。
	const val app_exit = "app_exit"

}
