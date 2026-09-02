package com.mar2sdk.core.firebase

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.mar2sdk.core.Core

object FirebaseUtil {

	private const val TAG = "FireBaseUtil"

	fun init() {
		FirebaseApp.initializeApp(Core.app)
		initLog()
		initFCM()
		initRemoteConfig()
	}

	private fun initLog() {
		val mFirebaseAnalytics = FirebaseAnalytics.getInstance(Core.app)
		mFirebaseAnalytics.setAnalyticsCollectionEnabled(true)
	}

	private fun initFCM() {
		FirebaseMessaging.getInstance().token
			.addOnCompleteListener { task ->
				if (!task.isSuccessful) {
					Log.e("FCM", "获取 Token 失败", task.exception)
					return@addOnCompleteListener
				}

				val token = task.result
				Log.d("FCM", "FCM Token: $token")

				// 上传到自己的服务器
			}
	}

	private fun initRemoteConfig() {
		val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig
		val configSettings = remoteConfigSettings {
			minimumFetchIntervalInSeconds = 3600
		}
		remoteConfig.setConfigSettingsAsync(configSettings)
		remoteConfig.addOnConfigUpdateListener(object : ConfigUpdateListener {
			override fun onUpdate(configUpdate: ConfigUpdate) {
				remoteConfig.fetchAndActivate()
					.addOnCompleteListener { task ->
						if (task.isSuccessful) {
							Log.e(TAG, "onUpdate: ", )
						} else {
							Log.e(TAG, "onUpdate: ", )
						}
					}
			}
			override fun onError(error: FirebaseRemoteConfigException) {
				Log.e(TAG, "initRemoteConfig onError: ", error)
			}
		})
	}

}