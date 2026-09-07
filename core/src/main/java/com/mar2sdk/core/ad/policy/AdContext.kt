package com.mar2sdk.core.ad.policy

enum class ScreenAdTrigger {
	ENTER, RETURN, LEAVE
}

data class ScreenAdContext(
	val areaKey: String,
	val trigger: ScreenAdTrigger,
	val fromRoute: String?,
	val toRoute: String
)