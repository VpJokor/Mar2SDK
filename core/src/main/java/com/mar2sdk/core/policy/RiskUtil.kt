package com.mar2sdk.core.policy

import com.mar2sdk.core.Core

/**
 * 风控类
 */
object RiskUtil {
	fun init() {
		requestIPInfo()
	}

	// 请求IP信息
	fun requestIPInfo() {

	}

	fun judgeEcpm(ad_revenue: Double) {
		if (ad_revenue == 0.0) {
			UserInfo.ecpmType = EcpmType.ECPM_0
		} else if (ad_revenue > PolicyConfig.highEcpm) {
			UserInfo.ecpmType = EcpmType.ECPM_H
		} else {
			UserInfo.ecpmType = EcpmType.ECOM_COMMON
		}
		// TODO: 持久化数据
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