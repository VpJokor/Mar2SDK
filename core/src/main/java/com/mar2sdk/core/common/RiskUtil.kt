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
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.common.status.EcpmType
import com.mar2sdk.core.common.status.RiskType
import com.mar2sdk.core.common.status.UserType
import com.mar2sdk.core.common.net.SearchConfigsResponse
import com.mar2sdk.core.common.net.NetUtil
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * The user type used by each policy source.  A value of [UserType.UNKNOW]
 * means that the corresponding source has not supplied a policy yet.
 *
 * This deliberately reports the source selectors rather than the currently
 * applied configuration: a server response can override a Remote Config
 * policy, while both values are still useful when diagnosing a build.
 */
data class PolicySourceSnapshot(
	val localUserType: UserType = UserType.UNKNOW,
	val serverUserType: UserType = UserType.UNKNOW,
	val localAdPolicyUserType: UserType = UserType.UNKNOW,
	val serverAdPolicyUserType: UserType = UserType.UNKNOW,
	val localNotificationPolicyUserType: UserType = UserType.UNKNOW,
	val serverNotificationPolicyUserType: UserType = UserType.UNKNOW,
)

/**
 * 风控类
 */
object RiskUtil {
	private const val TAG = "RiskUtil"
	@Volatile
	private var activeRemoteConfig: FirebaseRemoteConfig? = null
	@Volatile
	private var serverAdConfigApplied = false
	@Volatile
	private var serverNotificationConfigApplied = false
	private val policySourceLock = Any()
	@Volatile
	private var policySourceSnapshot = PolicySourceSnapshot()
	private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	/** Returns an immutable snapshot of local and server policy source types. */
	fun getPolicySourceSnapshot(): PolicySourceSnapshot = policySourceSnapshot

