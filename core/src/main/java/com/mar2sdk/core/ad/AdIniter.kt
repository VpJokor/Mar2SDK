package com.mar2sdk.core.ad

import android.content.Context
import com.mar2sdk.core.ad.impl.AdmobIniter

/**
 * 广告SDK初始化器
 */
object AdIniter {
	//初始化广告SDK
	fun init(context: Context) {
		AdmobIniter.init(context)
	}
}