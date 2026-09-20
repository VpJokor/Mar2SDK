package com.mar2sdk.core.notify.app

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.MainThread
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.common.status.UserType
import com.mar2sdk.core.log.LogAppParam
import com.mar2sdk.core.log.LogNotifyEvent
import com.mar2sdk.core.log.LogNotifyParam
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.common.DBUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONException
import org.json.JSONObject

/**
 * 通知触发场景的批次等待队列 的 通知批次
 * scene 触发场景
 * timeAt 到期时间，基于 SystemClock.elapsedRealtime()，单位毫秒
 */
data class NotificationBatch(val scene: String, val timeAt: Long)

/**
 * 正在发送通知的等待队列 的 通知项
 * scene 触发场景
 * timeAt 到期时间，基于 SystemClock.elapsedRealtime()，单位毫秒
 */
data class NotificationItem(val scene: String, val timeAt: Long)

object AppNotificationManager {
	const val TAG = "AppNotificationManager"

	private val loopScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var loopJob: Job? = null
	private val sendMutex = Mutex()

	// 通知触发场景的批次等待队列
	private val waitBatchQueue = mutableListOf<NotificationBatch>()

	// 正在发送通知的等待队列
	private val sendingQueue = mutableListOf<NotificationItem>()

	@MainThread
	fun init() {
		startLoop()
		startTimer()
	}

	// 将定时通知交给系统，即使进程退出也能触发；省电模式下可能延迟。
	@MainThread
	fun startTimer() {
		checkMainThread()
		NotificationAlarmScheduler.start()
	}

	// 启动进程内的通知循环；重复启动不会创建额外任务。
	@MainThread
	fun startLoop() {
		checkMainThread()
		if (loopJob?.isActive == true) return
		loopJob = loopScope.launch {
			while (isActive) {
				try {
					processDueQueues()
				} catch (exception: CancellationException) {
					throw exception
				} catch (exception: Exception) {
					Log.e(TAG, "Failed to process notification queue", exception)
				}
				delay(1_000L)
			}
		}
	}

	/** 取消发送循环和定时器并丢弃待发任务；调用 init 可重新启动两者。 */
	@MainThread
	fun stopLoop() {
		checkMainThread()
		NotificationAlarmScheduler.stop()
		loopJob?.cancel()
		loopJob = null
		clears()
	}

	/** 零延迟批次也只入队，所有发送检查和发送操作都由循环串行执行。 */
	suspend fun addBatch(scene: String): Unit = withContext(Dispatchers.Main.immediate) {
		val trigger = NotificationConfig.triggers[scene] ?: return@withContext
		if (trigger.count <= 0) return@withContext
		waitBatchQueue.add(
			NotificationBatch(scene, SystemClock.elapsedRealtime() + trigger.delay.coerceAtLeast(0) * 1_000L)
		)
	}

	private suspend fun processDueQueues() {
		while (true) {
			currentCoroutineContext().ensureActive()
			val now = SystemClock.elapsedRealtime()
			val batch = waitBatchQueue.minByOrNull { it.timeAt }
			val item = sendingQueue.minByOrNull { it.timeAt }
			// 两个队列按到期时间合并处理；同一时刻先检查批次，保留忙时拒绝新批次的规则。
			when {
				batch != null && batch.timeAt <= now && (item == null || batch.timeAt <= item.timeAt) -> {
					waitBatchQueue.remove(batch)
					sendBatch(batch.scene)
				}
				item != null && item.timeAt <= now -> {
					sendingQueue.remove(item)
					send(item.scene)
				}
				else -> return
			}
			// 即使检查没有挂起，也让主线程有机会处理停止或新增批次。
			yield()
		}
	}

