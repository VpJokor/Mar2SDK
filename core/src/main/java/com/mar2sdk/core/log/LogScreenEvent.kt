package com.mar2sdk.core.log

/** 页面生命周期和导航事件名。 */
object LogScreenEvent {
	const val screen_open = "screen_open"
	const val screen_close = "screen_close"
	const val screen_navigate = "screen_navigate"
}

/** 页面埋点参数名。 */
object LogScreenParam {
	const val screen_name = "screen_name"
	const val from_route = "from_route"
	const val to_route = "to_route"
	const val reason = "reason"
	const val container = "container"
}
