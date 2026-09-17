package com.mar2sdk.core.common.net

import android.os.Build
import android.provider.Settings
import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.CommonConfig
import com.mar2sdk.core.common.PreferenceUtil
import com.mar2sdk.core.log.AdEventReporter
import com.singular.sdk.Singular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NetUtil {
	private const val TAG = "NetUtil"
	private const val DEVICE_ID_KEY = "sf_device_id"
	private const val ACCOUNT_ID_KEY = "sf_temp_uid"
	private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val loginSessions = ConcurrentHashMap<Int, LoginSession>()
	private val attributionSyncs = ConcurrentHashMap<Int, UserAttributionSync>()
	private val client by lazy {
		OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
	}

	/** 统一登录入口：有效缓存走 Token 登录，否则使用原游客身份登录。 */
	fun login(): Job = launchLogin(refresh = false)

	/** 已有登录用户的主动刷新入口，复用有效期判断和失败补偿。 */
	fun refreshUser(): Job = launchLogin(refresh = true)

	private fun launchLogin(refresh: Boolean): Job = networkScope.launch {
		val appID = CommonConfig.serverAppID
		if (appID <= 0) {
			Log.e(TAG, "Login skipped: serverAppID must be a positive integer")
			return@launch
		}
		try {
			val session = loginSessions.getOrPut(appID) {
				LoginSession(
					loadUser = { readLoginUser(appID) },
					restoreUser = { syncLoginIdentity(appID, it) },
					clearUser = { clearLoginUser(appID) },
					tokenLogin = { autoLoginreflushtoken(appID, CommonConfig.serverClientKey, Core.SDK_VERSION) },
					guestLogin = { platformLogin(appID, CommonConfig.serverClientKey, Core.SDK_VERSION) },
				)
			}
			logLoginResult(if (refresh) session.refreshUser() else session.login())
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			logLoginResult(Result.failure(exception))
		}
	}

	/** 网络监听只补偿已经发起过的登录，不会绕过 isAutoLogin 开关启动新登录。 */
	internal fun onNetworkAvailable() {
		AdEventReporter.onNetworkAvailable()
		val session = loginSessions[CommonConfig.serverAppID] ?: return
		networkScope.launch {
			try {
				session.onNetworkAvailable()?.let(::logLoginResult)
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				logLoginResult(Result.failure(exception))
			}
		}
	}

	private fun logLoginResult(result: Result<PlatformLoginUser>) {
		result.onSuccess { user ->
			Log.d(TAG, "Login succeeded: uid=${user.uid}")
		}.onFailure { error ->
			val code = (error as? ServerApiException)?.code
			Log.w(TAG, "Login failed: code=$code, error=${error.javaClass.simpleName}")
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
				saveLoginUser(appID, user)
				Result.success(user)
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				Result.failure(exception)
			}
		}

	/** 读取当前配置产品上次成功登录的用户；是否仍有效由服务端决定。 */
	fun getPlatformLoginUser(): PlatformLoginUser? = readLoginUser(CommonConfig.serverAppID)

	private fun readLoginUser(appID: Int): PlatformLoginUser? {
		PreferenceUtil.init()
		val stored = PreferenceUtil.getString(loginUserKey(appID), "")
		if (stored.isEmpty()) return null
		return runCatching { PlatformLoginProtocol.decodeUser(stored) }.getOrNull()
	}

	private suspend fun saveLoginUser(appID: Int, user: PlatformLoginUser) {
		currentCoroutineContext().ensureActive()
		PreferenceUtil.commitString(loginUserKey(appID), PlatformLoginProtocol.encodeUser(user))
		syncLoginIdentity(appID, user)
		if (appID == CommonConfig.serverAppID) {
			attributionSync(appID).onLogin(user.uid)
			uploadUser(appID)
		}
	}

	private suspend fun clearLoginUser(appID: Int) {
		currentCoroutineContext().ensureActive()
		// 一旦删除凭据，必须同时清除分析身份；取消仍可阻止后续网络重登。
		withContext(NonCancellable) {
			attributionSyncs[appID]?.clearUser()
			PreferenceUtil.removeByKey(loginUserKey(appID))
			syncLoginIdentity(appID, null)
		}
	}

	private suspend fun syncLoginIdentity(appID: Int, user: PlatformLoginUser?) {
		withContext(Dispatchers.Main) {
			if (appID != CommonConfig.serverAppID) return@withContext
			// 分析 SDK 的故障不改变已成功的服务端登录结果。
			try {
				if (user == null) TDAnalytics.logout() else TDAnalytics.login(user.uid.toString())
			} catch (exception: Exception) {
				Log.w(TAG, "Unable to set ThinkingData login identity")
			}
			try {
				if (user == null) Singular.unsetCustomUserId() else Singular.setCustomUserId(user.uid.toString())
			} catch (exception: Exception) {
				Log.w(TAG, "Unable to set Singular login identity")
			}
			try {
				if (user == null) AdEventReporter.onLogout(appID) else AdEventReporter.onLogin(appID, user.uid)
			} catch (exception: Exception) {
				Log.w(TAG, "Unable to restore ad reporting identity")
			}
		}
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

	internal suspend fun requestUploadUser(
		request: Request,
		callFactory: Call.Factory = client,
	): Unit = executeRequest(request, callFactory, UploadUserProtocol::parseResponse)

	internal suspend fun requestReport(
		request: Request,
		callFactory: Call.Factory = client,
	): Unit = executeRequest(request, callFactory, ReportProtocol::parseResponse)

	internal suspend fun requestAutoLoginreflushtoken(
		request: Request,
		expectedUid: Long,
		callFactory: Call.Factory = client,
	): PlatformLoginUser = executeRequest(request, callFactory) {
		AutoLoginProtocol.parseResponse(it, System.currentTimeMillis(), expectedUid)
	}

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
			Log.e(TAG, "SDK initialization report succeeded")
		} catch (exception: CancellationException) {
			Log.e(TAG, "initLog: ", exception)
		} catch (exception: Exception) {
			val code = (exception as? ServerApiException)?.code
			Log.w(TAG, "SDK initialization report failed: code=$code, error=${exception.javaClass.simpleName}")
		}
	}

	/** 使用当前产品配置和已保存的凭据异步执行 Token 登录或刷新。 */
	fun autoLoginreflushtoken(): Job = networkScope.launch {
		autoLoginreflushtoken(CommonConfig.serverAppID, CommonConfig.serverClientKey, Core.SDK_VERSION)
			.onSuccess { user ->
				Log.d(TAG, "Token login succeeded: uid=${user.uid}")
			}
			.onFailure { error ->
				val code = (error as? ServerApiException)?.code
				Log.w(TAG, "Token login failed: code=$code, error=${error.javaClass.simpleName}")
			}
	}

	/**
	 * 使用指定产品已保存的 uid/token 登录并更新凭据；不生成新游客身份。
	 * 无缓存或请求失败通过 Result 返回，保留原用户；取消继续向上传递。
	 * 此接口需要旧 token 通过服务端校验，成功返回的 token 可能与原值相同。
	 */
	suspend fun autoLoginreflushtoken(
		appID: Int,
		clientKey: String,
		sdkVersion: String,
	): Result<PlatformLoginUser> = withContext(Dispatchers.IO) {
		try {
			require(appID > 0) { "appID must be positive" }
			require(clientKey.isNotBlank()) { "clientKey is required" }
			require(sdkVersion.isNotBlank()) { "sdkVersion is required" }
			val savedUser = readLoginUser(appID)
				?: throw IllegalStateException("No saved login credentials; call platformLogin first")
			val request = AutoLoginProtocol.createRequest(
				url = requestUrl(CommonConfig.autoLoginreflushtokenPath),
				info = requestInfo(appID, sdkVersion, deviceIdentifier()),
				user = savedUser,
				clientKey = clientKey,
			)
			val user = requestAutoLoginreflushtoken(request, savedUser.uid)
			saveLoginUser(appID, user)
			Result.success(user)
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			Result.failure(exception)
		}
	}

	/** 使用当前产品本次成功登录的 uid 上报已持久化的 Singular 归因；可取消返回的 Job。 */
	fun uploadUser(): Job = uploadUser(CommonConfig.serverAppID)

	private fun uploadUser(appID: Int): Job = networkScope.launch {
		try {
			require(appID > 0) { "appID must be positive" }
			PreferenceUtil.init()
			attributionSync(appID).sync()
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			logUploadUserFailure(exception)
		}
	}

	/** 完整快照一次写入，避免读取到半更新字段；先于登录到达时留待登录成功后同步。 */
	internal fun onSingularAttribution(attribution: JSONObject) {
		val network = attribution.opt("network")
		if (network == null || network == JSONObject.NULL || network.toString().isBlank()) return
		val appID = CommonConfig.serverAppID
		if (appID <= 0) return
		PreferenceUtil.init()
		PreferenceUtil.commitString(attributionKey(appID), attribution.toString())
		uploadUser(appID)
	}

	private fun attributionKey(appID: Int) = "mar2sdk.singular_attribution.$appID"

	private fun readAttribution(appID: Int): JSONObject? {
		val stored = PreferenceUtil.getString(attributionKey(appID), "")
		return if (stored.isEmpty()) null else JSONObject(stored)
	}

	private fun attributionSync(appID: Int): UserAttributionSync = attributionSyncs.getOrPut(appID) {
		UserAttributionSync(
			loadAttribution = { readAttribution(appID) },
			upload = { uid, attribution ->
				require(appID == CommonConfig.serverAppID) { "Attribution product no longer matches current configuration" }
				val request = UploadUserProtocol.createRequest(
					url = requestUrl(CommonConfig.uploadUserPath),
					info = requestInfo(appID, Core.SDK_VERSION, deviceIdentifier()),
					uid = uid,
					attribution = attribution,
					clientKey = CommonConfig.serverClientKey,
				)
				requestUploadUser(request)
				Log.d(TAG, "User attribution report succeeded: uid=$uid")
			},
			onFailure = ::logUploadUserFailure,
		)
	}

	private fun logUploadUserFailure(exception: Exception) {
		val code = (exception as? ServerApiException)?.code
		Log.w(TAG, "User attribution report failed: code=$code, error=${exception.javaClass.simpleName}")
	}

	/**
	 * 批量上报完整的数数广告事件，单条也使用 JSONArray；调用前须完成登录。
	 * 缺少 #account_id 时补充当前产品已登录用户的 uid，已有账号必须与该用户一致。
	 * 返回值可取消，通过 await() 取得 Result；成功仅表示服务端接受了整批数据。
	 * 调用方负责保留失败批次，重试时复用原 #uuid、#event_id 和采集属性。
	 */
	fun report(events: JSONArray): Deferred<Result<Unit>> = launchReport(events)

	/** 队列必须使用采集时的身份，防止在检查身份后、创建请求前切换产品或账号。 */
	internal fun report(events: JSONArray, appID: Int, uid: Long): Deferred<Result<Unit>> =
		launchReport(events, appID, uid)

	private fun launchReport(
		events: JSONArray,
		expectedAppID: Int? = null,
		expectedUid: Long? = null,
	): Deferred<Result<Unit>> {
		// 在切换线程前固定事件、产品配置和账号，避免后续修改或切换账号影响本次请求。
		val snapshot = events.toString()
		val appID = CommonConfig.serverAppID
		val clientKey = CommonConfig.serverClientKey
		val url = requestUrl(ReportProtocol.PATH)
		val loginUser = runCatching {
			require(appID > 0) { "appID must be positive" }
			require(expectedAppID == null || appID == expectedAppID) { "Report product no longer matches current configuration" }
			val user = readLoginUser(appID)
				?: throw IllegalStateException("No saved login credentials; call login first")
			require(expectedUid == null || user.uid == expectedUid) { "Report user no longer matches current login" }
			user
		}
		return networkScope.async {
			try {
				val user = loginUser.getOrThrow()
				val request = ReportProtocol.createRequest(
					url = url,
					info = requestInfo(appID, Core.SDK_VERSION, deviceIdentifier()),
					clientKey = clientKey,
					uid = user.uid,
					packageName = Core.app.packageName,
					events = JSONArray(snapshot),
				)
				requestReport(request)
				Log.d(TAG, "Ad event report accepted")
				Result.success(Unit)
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				val code = (exception as? ServerApiException)?.code
				Log.w(TAG, "Ad event report failed: code=$code, error=${exception.javaClass.simpleName}")
				Result.failure(exception)
			}
		}
	}

}
