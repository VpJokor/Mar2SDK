package com.mar2sdk.core.ad.policy

import com.inmobi.media.Bo
import com.mar2sdk.core.ad.AdConfig


object AdPolicy {
	fun canShowAd(adContext: ScreenAdContext) : Boolean {
		// 全局广告开关判断
		if (!AdConfig.isOpen) return false
		if (isShowMax()) return false
		if (isShowMax(adContext)) return false
		return true
	}

	fun isShowMax() : Boolean {
		// TODO: 获取1小时和24小时内的广告展示次数
		val _1HShow = 0
		val _24HShow = 0
		if (_1HShow >= AdConfig.max1H) return false
		if (_24HShow >= AdConfig.max24H) return false
		return true
	}

	fun isShowMax(adContext: ScreenAdContext) : Boolean {
		// TODO: 获取1小时和24小时内areaKey对应的广告展示次数
		val _1HShow = 0
		val _24HShow = 0
		val config = AdConfig.adUnits[adContext.areaKey] ?: return false
		val max1H = config.max1H
		val max24H = config.max24H
		if (_1HShow > max1H) return false
		if (_24HShow > max24H) return false
		return true
	}
}