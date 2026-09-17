package com.mar2sdk.core.common

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import org.json.JSONObject

// 通用配置
object CommonConfig {
	// 高ecpm阈值
	var highEcpm = 10.0
	// 服务端的Url
	var serverUrl = "https://api.newminigame.online"
	// 服务端的App ID
	var serverAppID: Int = 0
	// 服务端的客户端密钥
	var serverClientKey = "clientKey"
	// SDK 初始化时是否自动登录；根据缓存有效期选择 Token 或游客登录。
	var isAutoLogin = true
	// AB测试的名字
	var ABTestName = "Unknow"
	// PlayIntegrity的项目ID
	var PlayIntegrityID = 0L
	// PlayIntegrity Token解析路径
	var parseTokenPath = "/parseToken"
	// IP信息请求路径
	var ipInfoPath = "/getIpInfoV2"
	// 初始化日志请求路径
	var initLogPath = "/server/user/initLog"
	// 平台登录请求路径
	var platformLoginPath = "/server/user/platformLogin"
	// 自动登录刷新Token请求路径
	var autoLoginreflushtokenPath = "/server/user/autoLoginreflushtoken"
	// 用户信息上传请求路径
	var uploadUserPath = "/server/user/uploadUser"

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置。
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.common_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			highEcpm = getDouble("highEcpm")
			serverUrl = getString("serverUrl")
			serverAppID = getInt("serverAppID")
			serverClientKey = getString("serverClientKey")
			isAutoLogin = getBoolean("isAutoLogin")
			ABTestName = getString("ABTestName")
			PlayIntegrityID = getLong("PlayIntegrityID")
			parseTokenPath = getString("parseTokenPath")
			ipInfoPath = getString("ipInfoPath")
			initLogPath = getString("initLogPath")
			platformLoginPath = getString("platformLoginPath")
			autoLoginreflushtokenPath = getString("autoLoginreflushtokenPath")
			uploadUserPath = getString("uploadUserPath")
		}
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(PolicyKey) {
			highEcpm = PreferenceUtil.getDouble(KEY_HIGH_ECPM, highEcpm)
			serverUrl = PreferenceUtil.getString(KEY_SERVER_URL, serverUrl)
			serverAppID = PreferenceUtil.getInt(KEY_SERVER_APP_ID, serverAppID)
			serverClientKey = PreferenceUtil.getString(KEY_SERVER_CLIENT_KEY, serverClientKey)
			isAutoLogin = PreferenceUtil.getBoolean(KEY_IS_AUTO_LOGIN, isAutoLogin)
			ABTestName = PreferenceUtil.getString(KEY_AB_TEST_NAME, ABTestName)
			PlayIntegrityID = PreferenceUtil.getLong(KEY_PLAY_INTEGRITY_ID, PlayIntegrityID)
			parseTokenPath = PreferenceUtil.getString(KEY_PARSE_TOKEN_PATH, parseTokenPath)
			ipInfoPath = PreferenceUtil.getString(KEY_IP_INFO_PATH, ipInfoPath)
			initLogPath = PreferenceUtil.getString(KEY_INIT_LOG_PATH, initLogPath)
			platformLoginPath = PreferenceUtil.getString(KEY_PLATFORM_LOGIN_PATH, platformLoginPath)
			autoLoginreflushtokenPath = PreferenceUtil.getString(KEY_AUTO_LOGIN_REFLUSH_TOKEN_PATH, autoLoginreflushtokenPath)
			uploadUserPath = PreferenceUtil.getString(KEY_UPLOAD_USER_PATH, uploadUserPath)
		}
	}

	// 把配置保存到本地 (Preference)。
	fun saveCommonConfig() {
		with(PolicyKey) {
			PreferenceUtil.commitDouble(KEY_HIGH_ECPM, highEcpm)
			PreferenceUtil.commitString(KEY_SERVER_URL, serverUrl)
			PreferenceUtil.commitInt(KEY_SERVER_APP_ID, serverAppID)
			PreferenceUtil.commitString(KEY_SERVER_CLIENT_KEY, serverClientKey)
			PreferenceUtil.commitBoolean(KEY_IS_AUTO_LOGIN, isAutoLogin)
			PreferenceUtil.commitString(KEY_AB_TEST_NAME, ABTestName)
			PreferenceUtil.commitLong(KEY_PLAY_INTEGRITY_ID, PlayIntegrityID)
			PreferenceUtil.commitString(KEY_PARSE_TOKEN_PATH, parseTokenPath)
			PreferenceUtil.commitString(KEY_IP_INFO_PATH, ipInfoPath)
			PreferenceUtil.commitString(KEY_INIT_LOG_PATH, initLogPath)
			PreferenceUtil.commitString(KEY_PLATFORM_LOGIN_PATH, platformLoginPath)
			PreferenceUtil.commitString(KEY_AUTO_LOGIN_REFLUSH_TOKEN_PATH, autoLoginreflushtokenPath)
			PreferenceUtil.commitString(KEY_UPLOAD_USER_PATH, uploadUserPath)
		}
	}

	internal fun applyConfig(config: JSONObject) {
		highEcpm = config.optDouble("highEcpm", highEcpm)
		serverUrl = config.optString("serverUrl", serverUrl)
		serverAppID = config.optInt("serverAppID", serverAppID)
		serverClientKey = config.optString("serverClientKey", serverClientKey)
		isAutoLogin = config.optBoolean("isAutoLogin", isAutoLogin)
		ABTestName = config.optString("ABTestName", ABTestName)
		PlayIntegrityID = config.optLong("PlayIntegrityID", PlayIntegrityID)
		parseTokenPath = config.optString("parseTokenPath", parseTokenPath)
		ipInfoPath = config.optString("ipInfoPath", ipInfoPath)
		initLogPath = config.optString("initLogPath", initLogPath)
		platformLoginPath = config.optString("platformLoginPath", platformLoginPath)
		autoLoginreflushtokenPath = config.optString("autoLoginreflushtokenPath", autoLoginreflushtokenPath)
		uploadUserPath = config.optString("uploadUserPath", uploadUserPath)
		saveCommonConfig()
	}
}
