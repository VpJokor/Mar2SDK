package com.mar2sdk.core.notify.app

import android.widget.Toast
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.log.LogAppParam
import com.mar2sdk.core.log.LogNotifyEvent
import com.mar2sdk.core.log.LogNotifyParam
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.util.DBUtil
import org.json.JSONException
import org.json.JSONObject

/**
 * 通知触发场景的批次等待队列 的 通知批次
 * scene 触发场景
 * timeAt 触发时间
 */
data class NotificationBatch(val scene: String, val timeAt: Long)

/**
 * 正在发送通知的等待队列 的 通知项
 * scene 触发场景
 * timeAt 触发时间
 */
data class NotificationItem(val scene: String, val timeAt: Long)

class AppNotificationManager {

	// 通知触发场景的批次等待队列
	val waitBatchQueue = mutableListOf<NotificationBatch>()

	// 正在发送通知的等待队列
	val sendingQueue = mutableListOf<NotificationItem>()

	fun startLoop() {

	}

	fun stopLoop() {
		clears()
	}

	suspend fun addBatch(scene: String) {
		// INFO: 没有延迟（trigger.delay）发送的立即调 sendBatch，有延迟发送的放到 waitBatchQueue 中
		val trigger = NotificationConfig.triggers[scene] ?: return
		if (trigger.delay > 0 ) {
			waitBatchQueue.add(NotificationBatch(scene, System.currentTimeMillis()))
		} else {
			sendBatch(scene)
		}
	}

	// 发送一批通知
	suspend fun sendBatch(scene: String) {
		if (!canSendBatch(scene)) return

		val trigger = NotificationConfig.triggers[scene] ?: return
		if (trigger.count <= 0) return

		// 批次中的通知按场景配置的单条间隔排队。
		repeat(trigger.count) { index ->
			sendingQueue.add(
				NotificationItem(
					scene = scene,
					timeAt = index.toLong() * trigger.intervalItem * 1_000L
				)
			)
		}

		LogUtil.log(LogNotifyEvent.notify_send_batch, mapOf(LogNotifyParam.isSuccess to true, LogNotifyParam.scene to scene))
	}

	// 清理 waitBatchQueue 和 sendingQueue
	fun clears() {
		waitBatchQueue.clear()
		sendingQueue.clear()
		if (Core.appMod == AppMod.DEBUG) {
			Toast.makeText(Core.app, "清空待发送队列", Toast.LENGTH_LONG).show()
		}
		LogUtil.log(LogNotifyEvent.clear_notifications, mapOf())
	}

