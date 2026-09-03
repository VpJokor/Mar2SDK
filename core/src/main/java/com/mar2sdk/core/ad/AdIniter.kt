package com.mar2sdk.core.ad

import com.mar2sdk.core.ad.impl.admob.AdmobIniter
import com.mar2sdk.core.ad.impl.max.MaxIniter

/**
 * 广告SDK初始化器
 */
object AdIniter {

	//初始化广告SDK
	fun init() {
		AdmobIniter.init()
		MaxIniter.init()
		ToponIniter.init()
	}


}