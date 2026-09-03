package com.mar2sdk.core.ad.impl

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.firebase.analytics.FirebaseAnalytics
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdLoadStatus
import com.mar2sdk.core.ad.status.AdShowStatus
import com.mar2sdk.core.ad.status.ShowFailResult
import com.mar2sdk.core.log.LogAdEvent
import com.mar2sdk.core.log.LogAdParam
import com.mar2sdk.core.log.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开屏广告展示器
 */
object AdmobShower {

	private const val TAG = "AdmobShower"
	private val adScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	// 开屏广告展示超时时间
	var showOpenTimeout = 10 * 1000L
	var showInterTimeout = 10 * 1000L
	var showVideoTimeout = 10 * 1000L

	/**
	 *  广告展示
	 *  限制：
	 *  1. 整个APP只允许同时展示1个全屏广告(开屏/插屏/视频)，使用 AppStatus.isShowingAd 控制
	 *  1.1 如果其他广告正在展示或者正在等待加载完展示则返回 AdShowStatus.OTHER_AD_IS_SHOWING
	 *  2. 一直等待到ad.show调用方法或等待超时方法再返回 AdShowStatus
	 *  2.1 等待超时 返回 AdShowStatus.TIMEOUT，并后续广告加载成功不调用ad.show 方法
	 *
	 *  展示规则
	 *  1. 如果广告池里已经有加载好的广告，则直接取缓存的广告展示，并返回 AdShowStatus
	 *  2. 如果广告池里没有广告且正在加载广告，则下一个广告加载完毕后立即展示(如果超时则放入广告池不展示)，并返回  AdShowStatus
	 *  3. 如果广告池里没有广告且没有正在加载的广告，则开始加载广告，等广告加载完毕后立即展示(如果超时则放入广告池不展示)，并返回  AdShowStatus
	 */
	suspend fun showOpen(activity: Activity, callback: ShowCallback): AdShowStatus = withContext(Dispatchers.Main.immediate) {
		LogUtil.log(
			LogAdEvent.ad_occur,
			mapOf(
				LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
				LogAdParam.ad_areakey to callback.areaKey,
				LogAdParam.ad_format to LogAdParam.ad_format_open,
				LogAdParam.ad_unit_name to AdmobConfig.openID,
			)
		)
		if (AppStatus.isShowingAd) {
			Log.e(TAG, "showOpen: AppStatus.isShowingAd" )
			callback.showFailed(ShowFailResult.OTHER_AD_IS_SHOWING)
			return@withContext AdShowStatus.OTHER_AD_IS_SHOWING
		}
		//检查广告池广告是否过期
		AdmobLoader.checkOpenPool()

		//修改APP状态
		AppStatus.isShowingAd = true
		val startShowTime = System.currentTimeMillis()
		var currentOpenAd: AppOpenAd? = null
		val showFailed = AtomicBoolean(false)
		var showCommitted = false

		fun fail(failResult: ShowFailResult, showStatus: AdShowStatus = AdShowStatus.SHOW_FAIL): AdShowStatus {
			if (showFailed.compareAndSet(false, true)) {
				AppStatus.isShowingAd = false
				callback.showFailed(failResult)
			}
			return showStatus
		}

		fun logShowEvent(eventName: String) {
			LogUtil.log(
				eventName,
				mapOf(
					LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
					LogAdParam.duration to (System.currentTimeMillis() - startShowTime),
					LogAdParam.ad_areakey to callback.areaKey,
					LogAdParam.ad_format to LogAdParam.ad_format_open,
					LogAdParam.ad_source to (currentOpenAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
					LogAdParam.ad_unit_name to AdmobConfig.openID,
					LogAdParam.ad_preload to true,
				)
			)
		}

		fun logShowEventSafely(eventName: String, errorMessage: String) {
			try {
				logShowEvent(eventName)
			} catch (e: Exception) {
				Log.e(TAG, errorMessage, e)
			}
		}

		// INFO: 处理广告展示回调
		val contentCallback = object : FullScreenContentCallback() {
			override fun onAdFailedToShowFullScreenContent(p0: AdError) {
				logShowEventSafely(LogAdEvent.ad_show_fail, "Failed to log open ad show failure")
				fail(ShowFailResult.FAILED_TO_SHOW_CONTENT)
			}

			override fun onAdDismissedFullScreenContent() {
				logShowEventSafely(LogAdEvent.ad_close, "Failed to log open ad close")
				AppStatus.isShowingAd = false
				callback.onAdClosed()
			}

			override fun onAdImpression() {
				// info: 处理展示
				callback.showSuccess()
			}

			override fun onAdClicked() {
				logShowEvent(LogAdEvent.ad_click)
				callback.onClicked()
			}
		}
		// INFO: 处理广告收入回调
		val paidCallback = OnPaidEventListener { adValue ->
			Log.e(TAG, "showOpen: $adValue" )
			// info: 处理收入打点
			val revenue = adValue.valueMicros / 1_000_000.0
			val revenueParams = mapOf(
				LogAdParam.ad_areakey to callback.areaKey,
				FirebaseAnalytics.Param.AD_PLATFORM to LogAdParam.ad_platform_admob,
				FirebaseAnalytics.Param.AD_UNIT_NAME to AdmobConfig.openID,
				FirebaseAnalytics.Param.AD_FORMAT to LogAdParam.ad_format_open,
				FirebaseAnalytics.Param.AD_SOURCE to (currentOpenAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
				FirebaseAnalytics.Param.CURRENCY to adValue.currencyCode,
				FirebaseAnalytics.Param.VALUE to revenue,
				LogAdParam.ad_preload to true,
			)
			LogUtil.log(LogAdEvent.ad_impression, revenueParams)
			LogUtil.log(LogAdEvent.ad_revenue, revenueParams)
			LogUtil.logSingularAdRevenue(LogAdParam.adMob, revenue)
			callback.onPaid()
		}

		suspend fun show(ad: AppOpenAd): AdShowStatus {
			currentCoroutineContext().ensureActive()
			currentOpenAd = ad
			if (activity.isFinishing || activity.isDestroyed) {
				return fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			showCommitted = true
			val showStatus = try {
				// 广告只能展示一次，展示前从池中移除
				AdmobLoader.openPool.remove(ad)
				ad.fullScreenContentCallback = contentCallback
				ad.onPaidEventListener = paidCallback
				ad.show(activity)
				if (showFailed.get()) AdShowStatus.SHOW_FAIL else AdShowStatus.SHOW_SUCCESS
			} catch (e: Exception) {
				Log.e(TAG, "show: ", e)
				fail(ShowFailResult.SHOW_AD_EXCEPTION)
			}
			adScope.launch {
				try {
					if (AdmobLoader.loadOpen(areaKey = callback.areaKey) == AdLoadStatus.LOAD_FAIL) {
						Log.e(TAG, "Open ad preload failed")
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.e(TAG, "Failed to preload open ad: ", e)
				}
			}
			return showStatus
		}

		try {
			val openAd = AdmobLoader.openPool.keys.firstOrNull() ?: when (
				val loadResult = try {
					withTimeoutOrNull(showOpenTimeout) {
						AdmobLoader.loadOpenResult(areaKey = callback.areaKey)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					AdmobLoader.OpenLoadResult.Failed(exception = e)
				}
			) {
				null -> {
					logShowEventSafely(LogAdEvent.ad_show_timeout, "Failed to log open ad timeout")
					return@withContext fail(ShowFailResult.LOAD_TIMEOUT, AdShowStatus.TIMEOUT)
				}
				is AdmobLoader.OpenLoadResult.Loaded -> loadResult.ad
				is AdmobLoader.OpenLoadResult.Failed -> {
					val failResult = if (loadResult.loadError != null) {
						Log.e(TAG, "Open ad load failed: ${loadResult.loadError.message}")
						ShowFailResult.LOAD_FAILED
					} else {
						loadResult.exception?.let {
							Log.e(TAG, "showOpen: ", it)
						}
						ShowFailResult.LOAD_AD_EXCEPTION
					}
					return@withContext fail(failResult, AdShowStatus.LOAD_FAIL)
				}
				AdmobLoader.OpenLoadResult.PoolFull ->
					AdmobLoader.openPool.keys.firstOrNull() ?: return@withContext fail(
						ShowFailResult.LOAD_AD_EXCEPTION,
						AdShowStatus.LOAD_FAIL,
					)
			}
			show(openAd)
		} catch (e: CancellationException) {
			if (!showCommitted) {
				AppStatus.isShowingAd = false
			}
			throw e
		}
	}

	fun showInter(activity: Activity, callback: ShowCallback) {

	}

	fun showVideo(activity: Activity, callback: ShowCallback) {

	}


}
