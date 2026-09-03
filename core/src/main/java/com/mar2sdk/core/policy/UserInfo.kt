package com.mar2sdk.core.policy

object UserInfo {
	//Singular归因数据
	var network = "unknow"
	var campaignId = "unknow"
	var campaignName = "unknow"
	//ecpm为0,还是普通，还是高价值
	var ecpmType = EcpmType.ECOM_COMMON
	//高风险IP
	var riskIP = false
}