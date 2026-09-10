package com.mar2sdk.core.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mar2sdk.core.Core

class MyFirebaseMessagingService : FirebaseMessagingService() {

	companion object {
		private const val TAG = "MyFirebaseMessagingServ"
		private const val CHANNEL_ID = "MyFirebaseMessagingService_CHANNEL"
		private const val NOTIFICATION_ID = 789012
	}

	// 创建 FCM 调试通知通道。
	private fun createNotificationChannel() {
		val channel = NotificationChannel(
			CHANNEL_ID,
			"FCM Notifications",
			NotificationManager.IMPORTANCE_DEFAULT
		).apply {
			description = "Receive FCM notifications"
			enableLights(true)
			lightColor = Color.RED
			enableVibration(true)
			vibrationPattern = longArrayOf(100, 200, 300, 400, 500)
		}
		val notificationManager = getSystemService(NotificationManager::class.java)
		notificationManager.createNotificationChannel(channel)
	}

	override fun onCreate() {
		super.onCreate()
		createNotificationChannel()
	}

	override fun onNewToken(token: String) {
		super.onNewToken(token)
		Log.e(TAG, "onNewToken: environment=${Core.appMod}, token=$token")

	}

	override fun onMessageReceived(message: RemoteMessage) {
		super.onMessageReceived(message)
		Log.e(TAG, "onMessageReceived: 收到FCM")

	}


}