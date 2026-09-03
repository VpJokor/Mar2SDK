package com.mar2sdk.core.policy

import android.util.Log
import com.mar2sdk.core.Core
import com.mar2sdk.core.log.ThinkingUtil
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 风控类
 */
object RiskUtil {
	private const val TAG = "RiskUtil"

	fun init() {
		judgeRisk()
	}

	fun judgeRisk() {
		judgeRiskIP()
		judgePackage()
		judgeEcpm()
		judgeUserType()
	}

	// 请求IP信息
	fun judgeRiskIP() {
		if (UserInfo.riskIP != RiskType.UNKNOW) return
		IPUtil.checkIpInfo()
	}

	// 包名校验 integrity
	fun judgePackage() {
		if (UserInfo.riskPackage != RiskType.UNKNOW) return
		PlayIntegrityUtil.requestPlayIntegrity()
	}

	fun judgeEcpm() {
		if (UserInfo.firstAdRevenue == 0.0) {
			UserInfo.ecpmType = EcpmType.ECPM_0
		} else if (UserInfo.firstAdRevenue > PolicyConfig.highEcpm) {
			UserInfo.ecpmType = EcpmType.ECPM_H
		} else {
			UserInfo.ecpmType = EcpmType.ECOM_COMMON
		}
	}

	// 用户分级
	fun judgeUserType() {
		if (UserInfo.riskIP == RiskType.RISK || UserInfo.riskPackage == RiskType.RISK || UserInfo.ecpmType == EcpmType.ECPM_0) {
			Core.userType = UserType.RISK
			return
		}
		if (UserInfo.network.equals("organic", ignoreCase = true) || UserInfo.network.isEmpty()) {
			Core.userType = UserType.NATURE
		} else {
			Core.userType = UserType.COMMON
			if (UserInfo.ecpmType == EcpmType.ECPM_H) {
				Core.userType = UserType.HIGH_VALUE
			}
		}
		ThinkingUtil.setUserAttr("userType", Core.userType.name)
		UserInfo.saveUserInfo()
	}



}
