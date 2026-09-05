package com.mar2sdk.core.common

import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.status.RiskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Play Integrity token 请求工具。
 *
 * 负责准备 StandardIntegrityTokenProvider、生成 requestHash、请求 token，并交给服务端解析校验。
 */
object PlayIntegrityUtil {
	private const val TAG = "PlayIntegrityHelper"

	/**
	 * 发起 Play Integrity 标准请求流程。
	 *
	 * Google Cloud Project Number 由 SDK 配置提供；token 获取成功后异步发送给服务端。
	 */
	fun requestPlayIntegrity() {
		val cloudProjectNumber = PolicyConfig.PlayIntegrityID
		if (cloudProjectNumber <= 0L) {
			Log.w(TAG, "requestPlayIntegrity: missing cloud project number")
			return
		}

		val standardIntegrityManager = IntegrityManagerFactory.createStandard(Core.app)
		val request = StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
			.setCloudProjectNumber(cloudProjectNumber)
			.build()

		standardIntegrityManager.prepareIntegrityToken(request)
			.addOnSuccessListener tokenProviderReady@{ tokenProvider ->
				// requestHash 用于把本次客户端请求和服务端解析结果关联起来。
				val input = "${System.currentTimeMillis()}-${Math.random()}"
				val requestHash = generateRequestHash(input)
				if (requestHash == null) {
					Log.e(TAG, "requestHash 生成失败")
					return@tokenProviderReady
				}

				tokenProvider.request(
					StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
						.setRequestHash(requestHash)
						.build(),
				)
					.addOnSuccessListener { response -> checkToken(response.token()) }
					.addOnFailureListener(::handleError)
			}
			.addOnFailureListener(::handleError)
	}

	/** 使用 SHA-256 生成 Play Integrity 请求哈希。 */
	private fun generateRequestHash(input: String): String? {
		return try {
			MessageDigest.getInstance("SHA-256")
				.digest(input.toByteArray(Charsets.UTF_8))
				.joinToString(separator = "") { byte ->
					(byte.toInt() and 0xff).toString(16).padStart(2, '0')
				}
		} catch (exception: Exception) {
			Log.e(TAG, "generateRequestHash error", exception)
			null
		}
	}

	/** 将 token 切到后台线程发送给服务端解析，避免阻塞 Play Integrity 回调线程。 */

	fun checkToken(token: String) {
		Log.e(TAG, "checkToken: token=$token")

		val client = OkHttpClient
			.Builder()
			.dns(IPv4FirstDns)
			.build()
		val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
		val params = JSONObject().apply {
			put("Key", "TianWangGaiDiHu")
			put("PackageName", Core.app.packageName)
			put("token", token)
		}

		val jsonBody = params.toString().toRequestBody(mediaType)
		val request = Request.Builder()
			.url(PolicyConfig.serverUrl + PolicyConfig.parseTokenPath)
			.post(jsonBody)
			.build()
		try {
			client.newCall(request).execute().use { response ->
				val body = response.body?.string()
				if (response.isSuccessful) {
					val json = JSONObject(body)
					val dataString = json.getString("data")
					Log.e(TAG, "checkToken: parsed data=$dataString")
					val playIntegrityData = parseJson(dataString)
					val appIntegrity = playIntegrityData.appIntegrity
					if (appIntegrity == null) {
						Log.e(TAG, "checkToken: missing appIntegrity payload")
						return
					}
					if (appIntegrity.packageName.isNullOrBlank() || appIntegrity.versionCode.isNullOrBlank()) {
						Log.w(TAG, "checkToken: incomplete appIntegrity payload, skip enforcement")
						return
					}
					if (appIntegrity.appRecognitionVerdict == "UNRECOGNIZED_VERSION") {
						Log.e(TAG, "checkToken: appRecognitionVerdict != PLAY_RECOGNIZED")
						UserInfo.riskPackage = RiskType.RISK
						RiskUtil.judgeUserType()
					}
				}
			}
		} catch (e: Exception) {
			Log.e(TAG, "checkToken: ", e)
		}
	}

	/** 统一记录 Play Integrity 请求或 token 获取失败。 */
	private fun handleError(exception: Exception) {
		Log.e(TAG, "handleError: ", exception)
	}

	object IPv4FirstDns : Dns {
		override fun lookup(hostname: String): List<InetAddress> {
			val addresses = Dns.SYSTEM.lookup(hostname)
			return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
		}
	}


	/** Play Integrity 服务端解析结果根节点。 */
	@Serializable
	data class Root(
		val accountDetails: AccountDetails? = null,
		val appIntegrity: AppIntegrity? = null,
		val deviceIntegrity: DeviceIntegrity? = null,
		val requestDetails: RequestDetails? = null,
	)

	/** 账号授权相关完整性信息。 */
	@Serializable
	data class AccountDetails(
		val appLicensingVerdict: String? = null,
	)

	/** App 包名、证书、版本和 Play 识别状态。 */
	@Serializable
	data class AppIntegrity(
		val appRecognitionVerdict: String? = null,
		val certificateSha256Digest: List<String> = emptyList(),
		val packageName: String? = null,
		val versionCode: String? = null,
	)

	/** 设备完整性 verdict 列表。 */
	@Serializable
	data class DeviceIntegrity(
		val deviceRecognitionVerdict: List<String> = emptyList(),
	)

	/** Play Integrity 请求上下文信息。 */
	@Serializable
	data class RequestDetails(
		val requestHash: String? = null,
		val requestPackageName: String? = null,
		val timestampMillis: String? = null,
	)

	private val playIntegrityJson = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}

	/** 解析服务端返回的 Play Integrity data JSON。 */
	fun parseJson(jsonString: String): Root {
		return playIntegrityJson.decodeFromString(jsonString)
	}

}
