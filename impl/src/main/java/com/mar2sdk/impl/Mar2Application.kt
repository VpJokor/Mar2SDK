package com.mar2sdk.impl

import android.app.Application
import com.mar2sdk.core.Core
import com.mar2sdk.core.AppMod

/**
 * 供业务层代码引用
 */
open class Mar2Application : Application() {

	override fun onCreate() {
		super.onCreate()
		// 核心库初始化
		Core.init(this, AppMod.DEBUG)
	}

}