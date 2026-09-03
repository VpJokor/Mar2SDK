package com.mar2sdk.core.ad.impl

import com.mar2sdk.core.Core
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.R
import org.json.JSONObject

//admob的广告配置
object AdmobKey {

	var releaseOpenID = ""
	var releaseInterID = ""
	var releaseVideoID = ""


	//开屏广告过期时间(4小时)
	var openTimeout = 3.5 * 60 * 60 * 1000L
	//开屏广告池大小
	var openPoolSize = 1

	//插屏广告过期时间(1小时)
	var interTimeout = 50 * 60 * 1000L
	// 插屏广告过期时间
	var interPoolSize = 1

	//视频广告过期时间(1小时)
	var videoTimeout = 50 * 60 * 1000L
	var videoPoolSize = 1

}
