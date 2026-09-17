package com.mar2sdk.core.common.net

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 统一处理启动登录、主动刷新和网络恢复；底层登录方法负责保存成功结果。 */
internal class LoginSession(
	private val loadUser: () -> PlatformLoginUser?,
	private val restoreUser: suspend (PlatformLoginUser) -> Unit,
	private val clearUser: suspend () -> Unit,
	private val tokenLogin: suspend () -> Result<PlatformLoginUser>,
	private val guestLogin: suspend () -> Result<PlatformLoginUser>,
	private val now: () -> Long = System::currentTimeMillis,
) {
	private val mutex = Mutex()
	private var pendingLogin = false

	suspend fun login(): Result<PlatformLoginUser> = mutex.withLock {
		performLogin()
	}

	suspend fun refreshUser(): Result<PlatformLoginUser> = mutex.withLock {
		performLogin(requireUser = true)
	}

	/** 只有前次请求需要补偿时才重试，连续网络事件不会重复刷新已成功的登录。 */
	suspend fun onNetworkAvailable(): Result<PlatformLoginUser>? = mutex.withLock {
		if (pendingLogin) performLogin() else null
	}

	private suspend fun performLogin(requireUser: Boolean = false): Result<PlatformLoginUser> {
		pendingLogin = false
		val cached = loadUser()
		if (requireUser && cached == null) {
			return Result.failure(IllegalStateException("No saved login credentials; call login first"))
		}
		if (!isLocallyValid(cached, now())) {
			if (cached != null) clearUser()
			return finish(guestLogin())
		}

		// 离线启动也恢复已验证过的身份；不改写 loginTime 或延长本地有效期。
		restoreUser(cached!!)
		val result = tokenLogin()
		val error = result.exceptionOrNull()
		if (error is ServerApiException && error.code > 0 && error.code != SERVER_ERROR) {
			clearUser()
			// 只对已确认的凭据失效码回退，未知业务码（包括退款封禁）不自动重登。
			if (error.code == TOKEN_INVALID && cached.accountType == GUEST_ACCOUNT) {
				return finish(guestLogin())
			}
		}
		return finish(result)
	}

	private fun finish(result: Result<PlatformLoginUser>): Result<PlatformLoginUser> {
		pendingLogin = when (val error = result.exceptionOrNull()) {
			is ServerApiException -> error.code <= 0 || error.code == SERVER_ERROR
			is IOException -> true
			else -> false
		}
		return result
	}

	companion object {
		private const val TOKEN_INVALID = 1002
		private const val SERVER_ERROR = 1025
		private const val GUEST_ACCOUNT = 1

		internal fun isLocallyValid(user: PlatformLoginUser?, now: Long): Boolean {
			if (user == null || user.uid <= 0 || user.token.isBlank() || user.expiredTime <= 60) return false
			if (user.loginTime <= 0 || now < user.loginTime) return false
			// 先转秒再比较，避免将服务端有效期乘以 1000 时溢出。
			return (now - user.loginTime) / 1000 < user.expiredTime - 60
		}
	}
}
