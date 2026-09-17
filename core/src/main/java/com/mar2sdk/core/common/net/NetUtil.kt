package com.mar2sdk.core.common.net

import android.provider.Settings
import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.CommonConfig
import com.mar2sdk.core.common.PreferenceUtil
import com.singular.sdk.Singular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NetUtil {
	private const val TAG = "NetUtil"
	private const val DEVICE_ID_KEY = "sf_device_id"
	private const val ACCOUNT_ID_KEY = "sf_temp_uid"
	private val client by lazy {
		OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
	}

	/**
	 * Google Play Android 游客登录；首次调用同时完成注册。先调用 Core.init。
	 * appID/clientKey 来自产品后台，sdkVersion 为当前 SDK 版本；不会硬编码文档示例凭据。
	 * 失败通过 Result 返回，业务失败为 ServerApiException；协程取消正常向上传递。
	 */
	suspend fun platformLogin(appID: Int, clientKey: String, sdkVersion: String): Result<PlatformLoginUser> =
		withContext(Dispatchers.IO) {
			try {
				require(appID > 0) { "appID must be positive" }
				require(clientKey.isNotBlank()) { "clientKey is required" }
				require(sdkVersion.isNotBlank()) { "sdkVersion is required" }
				PreferenceUtil.init()
				val (deviceID, accountID) = guestIdentifiers()
				val app = Core.app
				val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
				val now = System.currentTimeMillis()
				val request = PlatformLoginProtocol.createRequest(
					url = CommonConfig.serverUrl.trimEnd('/') + "/" + CommonConfig.platformLoginPath.trimStart('/'),
					info = PlatformLoginRequestInfo(
						appID = appID,
						appVersion = packageInfo.versionName?.takeIf { it.isNotBlank() } ?: packageInfo.longVersionCode.toString(),
						sdkVersion = sdkVersion,
						deviceID = deviceID,
						accountID = accountID,
						deviceTime = now,
						zoneOffset = BigDecimal.valueOf(TimeZone.getDefault().getOffset(now).toLong())
							.divide(BigDecimal.valueOf(3_600_000)).stripTrailingZeros().toPlainString(),
						language = app.resources.configuration.locales[0]?.toLanguageTag() ?: Locale.getDefault().toLanguageTag(),
					),
					clientKey = clientKey,
				)
				val user = requestPlatformLogin(request)
				currentCoroutineContext().ensureActive()
				PreferenceUtil.commitString(loginUserKey(appID), PlatformLoginProtocol.encodeUser(user))
				withContext(Dispatchers.Main) {
					// 归因平台故障不应把已成功的服务端登录变为登录失败。
					try {
						TDAnalytics.login(user.uid.toString())
					} catch (exception: Exception) {
						Log.w(TAG, "Unable to set ThinkingData login identity")
					}
					try {
						Singular.setCustomUserId(user.uid.toString())
					} catch (exception: Exception) {
						Log.w(TAG, "Unable to set Singular login identity")
					}
				}
				Result.success(user)
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				Result.failure(exception)
			}
		}

	/** 读取指定产品上次成功登录的用户；是否仍有效由服务端决定。 */
	fun getPlatformLoginUser(appID: Int): PlatformLoginUser? {
		PreferenceUtil.init()
		val stored = PreferenceUtil.getString(loginUserKey(appID), "")
		if (stored.isEmpty()) return null
		return runCatching { PlatformLoginProtocol.decodeUser(stored) }.getOrNull()
	}

	@Synchronized
	private fun guestIdentifiers(): Pair<String, String> {
		val deviceID = PreferenceUtil.getString(DEVICE_ID_KEY, "").takeIf { it.isNotBlank() }
			?: (Settings.Secure.getString(Core.app.contentResolver, Settings.Secure.ANDROID_ID)
				?.takeIf { it.isNotBlank() } ?: PlatformLoginProtocol.md5(UUID.randomUUID().toString()))
		val accountID = PreferenceUtil.getString(ACCOUNT_ID_KEY, "").takeIf { it.isNotBlank() }
			?: PlatformLoginProtocol.md5(deviceID)
		PreferenceUtil.commitStrings(mapOf(DEVICE_ID_KEY to deviceID, ACCOUNT_ID_KEY to accountID))
		return deviceID to accountID
	}

	private fun loginUserKey(appID: Int) = "mar2sdk.login_user.$appID"

	internal suspend fun requestPlatformLogin(
		request: Request,
		callFactory: Call.Factory = client,
	): PlatformLoginUser = suspendCancellableCoroutine { continuation ->
		val call = callFactory.newCall(request)
		continuation.invokeOnCancellation { call.cancel() }
		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				continuation.resumeWithException(e)
			}

			override fun onResponse(call: Call, response: Response) {
				val result = runCatching {
					response.use {
						if (!it.isSuccessful) throw IOException("Platform login HTTP ${it.code}")
						val body = it.body?.string() ?: throw IOException("Empty platform login response")
						PlatformLoginProtocol.parseResponse(body, System.currentTimeMillis())
					}
				}
				result.fold(continuation::resume, continuation::resumeWithException)
			}
		})
	}

	// TODO: 日志上报
	fun initLog() {

	}

	// TODO: 刷新Token
	fun autoLoginreflushtoken() {

	}

	// TODO: 用户信息上报
	fun uploadUser() {

	}

	// TODO: 事件上报
	fun report() {

	}
}
