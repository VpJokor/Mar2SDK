package com.mar2sdk.core.firebase

import android.util.Log
import android.widget.Toast
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.ConfigUpdateListenerRegistration
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.mar2sdk.core.Core
import com.mar2sdk.core.AppMod

object FirebaseUtil {

	private const val TAG = "FireBaseUtil"
	private var initialized = false
	private var configUpdateRegistration: ConfigUpdateListenerRegistration? = null
	@Volatile
	var onRemoteConfigActivated: ((FirebaseRemoteConfig, Set<String>) -> Unit)? = null

	@Synchronized
	fun init() {
		if (!initFirebase()) return
		initLog()
		initFCM()
		initRemoteConfig()
	}

	private fun initFirebase() : Boolean {
		if (initialized) return false
		val firebaseApp = try {
			try {
				FirebaseApp.getInstance()
			} catch (_: IllegalStateException) {
				FirebaseApp.initializeApp(Core.app)
			}
		} catch (error: Exception) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "Firebase 初始化失败", Toast.LENGTH_LONG).show()
			}
			Log.e(TAG, "Firebase 初始化失败", error)
			return false
		}
		if (firebaseApp == null) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "Firebase 未配置，跳过 Firebase/RemoteConfig 初始化", Toast.LENGTH_LONG).show()
			}
			Log.e(TAG, "Firebase 未配置，跳过 Firebase/RemoteConfig 初始化")
			return false
		}
		initialized = true
		return true
	}

	private fun initLog() {
		val mFirebaseAnalytics = FirebaseAnalytics.getInstance(Core.app)
		mFirebaseAnalytics.setAnalyticsCollectionEnabled(true)
	}

	private fun initFCM() {
		FirebaseMessaging.getInstance().token
			.addOnCompleteListener { task ->
				if (!task.isSuccessful) {
					Log.e(TAG, "FCM 获取 Token 失败", task.exception)
					return@addOnCompleteListener
				}

				val token = task.result
				Log.e(TAG, "FCM Token: $token")
				// TODO : 上传到自己的服务器
			}
	}

	private fun initRemoteConfig() {
		val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig
		val configSettings = remoteConfigSettings {
			minimumFetchIntervalInSeconds = 0L
		}
		configUpdateRegistration = remoteConfig.addOnConfigUpdateListener(object : ConfigUpdateListener {
			override fun onUpdate(configUpdate: ConfigUpdate) {
				// 实时更新已经由 SDK 完成抓取，这里只需要激活已抓取的配置。
				// 再次抓取可能会受到 minimumFetchIntervalInSeconds 的限制。
				remoteConfig.activate()
					.addOnCompleteListener { task ->
						if (task.isSuccessful) {
							Log.i(TAG, "RemoteConfig 实时更新已激活: ${configUpdate.updatedKeys}")
							onRemoteConfigActivated?.invoke(remoteConfig, configUpdate.updatedKeys)
						} else {
							Log.e(TAG, "RemoteConfig 实时更新激活失败", task.exception)
						}
					}
			}
			override fun onError(error: FirebaseRemoteConfigException) {
				Log.e(TAG, "RemoteConfig 实时监听失败", error)
			}
		})

		// 首次抓取前先应用配置设置，否则新安装应用只能在服务端实时更新后获取配置。
		remoteConfig.setConfigSettingsAsync(configSettings)
			.addOnSuccessListener {
				remoteConfig.fetchAndActivate()
					.addOnCompleteListener { task ->
						if (task.isSuccessful) {
							Log.i(TAG, "RemoteConfig 初始配置已激活，是否有更新: ${task.result}")
							onRemoteConfigActivated?.invoke(remoteConfig, emptySet())
						} else {
							Log.e(TAG, "RemoteConfig 初始拉取失败", task.exception)
						}
					}
			}
			.addOnFailureListener { error ->
				Log.e(TAG, "RemoteConfig 配置设置失败", error)
			}
	}

}
