package com.mar2sdk.core.policy

import android.util.Log
import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.policy.RiskUtil.TAG
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.collections.buildList
import kotlin.let
import kotlin.takeIf

object IPUtil {
	private const val TAG = "IPUtil"

	private val cloudCidrs by lazy { loadCidrsFromRaw(R.raw.cloud) }
	private val googleCidrs by lazy { loadCidrsFromRaw(R.raw.google) }

	fun checkIpInfo() {
		val result = runCatching {
			val client = OkHttpClient()
			val request = Request.Builder()
				.url(PolicyConfig.serverUrl + PolicyConfig.ipInfoPath)
				.get()
				.build()

			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) throw IllegalStateException("Unexpected code $response")
				val body = response.body?.string() ?: ""
				val parsed = parseIpInfoPayload(body)
				return@use applyIpInfoResult(parsed, "AdvCheckManager.getIpInfoV2")
			}
		}.onFailure {
			Log.e(TAG, "getIpInfoV2 error", it)
		}.getOrNull()

	}

	private fun parseIpInfoPayload(raw: String): IpGeoDetail? {
		val parsedDetail = runCatching {
			val root = JSONObject(raw)
			val ip = root.optString("Ip").takeIf { it.isNotBlank() }
			val locationInfo = when (val value = root.opt("location_info")) {
				is JSONObject -> value.toString()
				is String -> value.takeIf { it.isNotBlank() }
				else -> null
			}
			val parsed = locationInfo?.let(::parseDetailFields) ?: parseDetailFields(raw)
			parsed?.let { detail ->
				if (detail.ip == null && ip != null) {
					detail.copy(ip = ip)
				} else {
					detail
				}
			}
		}.getOrNull()
		if (parsedDetail != null) {
			return parsedDetail
		}
		val ip = parseIp(raw) ?: return null
		return IpGeoDetail(ip = ip, longitude = null, latitude = null, asn = null, isp = null)
	}

	private fun parseIp(raw: String): String? {
		return runCatching {
			val json = JSONObject(raw)
			json.optString("Ip").takeIf { it.isNotBlank() }
		}.getOrNull()
	}

	private fun parseDetailFields(detailJson: String): IpGeoDetail? {
		return runCatching {
			val json = JSONObject(detailJson)
			val ip = json.optString("ip").takeIf { it.isNotBlank() }
			val longitude = json.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() }
			val latitude = json.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() }
			val asn = json.optString("asn").takeIf { it.isNotBlank() }
			val isp = json.optString("isp").takeIf { it.isNotBlank() }
			IpGeoDetail(ip, longitude, latitude, asn, isp)
		}.getOrNull()
	}

	private fun applyIpInfoResult(parsed: IpGeoDetail?, source: String): String? {
		parsed ?: return null
		val ipValue = parsed.ip
		val isGoogleNetwork = if (ipValue != null) {
			val inCloud = isIpInCidrs(ipValue, cloudCidrs)
			val inGoogle = isIpInCidrs(ipValue, googleCidrs)
			Log.e(TAG, "getIpInfoV2: ip=$ipValue inCloud=$inCloud inGoogle=$inGoogle")
			inCloud || inGoogle
		} else {
			false
		}
		val isGoogleIp = isGoogleNetwork || parsed.isp?.contains("google", ignoreCase = true) == true || parsed.asn?.contains("15169") == true
		ThinkingUtil.setUserOnceAttr("ip_info", "IPInfo ip=${parsed.ip}, longitude=${parsed.longitude}, latitude=${parsed.latitude}, asn=${parsed.asn}, isp=${parsed.isp}",)
		val detailSummary = "IP detail -> ip=$parsed.ip, Asn=$parsed.asn, Isp=$parsed.isp"
		Log.e(TAG, detailSummary)
		if (isGoogleIp) {
			UserInfo.riskIP = RiskType.RISK
			RiskUtil.judgeRisk()
		}
		return detailSummary
	}

	private fun isIpInCidrs(ip: String, cidrs: List<CidrRange>): Boolean {
		val target = try { InetAddress.getByName(ip).address
		} catch (e: UnknownHostException) {
			Log.e(TAG, "isIpInCidrs: invalid ip $ip", e)
			return false
		}
		return cidrs.any { it.contains(target) }
	}

	private fun loadCidrsFromRaw(resId: Int): List<CidrRange> {
		return runCatching {
			Core.app.resources.openRawResource(resId).bufferedReader().use { reader ->
				val root = JSONObject(reader.readText())
				val prefixes = root.optJSONArray("prefixes") ?: JSONArray()
				buildList {
					for (i in 0 until prefixes.length()) {
						val entry = prefixes.optJSONObject(i) ?: continue
						entry.optString("ipv4Prefix").takeIf { it.isNotBlank() }?.let { cidr ->
							parseCidr(cidr)?.let { add(it) }
						}
						entry.optString("ipv6Prefix").takeIf { it.isNotBlank() }?.let { cidr ->
							parseCidr(cidr)?.let { add(it) }
						}
					}
				}
			}
		}.onFailure {
			Log.e(TAG, "loadCidrsFromRaw failed", it)
		}.getOrElse { emptyList() }
	}

	private fun parseCidr(cidr: String): CidrRange? {
		val parts = cidr.split("/")
		if (parts.size != 2) return null
		val prefix = parts[1].toIntOrNull() ?: return null
		return try {
			val addr = InetAddress.getByName(parts[0]).address
			CidrRange(addr, prefix)
		} catch (e: Exception) {
			Log.e(TAG, "parseCidr failed for $cidr", e)
			null
		}
	}

	@Serializable
	data class IpGeoDetail(
		val ip: String?,
		val longitude: Double?,
		val latitude: Double?,
		val asn: String?,
		val isp: String?,
	)

	@Serializable
	data class CidrRange(
		val address: ByteArray,
		val prefixLength: Int,
	) {
		fun contains(target: ByteArray): Boolean {
			if (target.size != address.size) return false
			var remaining = prefixLength
			var index = 0
			while (remaining >= 8) {
				if (address[index] != target[index]) return false
				remaining -= 8
				index++
			}
			if (remaining > 0) {
				val mask = (0xFF shl (8 - remaining)) and 0xFF
				val lhs = address[index].toInt() and mask
				val rhs = target[index].toInt() and mask
				return lhs == rhs
			}
			return true
		}
	}
}