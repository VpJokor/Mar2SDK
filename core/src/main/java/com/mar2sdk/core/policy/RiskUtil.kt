package com.mar2sdk.core.policy

import com.mar2sdk.core.Core
import com.mar2sdk.core.Core.userType
import com.mar2sdk.core.log.ThinkingUtil

/**
 * 风控类
 */
object RiskUtil {
	fun init() {
		judgeRisk()
	}

	fun judgeRisk() {
		judgeRiskIP()
		judgeEcpm()
		judgeUserType()
		ThinkingUtil.setUserAttr("userType", userType.name)
	}

	// 请求IP信息
	fun judgeRiskIP() {

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
		if (UserInfo.riskIP || UserInfo.ecpmType == EcpmType.ECPM_0) {
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
	}

}