package com.mar2sdk.core.log

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal const val AD_REPORT_MAX_EVENT_BYTES = 64 * 1024
internal const val AD_REPORT_MAX_BATCH_BYTES = 256 * 1024

internal data class AdReportIdentity(val appID: Int, val uid: Long)

internal data class PendingAdReport(
	val id: String,
	val identity: AdReportIdentity,
	val data: String,
)

internal interface AdReportStorage {
	fun insert(event: PendingAdReport)

	/** 字节上限包含 JSON 数组括号和分隔符占用的 UTF-8 字节。 */
	fun peek(identity: AdReportIdentity, limit: Int, maxBytes: Int): List<PendingAdReport>

	fun remove(ids: List<String>)
}

/** 事件先持久化，再交由唯一的调度协程串行批量上报。 */
internal class AdReportQueue(
	private val scope: CoroutineScope,
	private val storage: AdReportStorage,
	private val currentIdentity: () -> AdReportIdentity?,
	private val send: suspend (AdReportIdentity, JSONArray) -> Result<Unit>,
	private val onFailure: (Throwable) -> Unit = {},
	private val batchSize: () -> Int = { 20 },
	private val flushDelayMillis: () -> Long = { 5_000 },
	private val retryDelaysMillis: List<Long> = listOf(1_000, 2_000),
	private val cooldownMillis: Long = 60_000,
) {
	private val storageMutex = Mutex()
	private val requests = Channel<Boolean>(Channel.UNLIMITED)
	private var cooldown: Job? = null

	init {
		require(batchSize() > 0)
		require(flushDelayMillis() >= 0 && cooldownMillis >= 0)
		require(retryDelaysMillis.all { it >= 0 })
		scope.launch {
			for (immediate in requests) {
				try {
					if (!immediate) awaitBatchWindow()
					cooldown?.join()
					// 入队触发的请求均在持久化完成后发出，下一次读取会包含对应事件。
					while (requests.tryReceive().isSuccess) Unit
					flushAvailable()
				} catch (error: CancellationException) {
					// 单次发送取消时保留批次，继续接收后续上报请求。
					// 调度协程或所属作用域被取消时，仍须向外传播取消。
					coroutineContext.ensureActive()
				} catch (error: Exception) {
					onFailure(error)
					startCooldown()
				}
			}
		}
	}

	fun enqueue(event: PendingAdReport): Job = scope.launch {
		try {
			require(event.data.toByteArray(Charsets.UTF_8).size <= AD_REPORT_MAX_EVENT_BYTES) {
				"Ad event exceeds the 64 KB limit"
			}
			JSONObject(event.data)
			val fullBatch = storageMutex.withLock {
				storage.insert(event)
				val identity = currentIdentity()
				val limit = batchSize().also { require(it > 0) }
				identity != null && storage.peek(identity, limit, Int.MAX_VALUE).size >= limit
			}
			requests.send(fullBatch)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			onFailure(error)
		}
	}

	/** 登录或网络恢复后调用，同时恢复上次进程持久化的待发批次。 */
	fun flush(): Job = scope.launch { requests.send(true) }

	private suspend fun awaitBatchWindow() = coroutineScope {
		val intervalMillis = flushDelayMillis().also { require(it >= 0) }
		val timer = async { delay(intervalMillis) }
		try {
			while (true) {
				val ready = select<Boolean> {
					timer.onAwait { true }
					requests.onReceive { it }
				}
				if (ready) break
			}
		} finally {
			timer.cancel()
		}
	}

	private suspend fun flushAvailable() {
		while (true) {
			val identity = currentIdentity() ?: return
			val batch = storageMutex.withLock {
				val limit = batchSize().also { require(it > 0) }
				storage.peek(identity, limit, AD_REPORT_MAX_BATCH_BYTES)
			}
			if (batch.isEmpty()) return
			val body = JSONArray().apply { batch.forEach { put(JSONObject(it.data)) } }.toString()
			require(body.toByteArray(Charsets.UTF_8).size <= AD_REPORT_MAX_BATCH_BYTES)
			var accepted = false
			for (attempt in 0..retryDelaysMillis.size) {
				coroutineContext.ensureActive()
				if (currentIdentity() != identity) return
				val result = try {
					// 每次尝试都重建发送对象，避免发送方修改后续重试的数据。
					send(identity, JSONArray(body))
				} catch (error: CancellationException) {
					throw error
				} catch (error: Exception) {
					Result.failure(error)
				}
				coroutineContext.ensureActive()
				val error = result.exceptionOrNull()
				if (error == null) {
					accepted = true
					break
				}
				if (error is CancellationException) throw error
				onFailure(error)
				if (attempt < retryDelaysMillis.size) delay(retryDelaysMillis[attempt])
			}
			if (!accepted) {
				startCooldown()
				return
			}
			storageMutex.withLock { storage.remove(batch.map { it.id }) }
		}
	}

	private fun startCooldown() {
		// 冷却结束不会自动重试，须由新事件、登录或网络恢复再次触发。
		cooldown = scope.launch { delay(cooldownMillis) }
	}
}