	suspend fun send(scene: String) {
		if (!canSendItem(scene)) return
		AppNotificationUtil.sendNotificationContent(scene)
		LogUtil.log(LogNotifyEvent.notify_send_item, mapOf(LogNotifyParam.isSuccess to true, LogNotifyParam.scene to scene))
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

	//发送批次限制
	suspend fun canSendBatch(scene: String) : Boolean {
		if (!canSend(true)) return false
		val currentTime = System.currentTimeMillis()
		if (sendingQueue.isNotEmpty()) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "有通知正在发送，不发新批次通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "有通知正在发送，不发新批次通知",)
			)
			return false
		}
		//首次打开时间
		val firstOpenTime = UserInfo.firstOpenTime
		val trigger = NotificationConfig.triggers[scene]
		if (trigger != null && currentTime - firstOpenTime < trigger.firstDelay.toLong() * 1000) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 首次打开时间小于场景通知首次发送延迟", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "首次打开时间小于场景通知首次发送延迟",
				)
			)
			return false
		}
		// 分页读取本地Log，只统计成功发送的通知
		val sentLogs = mutableListOf<DBUtil.LocalLog>()
		var beforeId = Long.MAX_VALUE
		while (true) {
			val logs = DBUtil.queryLogs(limit = 500, beforeId = beforeId)
			if (logs.isEmpty()) break
			for (log in logs) {
				if (log.eventName != LogNotifyEvent.notify_send_batch && log.eventName != LogNotifyEvent.notify_send_item) continue
				val params = try {
					JSONObject(log.paramsJson)
				} catch (e: JSONException) {
					continue
				}
				if (params.optBoolean(LogNotifyParam.isSuccess)) {
					sentLogs.add(log)
				}
			}
			beforeId = logs.last().id
		}
		val sentBatchLogs = sentLogs.filter { it.eventName == LogNotifyEvent.notify_send_batch }
		val sentItemLogs = sentLogs.filter { it.eventName == LogNotifyEvent.notify_send_item }
		// 上一次成功触发时间(查本地Log)
		val lastSendBatchTime = sentBatchLogs.maxOfOrNull { it.eventTimeMillis } ?: 0L
		if (lastSendBatchTime > 0L && currentTime - lastSendBatchTime < NotificationConfig.intervalSecond.toLong() * 1000) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 触发时间小于批次通知全局发送间隔", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "触发时间小于批次通知全局发送间隔",
				)
			)
			return false
		}
		// 最近的24小时内发送了几批(查本地Log)
		val _24HSentBatchCount = sentBatchLogs.count { it.eventTimeMillis in (currentTime - 24 * 60 * 60 * 1000L)..currentTime }
		if (_24HSentBatchCount >= NotificationConfig.max24HBatch) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 最近的24小时内发送批达到发送限制", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "最近的24小时内发送批达到发送限制"
				)
			)
			return false
		}
		// 最近的1小时内发送了几批
		val _1HSentBatchCount = sentBatchLogs.count { it.eventTimeMillis in (currentTime - 60 * 60 * 1000L)..currentTime }
		if (_1HSentBatchCount >= NotificationConfig.max1HBatch) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 最近的1小时内发送批达到发送限制", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "最近的1小时内发送批达到发送限制",
				)
			)
			return false
		}
		// 最近的24小时内发送了几条
		val _24HSentItemCount = sentItemLogs.count { it.eventTimeMillis in (currentTime - 24 * 60 * 60 * 1000L)..currentTime }
		if (_24HSentItemCount >= NotificationConfig.max24HItem) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 最近的24小时内发送条数达到发送限制", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "最近的24小时内发送条数达到发送限制",
				)
			)
			return false
		}
		// 最近的1小时内发送了几条
		val _1HSentItemCount = sentItemLogs.count { it.eventTimeMillis in (currentTime - 60 * 60 * 1000L)..currentTime }
		if (_1HSentItemCount >= NotificationConfig.max1HItem) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 最近的1小时内发送条数达到发送限制", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "最近的1小时内发送条数达到发送限制",
				)
			)
			return false
		}
		// 同一场景上一次成功触发时间(查本地Log)
		val lastSceneSendBatchTime = sentBatchLogs.filter {
			JSONObject(it.paramsJson).optString(LogNotifyParam.scene) == scene
		}.maxOfOrNull { it.eventTimeMillis } ?: 0L
		if (trigger != null && lastSceneSendBatchTime > 0L && currentTime - lastSceneSendBatchTime < trigger.intervalBatch.toLong() * 1000) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 触发时间小于场景通知发送间隔", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "触发时间小于场景通知发送间隔",
				)
			)
			return false
		}

		return true
	}

	suspend fun canSendItem(scene: String) : Boolean {
		if (!canSend(false)) return false
		// 分页读取本地Log，只统计成功发送的通知
		val sentItemLogs = mutableListOf<DBUtil.LocalLog>()
		var beforeId = Long.MAX_VALUE
		while (true) {
			val logs = DBUtil.queryLogs(limit = 500, beforeId = beforeId)
			if (logs.isEmpty()) break
			for (log in logs) {
				if (log.eventName != LogNotifyEvent.notify_send_item) continue
				val params = try {
					JSONObject(log.paramsJson)
				} catch (e: JSONException) {
					continue
				}
				if (params.optBoolean(LogNotifyParam.isSuccess)) {
					sentItemLogs.add(log)
				}
			}
			beforeId = logs.last().id
		}
		val currentTime = System.currentTimeMillis()
		// 最近的24小时内发送了几条
		val _24HSentItemCount = sentItemLogs.count { it.eventTimeMillis in (currentTime - 24 * 60 * 60 * 1000L)..currentTime }
		if (_24HSentItemCount >= NotificationConfig.max24HItem) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 最近的24小时内发送条数达到发送限制", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_item ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "最近的24小时内发送条数达到发送限制",
				)
			)
			return false
		}
		// 最近的1小时内发送了几条
		val _1HSentItemCount = sentItemLogs.count { it.eventTimeMillis in (currentTime - 60 * 60 * 1000L)..currentTime }
		if (_1HSentItemCount >= NotificationConfig.max1HItem) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "${scene}, 最近的1小时内发送条数达到发送限制", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_item ,
				mapOf(
					LogNotifyParam.isSuccess to false,
					LogNotifyParam.scene to scene,
					LogAppParam.msg to "最近的1小时内发送条数达到发送限制",
				)
			)
			return false
		}

		return true
	}

}
