package com.mar2sdk.impl

import com.mar2sdk.core.Core
import com.mar2sdk.core.log.LogScreenEvent
import com.mar2sdk.core.log.LogScreenParam

internal const val SCREEN_CONTAINER_COMPOSE = "compose"
internal const val SCREEN_CONTAINER_ACTIVITY = "activity"

internal fun logScreenOpen(
	screenName: String,
	fromRoute: String,
	container: String,
	reason: String,
) {
	logScreenEvent(
		LogScreenEvent.screen_open,
		mapOf(
			LogScreenParam.screen_name to screenName,
			LogScreenParam.from_route to fromRoute,
			LogScreenParam.container to container,
			LogScreenParam.reason to reason,
		),
	)
}

internal fun logScreenClose(
	screenName: String,
	fromRoute: String,
	container: String,
	reason: String,
) {
	logScreenEvent(
		LogScreenEvent.screen_close,
		mapOf(
			LogScreenParam.screen_name to screenName,
			LogScreenParam.from_route to fromRoute,
			LogScreenParam.container to container,
			LogScreenParam.reason to reason,
		),
	)
}

internal fun logScreenNavigation(
	fromRoute: String,
	toRoute: String,
	container: String,
	reason: String,
) {
	logScreenEvent(
		LogScreenEvent.screen_navigate,
		mapOf(
			LogScreenParam.from_route to fromRoute,
			LogScreenParam.to_route to toRoute,
			LogScreenParam.container to container,
			LogScreenParam.reason to reason,
		),
	)
}

/** 埋点失败不能影响页面展示、广告流程或业务导航。 */
private fun logScreenEvent(eventName: String, params: Map<String, Any>) {
	runCatching { Core.log(eventName, params) }
}
