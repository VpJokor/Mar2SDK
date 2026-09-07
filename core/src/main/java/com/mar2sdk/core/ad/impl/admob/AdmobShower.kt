package com.mar2sdk.core.ad.impl.admob

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.firebase.analytics.FirebaseAnalytics
import com.inmobi.media.Bo
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.ad.AdConfig.showMaxTime
import com.mar2sdk.core.ad.AdConfig.showMinTime
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.AdShowStatus
import com.mar2sdk.core.ad.status.ShowFailResult
import com.mar2sdk.core.log.LogAdEvent
import com.mar2sdk.core.log.LogAdParam
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.log.toAdLogParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

	private suspend fun waitForMinimumShowTime(startedAtMs: Long, minimumTimeMs: Long) {
		val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
		val remainingMs = minimumTimeMs - elapsedMs
		if (remainingMs > 0L) delay(remainingMs)
	}

	/**
	 *  广告展示(开屏 & 插屏比价)
	 *  限制：
	 *  1. 整个APP只允许同时展示1个全屏广告(开屏/插屏/视频)，使用 AppStatus.isShowingAd 控制
	 *  1.1 如果其他广告正在展示或者正在等待加载完展示则返回 AdShowStatus.OTHER_AD_IS_SHOWING
	 *  2. 一直等待到ad.show调用方法或等待超时方法再返回 AdShowStatus
	 *  2.1 等待超时 返回 AdShowStatus.TIMEOUT，并后续广告加载成功不调用ad.show 方法
	 *
	 *  展示规则
	 *  1. 如果广告池里已经有加载好的广告，则满足最小等待时间后展示，并返回 AdShowStatus
	 *  2. 如果广告池里没有广告且正在加载广告，则加载完毕且满足最小等待时间后展示(如果超时则放入广告池不展示)，并返回 AdShowStatus
	 *  3. 如果广告池里没有广告且没有正在加载的广告，则开始加载广告，加载完毕且满足最小等待时间后展示(如果超时则放入广告池不展示)，并返回 AdShowStatus
	 *  4. 广告加载耗时计入最小等待时间
	 */
	suspend fun showOpenInter(activity: Activity, callback: ShowCallback): AdShowStatus = withContext(Dispatchers.Main.immediate) {
		// TODO: 暂时还没想好实现方案先搁置
		return@withContext AdShowStatus.SHOW_SUCCESS

	}

	suspend fun showInterVideo(activity: Activity, callback: ShowCallback): AdShowStatus = withContext(Dispatchers.Main.immediate) {
		// TODO: 暂时还没想好实现方案先搁置
		return@withContext AdShowStatus.SHOW_SUCCESS
	}

	/**
	 *  广告展示（开屏/插屏/视频）
	 *  限制：
	 *  1. 整个APP只允许同时展示1个全屏广告(开屏/插屏/视频)，使用 AppStatus.isShowingAd 控制
	 *  1.1 如果其他广告正在展示或者正在等待加载完展示则返回 AdShowStatus.OTHER_AD_IS_SHOWING
	 *  2. 一直等待到ad.show调用方法或等待超时方法再返回 AdShowStatus
	 *  2.1 等待超时 返回 AdShowStatus.TIMEOUT，并后续广告加载成功不调用ad.show 方法
	 *
	 *  展示规则
	 *  1. 如果广告池里已经有加载好的广告，则满足最小等待时间后展示，并返回 AdShowStatus
	 *  2. 如果广告池里没有广告且正在加载广告，则加载完毕且满足最小等待时间后展示(如果超时则放入广告池不展示)，并返回 AdShowStatus
	 *  3. 如果广告池里没有广告且没有正在加载的广告，则开始加载广告，加载完毕且满足最小等待时间后展示(如果超时则放入广告池不展示)，并返回 AdShowStatus
	 *  4. 广告加载耗时计入最小等待时间
	 */
	suspend fun showOpen(activity: Activity, callback: ShowCallback): AdShowStatus = withContext(Dispatchers.Main.immediate) {
		callback.adContext.adUnitId = AdmobConfig.openID
		LogUtil.log(LogAdEvent.ad_occur, callback.adContext.toAdLogParams())
		if (AppStatus.isShowingAd) {
			Log.e(TAG, "showOpen: AppStatus.isShowingAd" )
			callback.showFailed(ShowFailResult.OTHER_AD_IS_SHOWING)
			return@withContext AdShowStatus.OTHER_AD_IS_SHOWING
		}
		//检查广告池广告是否过期
		AdmobLoader.checkPool(callback.adContext.adFormat)

		//修改APP状态
		AppStatus.isShowingAd = true
		val startShowTime = SystemClock.elapsedRealtime()
		val minimumShowTime = showMinTime.coerceAtLeast(0L)
		var currentOpenAd: AppOpenAd? = null
		val showFailed = AtomicBoolean(false)
		val fillOpenPoolStarted = AtomicBoolean(false)
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
				callback.adContext.toAdLogParams() + mapOf(
					LogAdParam.duration to (SystemClock.elapsedRealtime() - startShowTime),
					LogAdParam.ad_source to (currentOpenAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
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

		fun fillOpenPoolInBackground() {
			if (!fillOpenPoolStarted.compareAndSet(false, true)) return
			adScope.launch {
				try {
					AdmobLoader.fillOpen()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.e(TAG, "Failed to fill open ad pool: ", e)
				}
			}
		}

		// INFO: 处理广告展示回调
		val contentCallback = object : FullScreenContentCallback() {
			override fun onAdFailedToShowFullScreenContent(p0: AdError) {
				// 后台填满开屏广告池
				fillOpenPoolInBackground()
				logShowEventSafely(LogAdEvent.ad_show_fail, "Failed to log open ad show failure")
				fail(ShowFailResult.FAILED_TO_SHOW_CONTENT)
			}

			override fun onAdDismissedFullScreenContent() {
				logShowEventSafely(LogAdEvent.ad_close, "Failed to log open ad close")
				AppStatus.isShowingAd = false
				callback.onAdClosed()
			}

			override fun onAdImpression() {
				// 后台填满开屏广告池
				fillOpenPoolInBackground()
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
			val revenueParams = callback.adContext.toAdLogParams(FirebaseAnalytics.Param.AD_FORMAT) + mapOf(
				FirebaseAnalytics.Param.AD_SOURCE to (currentOpenAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
				FirebaseAnalytics.Param.CURRENCY to adValue.currencyCode,
				FirebaseAnalytics.Param.VALUE to revenue,
			)
			LogUtil.log(LogAdEvent.ad_impression, revenueParams)
			LogUtil.log(LogAdEvent.ad_revenue, revenueParams)
			LogUtil.logSingularAdRevenue(callback.adContext, revenue)
			callback.onPaid()
		}

		suspend fun show(ad: AppOpenAd): AdShowStatus {
			currentCoroutineContext().ensureActive()
			callback.adContext.adUnitId = ad.adUnitId
			if (activity.isFinishing || activity.isDestroyed) {
				return fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			waitForMinimumShowTime(startShowTime, minimumShowTime)
			if (activity.isFinishing || activity.isDestroyed) {
				return fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			currentOpenAd = ad
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
			fillOpenPoolInBackground()
			return showStatus
		}

		try {
			val openAd = AdmobLoader.openPool.keys.firstOrNull() ?: when (
				val loadResult = try {
					withTimeoutOrNull(showMaxTime) {
						AdmobLoader.loadOpenResult(adContext = callback.adContext)
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

	suspend fun showInter(activity: Activity, callback: ShowCallback): AdShowStatus = withContext(Dispatchers.Main.immediate) {
		callback.adContext.adUnitId = AdmobConfig.interID
		LogUtil.log(LogAdEvent.ad_occur, callback.adContext.toAdLogParams())
		if (AppStatus.isShowingAd) {
			Log.e(TAG, "showInter: AppStatus.isShowingAd")
			callback.showFailed(ShowFailResult.OTHER_AD_IS_SHOWING)
			return@withContext AdShowStatus.OTHER_AD_IS_SHOWING
		}
		//检查广告池广告是否过期
		AdmobLoader.checkPool(callback.adContext.adFormat)

		//修改APP状态
		AppStatus.isShowingAd = true
		val startShowTime = SystemClock.elapsedRealtime()
		val minimumShowTime = showMinTime.coerceAtLeast(0L)
		var currentInterAd: InterstitialAd? = null
		val showFailed = AtomicBoolean(false)
		val fillInterPoolStarted = AtomicBoolean(false)
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
				callback.adContext.toAdLogParams() + mapOf(
					LogAdParam.duration to (SystemClock.elapsedRealtime() - startShowTime),
					LogAdParam.ad_source to (currentInterAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
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

		fun fillInterPoolInBackground() {
			if (!fillInterPoolStarted.compareAndSet(false, true)) return
			adScope.launch {
				try {
					AdmobLoader.fillInter()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.e(TAG, "Failed to fill interstitial ad pool: ", e)
				}
			}
		}

		// INFO: 处理广告展示回调
		val contentCallback = object : FullScreenContentCallback() {
			override fun onAdFailedToShowFullScreenContent(p0: AdError) {
				// 后台填满插屏广告池
				fillInterPoolInBackground()
				logShowEventSafely(LogAdEvent.ad_show_fail, "Failed to log interstitial ad show failure")
				fail(ShowFailResult.FAILED_TO_SHOW_CONTENT)
			}

			override fun onAdDismissedFullScreenContent() {
				logShowEventSafely(LogAdEvent.ad_close, "Failed to log interstitial ad close")
				AppStatus.isShowingAd = false
				callback.onAdClosed()
			}

			override fun onAdImpression() {
				// 后台填满插屏广告池
				fillInterPoolInBackground()
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
			Log.e(TAG, "showInter: $adValue")
			// info: 处理收入打点
			val revenue = adValue.valueMicros / 1_000_000.0
			val revenueParams = callback.adContext.toAdLogParams(FirebaseAnalytics.Param.AD_FORMAT) + mapOf(
				FirebaseAnalytics.Param.AD_SOURCE to (currentInterAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
				FirebaseAnalytics.Param.CURRENCY to adValue.currencyCode,
				FirebaseAnalytics.Param.VALUE to revenue,
			)
			LogUtil.log(LogAdEvent.ad_impression, revenueParams)
			LogUtil.log(LogAdEvent.ad_revenue, revenueParams)
			LogUtil.logSingularAdRevenue(callback.adContext, revenue)
			callback.onPaid()
		}

		suspend fun show(ad: InterstitialAd): AdShowStatus {
			currentCoroutineContext().ensureActive()
			callback.adContext.adUnitId = ad.adUnitId
			if (activity.isFinishing || activity.isDestroyed) {
				return fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			waitForMinimumShowTime(startShowTime, minimumShowTime)
			if (activity.isFinishing || activity.isDestroyed) {
				return fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			currentInterAd = ad
			showCommitted = true
			val showStatus = try {
				// 广告只能展示一次，展示前从池中移除
				AdmobLoader.interPool.remove(ad)
				ad.fullScreenContentCallback = contentCallback
				ad.onPaidEventListener = paidCallback
				ad.show(activity)
				if (showFailed.get()) AdShowStatus.SHOW_FAIL else AdShowStatus.SHOW_SUCCESS
			} catch (e: Exception) {
				Log.e(TAG, "show: ", e)
				fail(ShowFailResult.SHOW_AD_EXCEPTION)
			}
			fillInterPoolInBackground()
			return showStatus
		}

		try {
			val interAd = AdmobLoader.interPool.keys.firstOrNull() ?: when (
				val loadResult = try {
					withTimeoutOrNull(showMaxTime) {
						AdmobLoader.loadInterResult(adContext = callback.adContext)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					AdmobLoader.InterLoadResult.Failed(exception = e)
				}
			) {
				null -> {
					logShowEventSafely(LogAdEvent.ad_show_timeout, "Failed to log interstitial ad timeout")
					return@withContext fail(ShowFailResult.LOAD_TIMEOUT, AdShowStatus.TIMEOUT)
				}
				is AdmobLoader.InterLoadResult.Loaded -> loadResult.ad
				is AdmobLoader.InterLoadResult.Failed -> {
					val failResult = if (loadResult.loadError != null) {
						Log.e(TAG, "Interstitial ad load failed: ${loadResult.loadError.message}")
						ShowFailResult.LOAD_FAILED
					} else {
						loadResult.exception?.let {
							Log.e(TAG, "showInter: ", it)
						}
						ShowFailResult.LOAD_AD_EXCEPTION
					}
					return@withContext fail(failResult, AdShowStatus.LOAD_FAIL)
				}
				AdmobLoader.InterLoadResult.PoolFull ->
					AdmobLoader.interPool.keys.firstOrNull() ?: return@withContext fail(
						ShowFailResult.LOAD_AD_EXCEPTION,
						AdShowStatus.LOAD_FAIL,
					)
			}
			show(interAd)
		} catch (e: CancellationException) {
			if (!showCommitted) {
				AppStatus.isShowingAd = false
			}
			throw e
		}
	}

	suspend fun showVideo(activity: Activity, callback: ShowCallback): AdShowStatus = withContext(Dispatchers.Main.immediate) {
		callback.adContext.adUnitId = AdmobConfig.videoID
		LogUtil.log(LogAdEvent.ad_occur, callback.adContext.toAdLogParams())
		if (AppStatus.isShowingAd) {
			Log.e(TAG, "showVideo: AppStatus.isShowingAd")
			callback.showFailed(ShowFailResult.OTHER_AD_IS_SHOWING)
			return@withContext AdShowStatus.OTHER_AD_IS_SHOWING
		}
		//检查广告池广告是否过期
		AdmobLoader.checkPool(callback.adContext.adFormat)

		//修改APP状态
		AppStatus.isShowingAd = true
		val startShowTime = SystemClock.elapsedRealtime()
		val minimumShowTime = showMinTime.coerceAtLeast(0L)
		var currentVideoAd: RewardedAd? = null
		val showFailed = AtomicBoolean(false)
		val fillVideoPoolStarted = AtomicBoolean(false)
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
				callback.adContext.toAdLogParams() + mapOf(
					LogAdParam.duration to (SystemClock.elapsedRealtime() - startShowTime),
					LogAdParam.ad_source to (currentVideoAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
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

		fun fillVideoPoolInBackground() {
			if (!fillVideoPoolStarted.compareAndSet(false, true)) return
			adScope.launch {
				try {
					AdmobLoader.fillVideo()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.e(TAG, "Failed to fill rewarded ad pool: ", e)
				}
			}
		}

		// INFO: 处理广告展示回调
		val contentCallback = object : FullScreenContentCallback() {
			override fun onAdFailedToShowFullScreenContent(p0: AdError) {
				// 后台填满视频广告池
				fillVideoPoolInBackground()
				logShowEventSafely(LogAdEvent.ad_show_fail, "Failed to log rewarded ad show failure")
				fail(ShowFailResult.FAILED_TO_SHOW_CONTENT)
			}

			override fun onAdDismissedFullScreenContent() {
				logShowEventSafely(LogAdEvent.ad_close, "Failed to log rewarded ad close")
				AppStatus.isShowingAd = false
				callback.onAdClosed()
			}

			override fun onAdImpression() {
				// 后台填满视频广告池
				fillVideoPoolInBackground()
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
			Log.e(TAG, "showVideo: $adValue")
			// info: 处理收入打点
			val revenue = adValue.valueMicros / 1_000_000.0
			val revenueParams = callback.adContext.toAdLogParams(FirebaseAnalytics.Param.AD_FORMAT) + mapOf(
				FirebaseAnalytics.Param.AD_SOURCE to (currentVideoAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
				FirebaseAnalytics.Param.CURRENCY to adValue.currencyCode,
				FirebaseAnalytics.Param.VALUE to revenue,
			)
			LogUtil.log(LogAdEvent.ad_impression, revenueParams)
			LogUtil.log(LogAdEvent.ad_revenue, revenueParams)
			LogUtil.logSingularAdRevenue(callback.adContext, revenue)
			callback.onPaid()
		}

		suspend fun show(ad: RewardedAd): AdShowStatus {
			currentCoroutineContext().ensureActive()
			callback.adContext.adUnitId = ad.adUnitId
			if (activity.isFinishing || activity.isDestroyed) {
				return fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			waitForMinimumShowTime(startShowTime, minimumShowTime)
			if (activity.isFinishing || activity.isDestroyed) {
				return fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			currentVideoAd = ad
			showCommitted = true
			val showStatus = try {
				// 广告只能展示一次，展示前从池中移除
				AdmobLoader.videoPool.remove(ad)
				ad.fullScreenContentCallback = contentCallback
				ad.onPaidEventListener = paidCallback
				ad.show(activity) {
					callback.onReward()
				}
				if (showFailed.get()) AdShowStatus.SHOW_FAIL else AdShowStatus.SHOW_SUCCESS
			} catch (e: Exception) {
				Log.e(TAG, "show: ", e)
				fail(ShowFailResult.SHOW_AD_EXCEPTION)
			}
			fillVideoPoolInBackground()
			return showStatus
		}

		try {
			val videoAd = AdmobLoader.videoPool.keys.firstOrNull() ?: when (
				val loadResult = try {
					withTimeoutOrNull(showMaxTime) {
						AdmobLoader.loadVideoResult(adContext = callback.adContext)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					AdmobLoader.VideoLoadResult.Failed(exception = e)
				}
			) {
				null -> {
					logShowEventSafely(LogAdEvent.ad_show_timeout, "Failed to log rewarded ad timeout")
					return@withContext fail(ShowFailResult.LOAD_TIMEOUT, AdShowStatus.TIMEOUT)
				}
				is AdmobLoader.VideoLoadResult.Loaded -> loadResult.ad
				is AdmobLoader.VideoLoadResult.Failed -> {
					val failResult = if (loadResult.loadError != null) {
						Log.e(TAG, "Rewarded ad load failed: ${loadResult.loadError.message}")
						ShowFailResult.LOAD_FAILED
					} else {
						loadResult.exception?.let {
							Log.e(TAG, "showVideo: ", it)
						}
						ShowFailResult.LOAD_AD_EXCEPTION
					}
					return@withContext fail(failResult, AdShowStatus.LOAD_FAIL)
				}
				AdmobLoader.VideoLoadResult.PoolFull ->
					AdmobLoader.videoPool.keys.firstOrNull() ?: return@withContext fail(
						ShowFailResult.LOAD_AD_EXCEPTION,
						AdShowStatus.LOAD_FAIL,
					)
			}
			show(videoAd)
		} catch (e: CancellationException) {
			if (!showCommitted) {
				AppStatus.isShowingAd = false
			}
			throw e
		}
	}


}
