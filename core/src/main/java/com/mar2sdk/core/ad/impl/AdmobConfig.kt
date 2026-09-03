package com.mar2sdk.core.ad.impl

import com.mar2sdk.core.Core
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.R
import org.json.JSONObject

//admob的广告配置
object AdmobConfig {
	val testOpenID = "ca-app-pub-3940256099942544/9257395921"
	val testInterID = "ca-app-pub-3940256099942544/1033173712"
	val testVideoID = "ca-app-pub-3940256099942544/5224354917"

	var releaseOpenID = ""
	var releaseInterID = ""
	var releaseVideoID = ""

	var openID = if (Core.appMod == AppMod.TEST) testOpenID else releaseOpenID
	var interID = if (Core.appMod == AppMod.TEST) testInterID else releaseInterID
	var VideoID = if (Core.appMod == AppMod.TEST) testVideoID else releaseVideoID


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

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置。
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.admob_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		// 先解析所有必填项，避免配置错误时只更新了部分字段。
		val localReleaseOpenID = config.getString("releaseOpenID")
		val localReleaseInterID = config.getString("releaseInterID")
		val localReleaseVideoID = config.getString("releaseVideoID")
		val localOpenTimeout = config.getDouble("openTimeout")
		val localInterTimeout = config.getLong("interTimeout")
		val localVideoTimeout = config.getLong("videoTimeout")
		val localOpenPoolSize = config.getInt("openPoolSize")
		val localInterPoolSize = config.getInt("interPoolSize")
		val localVideoPoolSize = config.getInt("videoPoolSize")

		releaseOpenID = localReleaseOpenID
		releaseInterID = localReleaseInterID
		releaseVideoID = localReleaseVideoID
		openTimeout = localOpenTimeout
		interTimeout = localInterTimeout
		videoTimeout = localVideoTimeout
		openPoolSize = localOpenPoolSize
		interPoolSize = localInterPoolSize
		videoPoolSize = localVideoPoolSize

		openID = if (Core.appMod == AppMod.TEST) testOpenID else releaseOpenID
		interID = if (Core.appMod == AppMod.TEST) testInterID else releaseInterID
		VideoID = if (Core.appMod == AppMod.TEST) testVideoID else releaseVideoID
	}

	// TODO: 从本地读配置 (Preference)
	fun loadConfigFromPreference() {

	}

	fun save() {

	}
}
