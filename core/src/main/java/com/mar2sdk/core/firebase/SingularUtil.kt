package com.mar2sdk.core.firebase

import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.RiskUtil
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.common.net.NetUtil
import com.singular.sdk.Singular
import org.json.JSONException
import org.json.JSONObject

object SingularUtil {
	private const val TAG = "SingularUtil"
	fun init() {
		Log.e(TAG, "initSingular: 开始初始化" )
		val config = com.singular.sdk.SingularConfig(SingularConfig.key, SingularConfig.secret)
			.withLoggingEnabled()
			.withLogLevel(1)
			.withSingularDeviceAttribution { attributionData ->
				// INFO: 只有首次安装APP时该方法会被回调
				val promoteParams = JSONObject()
				try {
					val attribution = JSONObject(attributionData)
					NetUtil.onSingularAttribution(attribution)
					val network = attribution.value("network").orEmpty()
					val campaignId = attribution.value("campaign_id")
					val campaignName = attribution.value("campaign_name")
					Log.e(TAG, "init: Singular初始化成功 network = $network")

					// 与 Install Referrer 共用锁，防止兜底结果覆盖已解析的 Singular 归因。
					synchronized(UserInfo) {
						UserInfo.network = network
						UserInfo.campaignId = campaignId ?: "unknow"
						UserInfo.campaignName = campaignName ?: "unknow"
						UserInfo.saveUserInfo()
						RiskUtil.judgeUserType()

						promoteParams.put("network", network)
						campaignId?.let {
							promoteParams.put("campaign_id", it)
						}
						campaignName?.let {
							promoteParams.put("campaign_name", it)
						}
						// network 为空或 organic 时按自然量处理；测试环境固定为非自然量，方便走广告分支。
						promoteParams.put("fromNature", network.equals("organic", ignoreCase = true) || network.isEmpty())
						TDAnalytics.userSet(promoteParams)
					}
				} catch (e: JSONException) {
					// 处理异常
					Log.e(TAG, "onDeviceAttributionInfoReceived: ", e)
				}
			}
		Singular.init(Core.app, config)
	}

	private fun JSONObject.value(key: String): String? = opt(key)
		?.takeUnless { it == JSONObject.NULL }
		?.toString()
		?.takeIf { it.isNotEmpty() }

}
