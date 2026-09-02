package com.mar2sdk.core.ad.impl

import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.mar2sdk.core.Core

// admob的初始化器
object AdmobIniter {

	var initCompleted = false

	// 初始化admob
	fun init() {
		MobileAds.initialize(Core.app) { initializationStatus ->
			initCompleted = true
		}
	}
}