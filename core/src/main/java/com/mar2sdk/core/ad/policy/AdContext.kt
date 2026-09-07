package com.mar2sdk.core.ad.policy

import android.os.Parcelable
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import kotlinx.parcelize.Parcelize
import java.util.UUID

enum class ScreenAdTrigger {
	ENTER, RETURN, LEAVE
}

// 广告展示上下文
@Parcelize
data class ScreenAdContext(
	val requestId: String = UUID.randomUUID().toString(),
	var areaKey: String = "preload",
	var adFormat: AdFormat,
	var adPlatform: AdPlatform,
	val trigger: ScreenAdTrigger,
	val fromRoute: String,
	val toRoute: String,
	var adUnitId: String = ""
) : Parcelable
