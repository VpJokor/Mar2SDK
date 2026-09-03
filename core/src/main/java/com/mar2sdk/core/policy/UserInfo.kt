package com.mar2sdk.core.policy

import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.util.PreferenceUtil

object UserInfo {
	//ecpm为0,还是普通，还是高价值
	var ecpmType = EcpmType.ECOM_COMMON

	// APP首次打开时间（只在首次打开时赋值，后面只读取）
	var firstOpenTime = 0L
	// 首次广告收入
	var firstAdRevenue = -1.0

	//Singular归因数据
	var network = "unknow"
	var campaignId = "unknow"
	var campaignName = "unknow"

	//高风险IP
	var riskIP = RiskType.UNKNOW
	//高风险包
	var riskPackage = RiskType.UNKNOW

	fun init() {
		//首次打开时间
		firstOpenTime = PreferenceUtil.getLong(PolicyKey.KEY_FIRST_OPEN_TIME, 0L)
		if (firstOpenTime == 0L) {
			firstOpenTime = System.currentTimeMillis()
			PreferenceUtil.commitLong(PolicyKey.KEY_FIRST_OPEN_TIME, firstOpenTime)
			ThinkingUtil.setUserOnceAttr("firstOpenTime", firstOpenTime.toString())
		}
		//首次广告收入
		firstAdRevenue = PreferenceUtil.getDouble(PolicyKey.KEY_FIRST_AD_Revenue, -1.0)

		network = PreferenceUtil.getString(PolicyKey.KEY_NETWORK, "unknow")
		campaignId = PreferenceUtil.getString(PolicyKey.KEY_CAMPAIGN_Id, "unknow")
		campaignName = PreferenceUtil.getString(PolicyKey.KEY_CAMPAIGN_NAME, "unknow")

		riskIP = RiskType.valueOf(
			PreferenceUtil.getString(PolicyKey.KEY_RISK_IP, riskIP.name)
		)
		riskPackage = RiskType.valueOf(
			PreferenceUtil.getString(PolicyKey.KEY_RISK_PACKAGE, riskPackage.name)
		)
	}

	fun saveUserInfo() {
		PreferenceUtil.commitDouble(PolicyKey.KEY_FIRST_AD_Revenue, firstAdRevenue)
		PreferenceUtil.commitString(PolicyKey.KEY_NETWORK, network)
		PreferenceUtil.commitString(PolicyKey.KEY_CAMPAIGN_Id, campaignId)
		PreferenceUtil.commitString(PolicyKey.KEY_CAMPAIGN_NAME, campaignName)
		PreferenceUtil.commitString(PolicyKey.KEY_RISK_IP, riskIP.name)
		PreferenceUtil.commitString(PolicyKey.KEY_RISK_PACKAGE, riskPackage.name)
	}

}
