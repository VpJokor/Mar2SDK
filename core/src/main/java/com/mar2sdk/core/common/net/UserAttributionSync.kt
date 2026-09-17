package com.mar2sdk.core.common.net

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** 仅在本次会话登录成功且归因已保存时同步；失败保留归因供后续触发重试。 */
internal class UserAttributionSync(
	private val loadAttribution: () -> JSONObject?,
	private val upload: suspend (Long, JSONObject) -> Unit,
	private val onFailure: (Exception) -> Unit,
) {
	private val mutex = Mutex()
	@Volatile private var uid: Long? = null

	fun onLogin(uid: Long) {
		this.uid = uid
	}

	fun clearUser() {
		uid = null
	}

	suspend fun sync() = mutex.withLock {
		val userId = uid ?: return@withLock
		repeat(3) { attempt ->
			currentCoroutineContext().ensureActive()
			if (uid != userId) return@withLock
			try {
				val attribution = loadAttribution() ?: return@withLock
				if (uid != userId) return@withLock
				upload(userId, attribution)
				return@withLock
			} catch (error: CancellationException) {
				throw error
			} catch (error: Exception) {
				onFailure(error)
				if (error !is IOException || attempt == 2) return@withLock
			}
			delay((attempt + 1) * 1_000L)
		}
	}
}
