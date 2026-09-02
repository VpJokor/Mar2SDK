package com.mar2sdk.core.ad.impl

import com.mar2sdk.core.Core
import com.mar2sdk.core.Mod

//admob的广告配置
object AdmobConfig {
	val testOpenID = "ca-app-pub-3940256099942544/9257395921"
	val testInterID = "ca-app-pub-3940256099942544/1033173712"
	val testVideoID = "ca-app-pub-3940256099942544/5224354917"

	var releaseOpenID = ""
	var releaseInterID = ""
	var releaseVideoID = ""

	var openID = if (Core.mod == Mod.TEST) testOpenID else releaseOpenID
	var interID = if (Core.mod == Mod.TEST) testInterID else releaseInterID
	var VideoID = if (Core.mod == Mod.TEST) testVideoID else releaseVideoID


	//开屏广告过期时间(4小时)
	var openTimeout = 4 * 60 * 60 * 1000L
	//开屏广告池大小
	var openPoolSize = 1

	//插屏广告过期时间(1小时)
	var interTimeout = 60 * 60 * 1000L
	// 插屏广告过期时间
	var interPoolSize = 1

	//视频广告过期时间(1小时)
	var videoTimeout = 60 * 60 * 1000L
	var videoPoolSize = 1
}