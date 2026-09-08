package com.mar2sdk.core.notify.app

import android.widget.Toast
import com.chartboost.sdk.impl.fa
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.Core
import com.mar2sdk.core.log.LogAppParam
import com.mar2sdk.core.log.LogNotifyEvent
import com.mar2sdk.core.log.LogNotifyParam
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.notify.NotificationConfig

class AppNotificationManager {

	// 通知触发场景的批次等待队列
	val waitBatchQueue = listOf<String>()

	// 正在发送通知的等待队列
	val sendingQueue = listOf<String>()

	fun startLoop() {

	}

	fun stopLoop() {
		clears()
	}

	// 发送一批通知
	fun sendBatch(scene: String) {
		if (!canSend(true)) return
		if (!canSendBatch(scene)) return

		// TODO: 每隔 6秒发一条，连发3条，这3条等待发送的通知用队列管理

		LogUtil.log(LogNotifyEvent.notify_send_batch, mapOf(LogNotifyParam.isSuccess to true))
	}

	// TODO: 清理 waitBatchQueue 和 sendingQueue
	fun clears() {
		if (Core.appMod == AppMod.DEBUG) {
			Toast.makeText(Core.app, "清空待发送队列", Toast.LENGTH_LONG).show()
		}
		LogUtil.log(LogNotifyEvent.clear_notifications, mapOf())
	}

	fun send(scene: String) {
		if (!canSend(false)) return
		if (!canSendItem(scene)) return
		AppNotificationUtil.sendNotificationContent(scene)
	}

	// 通知发送限制
	fun canSend(isBatch: Boolean) : Boolean {
		if (!NotificationConfig.isSend) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "APP通知总开关没开不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				if (isBatch) LogNotifyEvent.notify_send_batch else LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "APP通知总开关没开不发通知",)
			)
			return false
		}
		if ((!NotificationConfig.isForgroundSend) && AppStatus.isForeground) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "APP在前台不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				if (isBatch) LogNotifyEvent.notify_send_batch else LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "APP在前台不发通知",)
			)
			return false
		}
		if ((!NotificationConfig.isScreenOffSend) && (!AppStatus.isScreenOn)) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "手机熄屏不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				if (isBatch) LogNotifyEvent.notify_send_batch else LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "手机熄屏不发通知",)
			)
			return false
		}
		if ((!NotificationConfig.isScreenLockSend) && AppStatus.isLocked) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "手机锁屏不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				if (isBatch) LogNotifyEvent.notify_send_batch else LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "手机锁屏不发通知",)
			)
			return false
		}
		return true
	}

	fun canSendBatch(scene: String) : Boolean {

		return true
	}

	fun canSendItem(scene: String) : Boolean {

		return true
	}

}