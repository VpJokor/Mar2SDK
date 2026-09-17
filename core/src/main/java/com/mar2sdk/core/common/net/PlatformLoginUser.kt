package com.mar2sdk.core.common.net

import java.io.IOException

/** 游客或 Token 登录成功后返回并持久化的用户；时间戳为毫秒，expiredTime 为秒。 */
data class PlatformLoginUser(
	val uid: Long,
	val name: String,
	val loginName: String,
	val displayType: Int,
	val token: String,
	val expiredTime: Long,
	val registerTime: Long,
	val countryCode: String?,
	val accountType: Int,
	val newAccount: Int,
	val loginTime: Long,
) {
	override fun toString(): String = "PlatformLoginUser(uid=$uid, accountType=$accountType, newAccount=$newAccount)"
}

/** HTTP 成功但服务端 code 非零；保留业务码供调用方区分验签、封禁等失败。 */
class ServerApiException(val code: Int, message: String) : IOException(message)
