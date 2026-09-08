package com.mar2sdk.core.common

import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.common.status.EcpmType
import com.mar2sdk.core.common.status.RiskType
import com.mar2sdk.core.common.status.UserType

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
		if (UserInfo.firstAdRevenue == -1.0) {
			UserInfo.ecpmType = EcpmType.ECPM_UNKNOW
		} else if (UserInfo.firstAdRevenue == 0.0) {
			UserInfo.ecpmType = EcpmType.ECPM_0
		} else if (UserInfo.firstAdRevenue > CommonConfig.highEcpm) {
			UserInfo.ecpmType = EcpmType.ECPM_H
		} else {
			UserInfo.ecpmType = EcpmType.ECPM_COMMON
		}
	}

	// 用户分级
	fun judgeUserType() {
		//如果是测试模式，且测试模式设置为了强制模式，则强制不改变用户类型
		if ((Core.appMod == AppMod.TEST || Core.appMod == AppMod.DEBUG) && Core.testMod == TestMod.FORCE) {
			return
		}
		//正式版本逻辑
		if (
			UserInfo.riskIP == RiskType.RISK ||
			UserInfo.riskPackage == RiskType.RISK ||
			UserInfo.riskDevice == RiskType.RISK ||
			UserInfo.ecpmType == EcpmType.ECPM_0
		) {
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
