package com.mar2sdk.core.ad.impl

import android.content.Context
import com.google.android.gms.ads.MobileAds

// admob的初始化器
object AdmobIniter {

	var initCompleted = false

	// 初始化admob
	fun init(context: Context) {
		MobileAds.initialize(context) { initializationStatus ->
			initCompleted = true
		}
	}
}