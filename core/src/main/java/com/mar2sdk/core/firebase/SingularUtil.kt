package com.mar2sdk.core.firebase

import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.RiskUtil
import com.mar2sdk.core.common.UserInfo
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
					val network = attributionData["network"]?.toString().orEmpty()
					val campaignId = attributionData["campaign_id"]?.toString()?.takeIf { it.isNotEmpty() }
					val campaignName = attributionData["campaign_name"]?.toString()?.takeIf { it.isNotEmpty() }
					Log.e(TAG, "init: Singular初始化成功 network = $network")

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
				} catch (e: JSONException) {
					// 处理异常
					Log.e(TAG, "onDeviceAttributionInfoReceived: ", e)
				}
			}
		Singular.init(Core.app, config)
	}

}