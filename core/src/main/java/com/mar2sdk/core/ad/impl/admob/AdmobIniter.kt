package com.mar2sdk.core.ad.impl.admob

import com.google.android.gms.ads.MobileAds
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.status.AdInitStatus

// admob的初始化器
object AdmobIniter {

	var completed = AdInitStatus.UNCOMPLETED

	// 初始化admob
	fun init() {
		MobileAds.initialize(Core.app) { initializationStatus ->
			completed = AdInitStatus.COMPLETED
		}
	}
}