	// 发送一批通知
	private suspend fun sendBatch(scene: String) {
		if (!canSendBatch(scene)) return
		currentCoroutineContext().ensureActive()

		val trigger = NotificationConfig.triggers[scene] ?: return
		if (trigger.count <= 0) return

		// 批次中的通知按场景配置的单条间隔排队。
		val baseTime = SystemClock.elapsedRealtime()
		repeat(trigger.count) { index ->
			sendingQueue.add(
				NotificationItem(
					scene = scene,
					timeAt = baseTime + index.toLong() * trigger.intervalItem.coerceAtLeast(0) * 1_000L
				)
			)
		}

		Core.log(LogNotifyEvent.notify_send_batch, mapOf(LogNotifyParam.isSuccess to true, LogNotifyParam.scene to scene))
	}

	/** 清空待发任务，并取消尚未完成的检查；已运行的循环会继续等待新任务。 */
	@MainThread
	fun clears() {
		checkMainThread()
		val restartLoop = loopJob?.isActive == true
		loopJob?.cancel()
		loopJob = null
		waitBatchQueue.clear()
		sendingQueue.clear()
		if (Core.appMod == AppMod.DEBUG) {
			Toast.makeText(Core.app, "清空待发送队列", Toast.LENGTH_LONG).show()
		}
		Core.log(LogNotifyEvent.clear_notifications, mapOf())
		if (restartLoop) startLoop()
	}

	@MainThread
	internal suspend fun sendTimerItem(scene: String) {
		checkMainThread()
		send(scene)
	}

	private suspend fun send(scene: String) = sendMutex.withLock {
		if (!canSendItem(scene)) return@withLock
		currentCoroutineContext().ensureActive()
		AppNotificationUtil.sendNotificationContent(scene)
		Core.log(LogNotifyEvent.notify_send_item, mapOf(LogNotifyParam.isSuccess to true, LogNotifyParam.scene to scene))
	}

	// 通知发送限制
	@MainThread
	fun canSend(isBatch: Boolean) : Boolean {
		checkMainThread()
		if (Core.userType == UserType.RISK) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "风险用户不发通知", Toast.LENGTH_LONG).show()
			}
		}
		if (!NotificationConfig.isSend) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "APP通知总开关没开不发通知", Toast.LENGTH_LONG).show()
			}
			Core.log(
				if (isBatch) LogNotifyEvent.notify_send_batch else LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "APP通知总开关没开不发通知",)
			)
			return false
		}
		if ((!NotificationConfig.isForgroundSend) && AppStatus.isForeground) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "APP在前台不发通知", Toast.LENGTH_LONG).show()
			}
			Core.log(
				if (isBatch) LogNotifyEvent.notify_send_batch else LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "APP在前台不发通知",)
			)
			return false
		}
		if ((!NotificationConfig.isScreenOffSend) && (!AppStatus.isScreenOn)) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "手机熄屏不发通知", Toast.LENGTH_LONG).show()
			}
			Core.log(
				if (isBatch) LogNotifyEvent.notify_send_batch else LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "手机熄屏不发通知",)
			)
			return false
		}
		if ((!NotificationConfig.isScreenLockSend) && AppStatus.isLocked) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "手机锁屏不发通知", Toast.LENGTH_LONG).show()
			}
			Core.log(
				if (isBatch) LogNotifyEvent.notify_send_batch else LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "手机锁屏不发通知",)
			)
			return false
		}
		return true
	}

	//发送批次限制
	@MainThread
	suspend fun canSendBatch(scene: String) : Boolean {
		if (!canSend(true)) return false
		val currentTime = System.currentTimeMillis()
		if (sendingQueue.isNotEmpty()) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "有通知正在发送，不发新批次通知", Toast.LENGTH_LONG).show()
			}
			Core.log(
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
			Core.log(
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
			Core.log(
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
			Core.log(
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
			Core.log(
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
			Core.log(
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
			Core.log(
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
			Core.log(
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

	@MainThread
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
			Core.log(
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
			Core.log(
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

	private fun checkMainThread() {
		check(Looper.myLooper() == Looper.getMainLooper()) {
			"AppNotificationManager must be accessed on the main thread"
		}
	}

}
