package com.mar2sdk.core.log

import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.CommonConfig
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.common.net.NetUtil
import com.mar2sdk.core.common.status.UserType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject

/** 事件采集时只生成快照；持久化、批量发送和重试在后台执行。 */
internal object AdEventReporter {
	private const val TAG = "AdEventReporter"
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	@Volatile private var identity: AdReportIdentity? = null
	private val queue by lazy {
		AdReportQueue(
			scope = scope,
			storage = SqliteAdReportStore(Core.app),
			currentIdentity = ::currentIdentity,
			send = { owner, events ->
				val request = NetUtil.report(events, owner.appID, owner.uid)
				try {
					request.await()
				} finally {
					if (!request.isCompleted) request.cancel()
				}
			},
			onFailure = { error ->
				Log.w(TAG, "Report queue failed: ${error.javaClass.simpleName}")
			},
			batchSize = { LogConfig.reportBatchSize },
			flushDelayMillis = { LogConfig.reportFlushIntervalMillis },
		)
	}

	private fun currentIdentity(): AdReportIdentity? = identity
		?.takeIf { it.appID == CommonConfig.serverAppID }

	/** 登录/恢复有效缓存后才开始采集，进程重启后同时恢复待发批次。 */
	fun onLogin(appID: Int, uid: Long) {
		if (appID != CommonConfig.serverAppID) return
		identity = AdReportIdentity(appID, uid)
		queue.flush()
	}

	fun onLogout(appID: Int) {
		if (identity?.appID == appID) identity = null
	}

	fun onNetworkAvailable() {
		if (currentIdentity() != null) queue.flush()
	}

	fun capture(eventName: String, params: Map<String, Any>) {
		if (eventName.isBlank()) return
		val owner = currentIdentity() ?: return
		try {
			val timeMillis = System.currentTimeMillis()
			val preset = TDAnalytics.getPresetProperties()?.toEventPresetProperties()
			val presetSnapshot = preset?.let { JSONObject(it.toString()) } ?: JSONObject()
			presetSnapshot.put("#lib", "Android")
				.put("#lib_version", TDAnalytics.getSDKVersion())
				.put("#data_source", "Native_SDK")
			val event = AdReportEvent.create(
				eventName = eventName,
				params = params,
				uid = owner.uid,
				distinctId = TDAnalytics.getDistinctId().orEmpty(),
				packageName = Core.app.packageName,
				presetProperties = presetSnapshot,
				superProperties = TDAnalytics.getSuperProperties() ?: JSONObject(),
				commonProperties = commonProperties(),
				timeMillis = timeMillis,
			)
			queue.enqueue(PendingAdReport(event.getString("#uuid"), owner, event.toString()))
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			Log.w(TAG, "Unable to capture report event: ${exception.javaClass.simpleName}")
		}
	}

	private fun commonProperties() = JSONObject().apply {
		put("traffic_source", UserInfo.trafficSource)
		put("isDebug", Core.appMod != AppMod.RELEASE)
		put("fromNature", Core.userType == UserType.NATURE)
		fun attribution(key: String, value: String) {
			if (value.isNotBlank() && value != "unknow") put(key, value)
		}
		attribution("network", UserInfo.network)
		attribution("campaign_id", UserInfo.campaignId)
		attribution("campaign_name", UserInfo.campaignName)
	}
}