	fun init() {
		serverAdConfigApplied = false
		serverNotificationConfigApplied = false
		clearPolicySourceSnapshot()
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
			Log.e(TAG, "judgeFromLocal: " +
					"UserInfo.riskIP = ${UserInfo.riskIP}," +
					"UserInfo.riskPackage = ${UserInfo.riskPackage}," +
					"UserInfo.riskDevice = ${UserInfo.riskDevice}," +
					"UserInfo.ecpmType = ${UserInfo.ecpmType}"
			)
			UserInfo.localUserType = UserType.RISK
			applyUserType()
			updateLocalUserTypeSource(UserInfo.localUserType)
			scheduleActiveRemoteConfig()
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
			Core.setUserAttr("userType", Core.userType.name)
		}
		updateLocalUserTypeSource(UserInfo.localUserType)
		UserInfo.saveUserInfo()
		scheduleActiveRemoteConfig()
	}

	// TODO: 读取服务端下发的用户类型和策略
	fun judgeUserFromNet() {
		NetUtil.scheduleSearchConfigsRefresh()
	}

	/** Applies the user type and policy configurations returned by searchConfigs. */
	internal fun applyServerConfigs(response: SearchConfigsResponse) {
		// 1060 means that this round should keep the already loaded local configuration.
		if (response.code != 0) {
			if (response.code == 1060) {
				clearServerPolicySources()
				updateServerUserTypeSource(UserType.UNKNOW)
			}
			return
		}
		if ((Core.appMod == AppMod.TEST || Core.appMod == AppMod.DEBUG) && Core.testMod == TestMod.FORCE) {
			return
		}
		serverAdConfigApplied = false
		serverNotificationConfigApplied = false

		val serverUserType = response.userType
			?.uppercase(Locale.ROOT)
			?.let { value -> runCatching { UserType.valueOf(value) }.getOrNull() }
			?.takeUnless { it == UserType.UNKNOW }
			?: run {
				Log.w(TAG, "searchConfigs returned an invalid userType")
				return
			}
		clearServerPolicySources()

		// The server result takes precedence over the local risk classification for
		// subsequent calls to judgeUserType().
		UserInfo.netUserType = serverUserType
		updateServerUserTypeSource(serverUserType)
		Core.userType = serverUserType
		Core.setUserAttr("userType", serverUserType.name)
		response.items.forEach { item ->
			if (item.code != 0 || item.config == null) return@forEach
			runCatching {
				val itemUserType = parseUserType(item.userType).takeUnless { it == UserType.UNKNOW } ?: serverUserType
				when (item.type) {
					"ad_config" -> {
						AdConfig.applyConfig(item.config)
						serverAdConfigApplied = true
						updateServerAdPolicySource(itemUserType)
					}
					"notification_config" -> {
						NotificationConfig.applyConfig(item.config)
						NotificationConfig.saveNotificationConfig()
						serverNotificationConfigApplied = true
						updateServerNotificationPolicySource(itemUserType)
					}
				}
			}.onFailure { error ->
				Log.w(TAG, "Unable to apply searchConfigs item type=${item.type}", error)
			}
		}
		if (!serverAdConfigApplied || !serverNotificationConfigApplied) scheduleActiveRemoteConfig()
	}

	/**
	 * 更新本地配置信息
	 * 1. 如果接口有返回 ad_config 和 notification_config 配置，则以服务器接口返回的为准(服务器接口暂时未对接，预留接口，暂时不实现)
	 * 2. 如果接口没有返回配置，RemoteConfig已经可以拉到配置则 ad_config 和 notification_config 根据用户类型及 AdConfig.isRisk 取对应的策略配置
	 * 3. 如果接口没返回配置且RemoteConfig也还没拉到 ad_config 和 notification_config 配置，则使用本地Raw文件夹中的默认配置
	 * 4. 除 ad_config 和 notification_config 配置以外，其他的配置文件 优先使用RemoteConfig中的配置，未拉到RemoteConfig中的配置时使用raw文件夹中的默认配置
	 */
	fun updateConfig() {
		FirebaseUtil.onRemoteConfigActivated = { remoteConfig, _ ->
			Log.e(TAG, "updateConfig: 从RemoteConfig上抓取到新的配置" )
			activeRemoteConfig = remoteConfig
			scheduleActiveRemoteConfig()
		}
	}

	private fun scheduleActiveRemoteConfig() {
		activeRemoteConfig?.let { remoteConfig ->
			configScope.launch { applyRemoteConfig(remoteConfig) }
		}
	}

	private fun applyRemoteConfig(remoteConfig: FirebaseRemoteConfig) {
		applyUserPolicyConfigs(
			readConfig = { key -> readJson(remoteConfig, key) },
			applyAdConfig = !serverAdConfigApplied,
			applyNotificationConfig = !serverNotificationConfigApplied,
		)

		applyJson(remoteConfig, "notification_content") { NotificationConfig.applyContentConfig(it) }
		applyJson(remoteConfig, "common_config") { CommonConfig.applyConfig(it) }
		applyJson(remoteConfig, "admob_config") { AdmobConfig.applyConfig(it) }
		applyJson(remoteConfig, "singular_config") { SingularConfig.applyConfig(it) }
		applyJson(remoteConfig, "thinking_config") { ThinkingConfig.applyConfig(it) }
		applyJson(remoteConfig, "log_config") { LogConfig.applyConfig(it) }
	}

	internal fun applyUserPolicyConfigs(readConfig: (String) -> JSONObject?) {
		applyUserPolicyConfigs(readConfig, applyAdConfig = true, applyNotificationConfig = true)
	}

	private fun applyUserPolicyConfigs(
		readConfig: (String) -> JSONObject?,
		applyAdConfig: Boolean = true,
		applyNotificationConfig: Boolean = true,
	) {
		val userType = Core.userType
		var localAdPolicyUserType = UserType.UNKNOW
		var localNotificationPolicyUserType = UserType.UNKNOW
		val userAdConfig = if (applyAdConfig) {
			readConfig("ad_config_${userType.name}")
		} else {
			null
		}
		// 原用户类型的配置控制开关；缺失时沿用资源或已保存的值。
		val isRisk = userAdConfig?.optBoolean("isRisk", AdConfig.isRisk) ?: AdConfig.isRisk
		val policyUserType = userType.forPolicy(isRisk)
		val adConfig = if (policyUserType == userType) userAdConfig else readConfig("ad_config_${policyUserType.name}")
		if (applyAdConfig && (adConfig != null || isRisk != AdConfig.isRisk)) {
			val applied = runCatching {
				// 风险策略中的 isRisk 不覆盖用于选择策略的开关，避免配置来回切换。
				val config = adConfig?.let { JSONObject(it.toString()) } ?: JSONObject()
				AdConfig.applyConfig(config.put("isRisk", isRisk))
			}.isSuccess
			if (applied && (userAdConfig != null || adConfig != null)) localAdPolicyUserType = policyUserType
		}
		if (applyNotificationConfig) readConfig("notification_config_${policyUserType.name}")?.let { config ->
			val applied = runCatching {
				NotificationConfig.applyConfig(config)
				NotificationConfig.saveNotificationConfig()
			}.isSuccess
			if (applied) localNotificationPolicyUserType = policyUserType
		}
		if (applyAdConfig || applyNotificationConfig) {
			updateLocalPolicySources(
				adPolicyUserType = if (applyAdConfig) localAdPolicyUserType else null,
				notificationPolicyUserType = if (applyNotificationConfig) localNotificationPolicyUserType else null,
			)
		}
	}

	private fun parseUserType(value: String?): UserType = value
		?.uppercase(Locale.ROOT)
		?.let { runCatching { UserType.valueOf(it) }.getOrNull() }
		?: UserType.UNKNOW

	private fun clearPolicySourceSnapshot() {
		synchronized(policySourceLock) { policySourceSnapshot = PolicySourceSnapshot() }
	}

	private fun updateLocalUserTypeSource(userType: UserType) {
		synchronized(policySourceLock) {
			policySourceSnapshot = policySourceSnapshot.copy(localUserType = userType)
		}
	}

	private fun updateServerUserTypeSource(userType: UserType) {
		synchronized(policySourceLock) {
			policySourceSnapshot = policySourceSnapshot.copy(serverUserType = userType)
		}
	}

	private fun clearServerPolicySources() {
		synchronized(policySourceLock) {
			policySourceSnapshot = policySourceSnapshot.copy(
				serverAdPolicyUserType = UserType.UNKNOW,
				serverNotificationPolicyUserType = UserType.UNKNOW,
			)
		}
	}

	private fun updateServerAdPolicySource(userType: UserType) {
		synchronized(policySourceLock) {
			policySourceSnapshot = policySourceSnapshot.copy(serverAdPolicyUserType = userType)
		}
	}

	private fun updateServerNotificationPolicySource(userType: UserType) {
		synchronized(policySourceLock) {
			policySourceSnapshot = policySourceSnapshot.copy(serverNotificationPolicyUserType = userType)
		}
	}

	private fun updateLocalPolicySources(
		adPolicyUserType: UserType?,
		notificationPolicyUserType: UserType?,
	) {
		synchronized(policySourceLock) {
			policySourceSnapshot = policySourceSnapshot.copy(
				localAdPolicyUserType = adPolicyUserType ?: policySourceSnapshot.localAdPolicyUserType,
				localNotificationPolicyUserType = notificationPolicyUserType ?: policySourceSnapshot.localNotificationPolicyUserType,
			)
		}
	}

	private fun applyJson(
		remoteConfig: FirebaseRemoteConfig,
		key: String,
		apply: (JSONObject) -> Unit
	) {
		readJson(remoteConfig, key)?.let { json -> runCatching { apply(json) } }
	}

	private fun readJson(remoteConfig: FirebaseRemoteConfig, key: String): JSONObject? {
		val value = runCatching { remoteConfig.getString(key) }.getOrNull()
		Log.e(TAG, "applyJson: key = $key, value = $value")
		if (value.isNullOrBlank()) return null
		return runCatching { JSONObject(value) }.getOrNull()
	}

}
