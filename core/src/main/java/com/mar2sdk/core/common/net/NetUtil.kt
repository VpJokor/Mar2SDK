package com.mar2sdk.core.common.net

import android.os.Build
import android.provider.Settings
import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.CommonConfig
import com.mar2sdk.core.common.PreferenceUtil
import com.singular.sdk.Singular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
	private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val client by lazy {
		OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
	}

	/** 使用产品配置异步登录游客；由 Core.init 调用，返回的 Job 可用于取消或等待。 */
	fun login(): Job = networkScope.launch {
		val appID = CommonConfig.serverAppID
		if (appID <= 0) {
			Log.e(TAG, "Guest login skipped: serverAppID must be a positive integer")
			return@launch
		}
		platformLogin(appID, CommonConfig.serverClientKey, Core.SDK_VERSION)
			.onSuccess { user ->
				Log.e(TAG, "Guest login succeeded: uid=${user.uid}")
			}
			.onFailure { error ->
				val code = (error as? ServerApiException)?.code
				Log.e(TAG, "Guest login failed: code=$code, error=${error.javaClass.simpleName}")
			}
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
				val request = PlatformLoginProtocol.createRequest(
					url = requestUrl(CommonConfig.platformLoginPath),
					info = requestInfo(appID, sdkVersion, deviceID),
					accountID = accountID,
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
	fun getPlatformLoginUser(): PlatformLoginUser? {
		PreferenceUtil.init()
		val appID = CommonConfig.serverAppID
		val stored = PreferenceUtil.getString(loginUserKey(appID), "")
		if (stored.isEmpty()) return null
		return runCatching { PlatformLoginProtocol.decodeUser(stored) }.getOrNull()
	}

	@Synchronized
	private fun guestIdentifiers(): Pair<String, String> {
		val deviceID = deviceIdentifier()
		val accountID = PreferenceUtil.getString(ACCOUNT_ID_KEY, "").takeIf { it.isNotBlank() }
			?: ServerApiProtocol.md5(deviceID).also { PreferenceUtil.commitString(ACCOUNT_ID_KEY, it) }
		return deviceID to accountID
	}

	@Synchronized
	private fun deviceIdentifier(): String =
		PreferenceUtil.getString(DEVICE_ID_KEY, "").takeIf { it.isNotBlank() }
			?: (Settings.Secure.getString(Core.app.contentResolver, Settings.Secure.ANDROID_ID)
				?.takeIf { it.isNotBlank() } ?: ServerApiProtocol.md5(UUID.randomUUID().toString()))
				.also { PreferenceUtil.commitString(DEVICE_ID_KEY, it) }

	private fun requestInfo(appID: Int, sdkVersion: String, deviceID: String): ServerRequestInfo {
		val app = Core.app
		val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
		val now = System.currentTimeMillis()
		return ServerRequestInfo(
			appID = appID,
			appVersion = packageInfo.versionName?.takeIf { it.isNotBlank() } ?: packageInfo.longVersionCode.toString(),
			sdkVersion = sdkVersion,
			deviceID = deviceID,
			deviceTime = now,
			zoneOffset = BigDecimal.valueOf(TimeZone.getDefault().getOffset(now).toLong())
				.divide(BigDecimal.valueOf(3_600_000)).stripTrailingZeros().toPlainString(),
			language = app.resources.configuration.locales[0]?.toLanguageTag() ?: Locale.getDefault().toLanguageTag(),
		)
	}

	private fun requestUrl(path: String) = CommonConfig.serverUrl.trimEnd('/') + "/" + path.trimStart('/')

	private fun loginUserKey(appID: Int) = "mar2sdk.login_user.$appID"

	internal suspend fun requestPlatformLogin(
		request: Request,
		callFactory: Call.Factory = client,
	): PlatformLoginUser = executeRequest(request, callFactory) {
		PlatformLoginProtocol.parseResponse(it, System.currentTimeMillis())
	}

	internal suspend fun requestInitLog(
		request: Request,
		callFactory: Call.Factory = client,
	): Unit = executeRequest(request, callFactory, InitLogProtocol::parseResponse)

	private suspend fun <T> executeRequest(
		request: Request,
		callFactory: Call.Factory,
		parseResponse: (String) -> T,
	): T = suspendCancellableCoroutine { continuation ->
		val call = callFactory.newCall(request)
		continuation.invokeOnCancellation { call.cancel() }
		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				continuation.resumeWithException(e)
			}

			override fun onResponse(call: Call, response: Response) {
				val result = runCatching {
					response.use {
						if (!it.isSuccessful) throw IOException("Server API HTTP ${it.code}")
						val body = it.body?.string() ?: throw IOException("Empty server response")
						parseResponse(body)
					}
				}
				result.fold(continuation::resume, continuation::resumeWithException)
			}
		})
	}

	/** 异步上报本次 SDK 初始化的设备信息，无需登录；可取消返回的 Job。 */
	fun initLog(): Job = networkScope.launch {
		try {
			PreferenceUtil.init()
			val request = InitLogProtocol.createRequest(
				url = requestUrl(CommonConfig.initLogPath),
				info = requestInfo(CommonConfig.serverAppID, Core.SDK_VERSION, deviceIdentifier()),
				clientKey = CommonConfig.serverClientKey,
				deviceType = Build.MODEL.orEmpty(),
				deviceDpi = Core.app.resources.displayMetrics.densityDpi.toString(),
			)
			requestInitLog(request)
			Log.d(TAG, "SDK initialization report succeeded")
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			val code = (exception as? ServerApiException)?.code
			Log.w(TAG, "SDK initialization report failed: code=$code, error=${exception.javaClass.simpleName}")
		}
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
