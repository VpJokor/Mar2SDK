package com.mar2sdk.core.notify.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.policy.status.UserType
import java.util.concurrent.atomic.AtomicInteger

/**
 * 常驻通知栏
 */
class CommonService : Service() {
	/** 服务启动入口和共享配置。 */
	companion object {
		private const val NOTIFICATION_ID = 3001
		private const val CHANNEL_ID = "channel_id_common"
		private const val TAG = "CommonService"
		private const val MAX_PERSISTENT_ACTIONS = 4

		private val requestCodeGenerator = AtomicInteger(0)

		fun intent(context: Context): Intent {
			return Intent(context, CommonService::class.java).apply {
				setPackage(context.packageName)
			}
		}

		fun start(context: Context) {
			runCatching {
				ContextCompat.startForegroundService(context, intent(context))
			}.onFailure {
				Log.e(TAG, "start: failed to launch foreground service", it)
			}
		}
	}

	private lateinit var notificationManager: NotificationManager

	lateinit var wakeLock: PowerManager.WakeLock

	// 短时持有 WakeLock，降低服务启动后被系统立即挂起的概率。
	private fun acquireWakeLock() {
		val powerManager = getSystemService(POWER_SERVICE) as PowerManager
		wakeLock = powerManager.newWakeLock(
			PowerManager.PARTIAL_WAKE_LOCK,
			"KeepAliveService::WakeLock"
		)
		wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)
	}

	// 释放服务启动阶段持有的 WakeLock。
	private fun releaseWakeLock() {
		if (::wakeLock.isInitialized && wakeLock.isHeld) {
			wakeLock.release()
		}
	}

	// 前台服务不提供绑定能力。
	override fun onBind(intent: Intent?): IBinder? {
		return null
	}

	override fun onCreate() {
		super.onCreate()
		acquireWakeLock()
		try {
			val notification = createNotificationChannel()
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
			} else {
				startForeground(NOTIFICATION_ID, notification)
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	// 服务被重新拉起时刷新前台通知，保持 START_STICKY。
	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		try {
			val notification = createNotificationChannel()
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
			} else {
				startForeground(NOTIFICATION_ID, notification)
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}
		return START_STICKY
	}

	// 任务被移除时尝试c重启
	override fun onTaskRemoved(rootIntent: Intent?) {
		super.onTaskRemoved(rootIntent)
		start(applicationContext)
	}

	// 服务销毁时注销观察器、释放 WakeLock，并清理持久/临时通知。
	override fun onDestroy() {
		super.onDestroy()
		releaseWakeLock()
		notificationManager.cancel(NOTIFICATION_ID)
	}

	// 创建通知通道并返回前台通知。
	private fun createNotificationChannel() : Notification {
		val channel = NotificationChannel(CHANNEL_ID, "Long show notify", NotificationManager.IMPORTANCE_DEFAULT)
		channel.description = "Long show notify desc"
		channel.setShowBadge(false)
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(channel)
		return buildPersistentNotification()
	}

	// 创建前台通知
	private fun buildPersistentNotification(): Notification {
		val remoteViews = RemoteViews(packageName, R.layout.common_notification_mini).apply {
			// TODO:  
//			bindPersistentActions(this, miniPersistentActionSlots())
		}

		val bigRemoteViews = RemoteViews(packageName, R.layout.common_notification).apply {
			// TODO:  
//			bindPersistentActions(this, bigPersistentActionSlots())
		}

		val delIntent = PendingIntent.getBroadcast(
			this,
			0,
			Intent(this, CommonDelReceiver::class.java),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		if (Core.userType != UserType.RISK) {
			val oneYearLater = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000L)
			// 非 paid_0 用户将通知时间设到未来，减少系统按时间排序时被挤下去的概率。
			return NotificationCompat
				.Builder(this, CHANNEL_ID)
				.setCustomContentView(remoteViews)
				.setSmallIcon(R.drawable.nlogo)
				.setCustomBigContentView(bigRemoteViews)
				.setOngoing(true)
				.setShowWhen(true)
				.setWhen(oneYearLater)
				.setColor(getColor(R.color.notification_icon_bg))
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setContentIntent(getPendingIntent())
				.setDeleteIntent(delIntent)
				.build()
		} else {
			// paid_0 用户保留普通持久通知时间。
			return NotificationCompat
				.Builder(this,CHANNEL_ID)
				.setCustomContentView(remoteViews)
				.setSmallIcon(R.drawable.nlogo)
				.setCustomBigContentView(bigRemoteViews)
				.setOngoing(true)
				.setColor(getColor(R.color.notification_icon_bg))
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setContentIntent(getPendingIntent())
				.setDeleteIntent(delIntent)
				.build()
		}
	}

	/** 创建持久通知点击启动 PendingIntent。 */
	private fun getPendingIntent(route: String = ""): PendingIntent {
		val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
		launchIntent.apply {
			putExtra("AppOpenFrom", "persistent")
			putExtra("Route", "persistent")
			putExtra("Scene", "persistent")
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
		}

		val requestCode = requestCodeGenerator.incrementAndGet()
		val pendingIntent = PendingIntent.getActivity(
			this,
			requestCode,
			launchIntent,
			PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		return pendingIntent
	}
}