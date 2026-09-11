package com.mar2sdk.core.common

import android.util.Log
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.impl.admob.AdmobConfig
import com.mar2sdk.core.firebase.FirebaseUtil
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.log.LogConfig
import com.mar2sdk.core.log.ThinkingConfig
import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.common.status.EcpmType
import com.mar2sdk.core.common.status.RiskType
import com.mar2sdk.core.common.status.UserType
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import org.json.JSONObject

/**
 * 风控类
 */
object RiskUtil {
	private const val TAG = "RiskUtil"
	@Volatile
	private var activeRemoteConfig: FirebaseRemoteConfig? = null

	fun init() {
		updateConfig()
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
		judgeFromLocal()
		judgeUserFromNet()
	}

	// INFO: 本地用户分级
	fun judgeFromLocal() {
		fun applyUserType() {
			if (Core.userType != UserInfo.localUserType && UserInfo.netUserType == UserType.UNKNOW) {
				Core.userType = UserInfo.localUserType
			}
		}
		if (
			UserInfo.riskIP == RiskType.RISK ||
			UserInfo.riskPackage == RiskType.RISK ||
			UserInfo.riskDevice == RiskType.RISK ||
			UserInfo.ecpmType == EcpmType.ECPM_0
		) {
			UserInfo.localUserType = UserType.RISK
			applyUserType()
			activeRemoteConfig?.let(::applyRemoteConfig)
			return
		}
		if (UserInfo.network.equals("organic", ignoreCase = true) || UserInfo.network.isEmpty()) {
			UserInfo.localUserType = UserType.NATURE
		} else {
			UserInfo.localUserType = UserType.COMMON
			if (UserInfo.ecpmType == EcpmType.ECPM_H) {
				UserInfo.localUserType = UserType.HIGH_VALUE
			}
		}
		applyUserType()
		if (Core.userType == UserInfo.localUserType) {
			ThinkingUtil.setUserAttr("userType", Core.userType.name)
		}
		UserInfo.saveUserInfo()
		activeRemoteConfig?.let(::applyRemoteConfig)
	}

	// TODO: 读取服务端下发的用户类型和策略
	fun judgeUserFromNet() {

	}

	/**
	 * 更新本地配置信息
	 * 1. 如果接口有返回 ad_config 和 notification_config 配置，则以服务器接口返回的为准(服务器接口暂时未对接，预留接口，暂时不实现)
	 * 2. 如果接口没有返回配置，RemoteConfig已经可以拉到配置则 ad_config 和 notification_config 根据 Core.userType 取 RemoteConfig中对应的配置
	 * 3. 如果接口没返回配置且RemoteConfig也还没拉到 ad_config 和 notification_config 配置，则使用本地Raw文件夹中的默认配置
	 * 4. 除 ad_config 和 notification_config 配置以外，其他的配置文件 优先使用RemoteConfig中的配置，未拉到RemoteConfig中的配置时使用raw文件夹中的默认配置
	 */
	fun updateConfig() {
		FirebaseUtil.onRemoteConfigActivated = { remoteConfig, _ ->
			Log.e(TAG, "updateConfig: 从RemoteConfig上抓取到新的配置" )
			activeRemoteConfig = remoteConfig
			applyRemoteConfig(remoteConfig)
		}
	}

	private fun applyRemoteConfig(remoteConfig: FirebaseRemoteConfig) {
		// 根据当前生效的用户类型选择 ad_config 和 notification_config。
		applyJson(remoteConfig, "ad_config_${Core.userType.name}") { AdConfig.applyConfig(it) }
		applyJson(remoteConfig, "notification_config_${Core.userType.name}") {
			NotificationConfig.applyConfig(it)
			NotificationConfig.saveNotificationConfig()
		}

		applyJson(remoteConfig, "notification_content") { NotificationConfig.applyContentConfig(it) }
		applyJson(remoteConfig, "common_config") { CommonConfig.applyConfig(it) }
		applyJson(remoteConfig, "admob_config") { AdmobConfig.applyConfig(it) }
		applyJson(remoteConfig, "singular_config") { SingularConfig.applyConfig(it) }
		applyJson(remoteConfig, "thinking_config") { ThinkingConfig.applyConfig(it) }
		applyJson(remoteConfig, "log_config") { LogConfig.applyConfig(it) }
	}

	private fun applyJson(
		remoteConfig: FirebaseRemoteConfig,
		key: String,
		apply: (JSONObject) -> Unit
	) {
		val value = runCatching { remoteConfig.getString(key) }.getOrNull()
		Log.e(TAG, "applyJson: key = $key, value = $value")
		if (value.isNullOrBlank()) return
		runCatching { JSONObject(value) }
			.onSuccess { json -> runCatching { apply(json) } }
	}

}
