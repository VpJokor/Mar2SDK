package com.mar2sdk.core

import android.app.Application
import android.content.Context
import com.mar2sdk.core.ad.AdIniter

/**
 * 核心库入口
 */
object Core {

	lateinit var app: Application
	lateinit var mod: Mod

	// 初始化SDK
	fun init(app: Application, mod: Mod) {
		this@Core.app = app
		this@Core.mod = mod
		// 初始化广告SDK
		AdIniter.init(app)
	}

	fun showAd() {

	}

}