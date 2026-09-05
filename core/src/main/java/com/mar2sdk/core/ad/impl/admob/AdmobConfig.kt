package com.mar2sdk.core.ad.impl.admob

import com.mar2sdk.core.Core
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.R
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONObject

//admob的广告配置
object AdmobConfig {
	val testOpenID = "ca-app-pub-3940256099942544/9257395921"
	val testInterID = "ca-app-pub-3940256099942544/1033173712"
	val testVideoID = "ca-app-pub-3940256099942544/5224354917"

	var releaseOpenID = ""
	var releaseInterID = ""
	var releaseVideoID = ""

	var openID = if (Core.appMod == AppMod.TEST || Core.appMod == AppMod.DEBUG) testOpenID else releaseOpenID
	var interID = if (Core.appMod == AppMod.TEST || Core.appMod == AppMod.DEBUG) testInterID else releaseInterID
	var VideoID = if (Core.appMod == AppMod.TEST || Core.appMod == AppMod.DEBUG) testVideoID else releaseVideoID


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

		with(config) {
			releaseOpenID = getString("releaseOpenID")
			releaseInterID = getString("releaseInterID")
			releaseVideoID = getString("releaseVideoID")
			openTimeout = getDouble("openTimeout")
			interTimeout = getLong("interTimeout")
			videoTimeout = getLong("videoTimeout")
			openPoolSize = getInt("openPoolSize")
			interPoolSize = getInt("interPoolSize")
			videoPoolSize = getInt("videoPoolSize")
		}
		updateAdUnitIds()
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(AdmobKey) {
			releaseOpenID = PreferenceUtil.getString(KEY_RELEASE_OPEN_ID, releaseOpenID)
			releaseInterID = PreferenceUtil.getString(KEY_RELEASE_INTER_ID, releaseInterID)
			releaseVideoID = PreferenceUtil.getString(KEY_RELEASE_VIDEO_ID, releaseVideoID)
			openTimeout = PreferenceUtil.getDouble(KEY_OPEN_TIMEOUT, openTimeout)
			interTimeout = PreferenceUtil.getLong(KEY_INTER_TIMEOUT, interTimeout)
			videoTimeout = PreferenceUtil.getLong(KEY_VIDEO_TIMEOUT, videoTimeout)
			openPoolSize = PreferenceUtil.getInt(KEY_OPEN_POOL_SIZE, openPoolSize)
			interPoolSize = PreferenceUtil.getInt(KEY_INTER_POOL_SIZE, interPoolSize)
			videoPoolSize = PreferenceUtil.getInt(KEY_VIDEO_POOL_SIZE, videoPoolSize)
		}
		updateAdUnitIds()
	}

	private fun updateAdUnitIds() {
		val isTest = (Core.appMod == AppMod.TEST) || (Core.appMod == AppMod.DEBUG)
		openID = if (isTest) testOpenID else releaseOpenID
		interID = if (isTest) testInterID else releaseInterID
		VideoID = if (isTest) testVideoID else releaseVideoID
	}

	// 把配置保存到本地 (Preference)。
	fun saveAdmobConfig() {
		with(AdmobKey) {
			PreferenceUtil.commitString(KEY_RELEASE_OPEN_ID, releaseOpenID)
			PreferenceUtil.commitString(KEY_RELEASE_INTER_ID, releaseInterID)
			PreferenceUtil.commitString(KEY_RELEASE_VIDEO_ID, releaseVideoID)
			PreferenceUtil.commitDouble(KEY_OPEN_TIMEOUT, openTimeout)
			PreferenceUtil.commitLong(KEY_INTER_TIMEOUT, interTimeout)
			PreferenceUtil.commitLong(KEY_VIDEO_TIMEOUT, videoTimeout)
			PreferenceUtil.commitInt(KEY_OPEN_POOL_SIZE, openPoolSize)
			PreferenceUtil.commitInt(KEY_INTER_POOL_SIZE, interPoolSize)
			PreferenceUtil.commitInt(KEY_VIDEO_POOL_SIZE, videoPoolSize)
		}
	}
}
