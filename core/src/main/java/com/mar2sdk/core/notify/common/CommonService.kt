package com.mar2sdk.core.notify.common

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
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
			// TODO: 检查前台服务权限

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

	}

}