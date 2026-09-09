package com.mar2sdk.core.notify.app

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.log.ThinkingConfig
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.util.DBUtil
import com.mar2sdk.core.util.PreferenceUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 由系统显式广播拉起，发送完成前用 goAsync 保持接收器有效。 */
class NotificationAlarmReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action !in ACTIONS) return
		val pending = goAsync()
		val powerManager = context.getSystemService(PowerManager::class.java)
		val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mar2SDK:NotificationTimer")
		wakeLock.acquire(9_000L)
		scope.launch {
			try {
				withTimeout(8_000L) {
					prepare(context)
					if (intent.action == NotificationAlarmScheduler.ACTION_FIRE) {
						val scene = NotificationAlarmScheduler.consume(intent) ?: return@withTimeout
						AppStatus.isScreenOn = powerManager.isInteractive
						AppStatus.isLocked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
						AppNotificationUtil.createChannels()
						AppNotificationManager.sendTimerItem(scene)
						// 等待之前排队的发送日志写入，供下一条通知的限额检查使用。
						DBUtil.queryLogs(limit = 1)
					} else {
						NotificationAlarmScheduler.refresh(
							resetTimes = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
						)
					}
				}
			} catch (exception: Exception) {
				Log.e("NotificationAlarm", "Failed to handle notification alarm", exception)
			} finally {
				if (wakeLock.isHeld) wakeLock.release()
				pending.finish()
			}
		}
	}

	private fun prepare(context: Context) {
		// 通常宿主 Application 已完成 Core.init；同时支持只接入 core 的宿主冷启动。
		if (runCatching { Core.app }.isFailure) {
			Core.app = context.applicationContext as Application
			Core.appMod = AppMod.RELEASE
			PreferenceUtil.init()
			UserInfo.init()
			ThinkingConfig.init()
			NotificationConfig.init()
		}
		PreferenceUtil.init()
		DBUtil.init()
	}

	companion object {
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
		private val ACTIONS = setOf(
			NotificationAlarmScheduler.ACTION_FIRE,
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_MY_PACKAGE_REPLACED,
			Intent.ACTION_TIME_CHANGED,
			Intent.ACTION_TIMEZONE_CHANGED
		)
	}
}
