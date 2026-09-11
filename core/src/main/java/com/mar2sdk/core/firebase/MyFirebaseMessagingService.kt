package com.mar2sdk.core.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.log.LogNotifyEvent
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.notify.common.CommonDelReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

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

		// 记录服务端下发的关键 FCM 字段，便于统计推送到达和排查内容。
		val appOpenFrom = message.data["AppOpenFrom"] ?: "AppOpenFrom"
		val fCMSendTime = message.data["FCMSendTime"] ?: "FCMSendTime"
		val fcmId = message.data["Id"] ?: "fcmId"
		val appPackage = message.data["AppPackage"] ?: "AppPackage"
		val fcmContent = message.data["FcmContent"] ?: "FcmContent"
		val fcmType = message.data["FcmType"] ?: "FcmType"
		val fcmTitle = message.data["FcmTitle"] ?: "FcmTitle"

		Log.e(
				TAG, "onMessageReceived: " +
				"environment=${Core.appMod}, " +
				"appOpenFrom=${appOpenFrom}, " +
				"fCMSendTime=${fCMSendTime}, " +
				"fcmId=${fcmId}, " +
				"appPackage=${appPackage}, " +
				"fcmContent=${fcmContent}, " +
				"fcmType=${fcmType}" +
				"fcmTitle=${fcmTitle}"
		)

		LogUtil.log(
			LogNotifyEvent.receive_fcm,mapOf(
			"fCMSendTime" to fCMSendTime,
			"fcmId" to fcmId,
			"appPackage" to appPackage,
			"fcmContent" to fcmContent,
			"fcmType" to fcmType,
			"fcmTitle" to fcmTitle
		))

		if (Core.appMod != AppMod.RELEASE) {
			showNotification(message)
		}

		sendBroadcast(Intent(this, CommonDelReceiver::class.java))
		try {
			val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
			val componentName = android.content.ComponentName(this, ServiceStarterJobService::class.java)
			val jobInfo = android.app.job.JobInfo.Builder(1001, componentName)
				.setMinimumLatency(10 * 1000) // 延迟 10 秒
				.setOverrideDeadline(15 * 1000) // 最晚 15 秒内必须执行
				.setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_NONE) // 不需要网络
				.build()
			jobScheduler.schedule(jobInfo)
		} catch (e: Exception) {
			Log.e(TAG, "scheduleServiceStarter: failed with ${e.javaClass.simpleName}")
		}
		try {
			val workRequest = androidx.work.OneTimeWorkRequestBuilder<ServiceWorker>()
				.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
				.build()
			androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
		} catch (e: Exception) {
			Log.e(TAG, "enqueueServiceWorker: failed with ${e.javaClass.simpleName}")
		}
	}

	private fun showNotification(remoteMessage: RemoteMessage) {

		val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

		val pendingIntent = PendingIntent.getActivity(
			this,
			0,
			Intent(),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		val title = remoteMessage.notification?.title
			?: remoteMessage.data["FcmTitle"]
			?: remoteMessage.data["title"]
			?: "测试通知"
		val body = remoteMessage.notification?.body
			?: remoteMessage.data["FcmContent"]
			?: remoteMessage.data["body"]
				.orEmpty()
		val notificationBuilder = NotificationCompat.Builder(this,CHANNEL_ID)
			.setContentTitle(title)
			.setContentText(body)
			.setContentIntent(pendingIntent)
			.setVibrate(longArrayOf(1000, 1000, 1000))
			.setLights(Color.RED, 3000, 3000)
			.setAutoCancel(true)

		notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())

	}


}
