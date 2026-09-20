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
import com.mar2sdk.core.ad.status.AdFormat
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

	private fun reflectPriceMicros(ad: Any): Long? = when (ad) {
		is AppOpenAd -> ad.reflectPrice
		is InterstitialAd -> ad.reflectPrice
		is RewardedAd -> ad.reflectPrice
		else -> null
	}?.valueMicros

	private suspend fun waitForMinimumShowTime(startedAtMs: Long, minimumTimeMs: Long) {
		val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
		val remainingMs = minimumTimeMs - elapsedMs
		if (remainingMs > 0L) delay(remainingMs)
	}

	/**
	 *  广告展示(开屏 & 插屏)
	 *  限制：
	 *  1. 整个APP只允许同时展示1个全屏广告(开屏/插屏/视频)，使用 AppStatus.isShowingAd 控制
	 *  1.1 如果其他广告正在展示或者正在等待加载完展示则返回 AdShowStatus.OTHER_AD_IS_SHOWING
	 *  2. 一直等待到ad.show调用方法或等待超时方法再返回 AdShowStatus
	 *  2.1 等待超时 返回 AdShowStatus.TIMEOUT，并后续广告加载成功不调用ad.show 方法
	 *
	 *  展示规则
	 *  1. 如果开屏广告池和插屏广告池里都已经有加载好的广告, 则比价后播价格高的广告。
	 *  2. 如果开屏广告池有广告，插屏广告池没广告, 则等待插屏广告加载。如果到最大等待时间插屏广告还没加载出来，就播开屏。如果在最大等待时间内加载出来了就进行比价播价格高的广告。
	 *  3. 如果开屏广告池没广告, 插屏广告池有广告, 则等待开屏广告加载。如果到最大等待时间开屏广告还没加载出来，就播插屏。如果在最大等待时间内加载出来了就进行比价播价格高的广告。
	 *  4. 如果开屏和插屏广告池都没有广告，则等待开屏和插屏广告加载，如果在最大等待时间内都加载出来了则播价格高的广告，如果只加载出来一个就播加载出来的那个广告。
	 */
	suspend fun showOpenInter(activity: Activity, callback: ShowCallback): AdShowStatus = withContext(Dispatchers.Main.immediate) {
		LogUtil.log(LogAdEvent.ad_occur, callback.adContext.toAdLogParams())
		if (AppStatus.isShowingAd) {
			callback.showFailed(ShowFailResult.OTHER_AD_IS_SHOWING)
			return@withContext AdShowStatus.OTHER_AD_IS_SHOWING
		}
		AppStatus.isShowingAd = true
		val startShowTime = SystemClock.elapsedRealtime()
		val minimumShowTime = showMinTime.coerceAtLeast(0L)
		val maximumWaitTime = showMaxTime.coerceAtLeast(0L)
		val finished = AtomicBoolean(false)
		var showCommitted = false
		var showFailed = false
		var loadFailure = ShowFailResult.LOAD_FAILED
		var adSource = LogAdParam.unknow

		fun fail(reason: ShowFailResult, status: AdShowStatus = AdShowStatus.SHOW_FAIL): AdShowStatus {
			if (finished.compareAndSet(false, true)) {
				showFailed = true
				AppStatus.isShowingAd = false
				callback.showFailed(reason)
			}
			return status
		}

		fun logShowEvent(eventName: String) {
			try {
				LogUtil.log(
					eventName,
					callback.adContext.toAdLogParams() + mapOf(
						LogAdParam.duration to (SystemClock.elapsedRealtime() - startShowTime),
						LogAdParam.ad_source to adSource,
					)
				)
			} catch (e: Exception) {
				Log.e(TAG, "Failed to log open/interstitial ad event: $eventName", e)
			}
		}

		try {
			if (activity.isFinishing || activity.isDestroyed) {
				return@withContext fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			AdmobLoader.checkPool(AdFormat.OPEN)
			AdmobLoader.checkPool(AdFormat.INTER)
			fun bestOpenAd() = AdmobLoader.openPool.keys.maxByOrNull {
				it.reflectPrice?.valueMicros ?: Long.MIN_VALUE
			}
			fun bestInterAd() = AdmobLoader.interPool.keys.maxByOrNull {
				it.reflectPrice?.valueMicros ?: Long.MIN_VALUE
			}
			val selection = selectAdPair<Any>(
				primaryAd = bestOpenAd(),
				secondaryAd = bestInterAd(),
				maxWaitTimeMs = (maximumWaitTime - (SystemClock.elapsedRealtime() - startShowTime)).coerceAtLeast(0L),
				loadPrimary = {
					val result = try {
						AdmobLoader.loadOpenResult(callback.adContext.copy(
							adFormat = AdFormat.OPEN, adUnitId = AdmobConfig.openID,
						))
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						AdmobLoader.OpenLoadResult.Failed(exception = e)
					}
					when (result) {
						is AdmobLoader.OpenLoadResult.Loaded -> result.ad
						is AdmobLoader.OpenLoadResult.Failed -> {
							if (result.loadError == null) loadFailure = ShowFailResult.LOAD_AD_EXCEPTION
							Log.e(TAG, "Open ad load failed: ${result.loadError?.message}", result.exception)
							null
						}
						AdmobLoader.OpenLoadResult.PoolFull -> AdmobLoader.openPool.keys.firstOrNull()
					}
				},
				loadSecondary = {
					val result = try {
						AdmobLoader.loadInterResult(callback.adContext.copy(
							adFormat = AdFormat.INTER, adUnitId = AdmobConfig.interID,
						))
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						AdmobLoader.InterLoadResult.Failed(exception = e)
					}
					when (result) {
						is AdmobLoader.InterLoadResult.Loaded -> result.ad
						is AdmobLoader.InterLoadResult.Failed -> {
							if (result.loadError == null) loadFailure = ShowFailResult.LOAD_AD_EXCEPTION
							Log.e(TAG, "Interstitial ad load failed: ${result.loadError?.message}", result.exception)
							null
						}
						AdmobLoader.InterLoadResult.PoolFull -> AdmobLoader.interPool.keys.firstOrNull()
					}
				},
				priceMicros = ::reflectPriceMicros,
			)
			fun noAvailableAd(): AdShowStatus {
				if (selection.timedOut) {
					logShowEvent(LogAdEvent.ad_show_timeout)
					return fail(ShowFailResult.LOAD_TIMEOUT, AdShowStatus.TIMEOUT)
				}
				return fail(loadFailure, AdShowStatus.LOAD_FAIL)
			}
			if (selection.ad == null) return@withContext noAvailableAd()
			if (activity.isFinishing || activity.isDestroyed) {
				return@withContext fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			waitForMinimumShowTime(startShowTime, minimumShowTime)
			currentCoroutineContext().ensureActive()
			if (activity.isFinishing || activity.isDestroyed) {
				return@withContext fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			// 等待期间缓存可能过期或被后台补池替换，展示前重新比价。
			AdmobLoader.checkPool(AdFormat.OPEN)
			AdmobLoader.checkPool(AdFormat.INTER)
			val ad = higherPricedAd<Any>(bestOpenAd(), bestInterAd()) {
				reflectPriceMicros(it)
			} ?: return@withContext noAvailableAd()
			when (ad) {
				is AppOpenAd -> {
					callback.adContext.adFormat = AdFormat.OPEN
					callback.adContext.adUnitId = ad.adUnitId
					adSource = ad.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow
				}
				is InterstitialAd -> {
					callback.adContext.adFormat = AdFormat.INTER
					callback.adContext.adUnitId = ad.adUnitId
					adSource = ad.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow
				}
			}
			val fillPoolStarted = AtomicBoolean(false)
			fun fillPoolInBackground() {
				if (!fillPoolStarted.compareAndSet(false, true)) return
				adScope.launch {
					try {
						when (ad) {
							is AppOpenAd -> AdmobLoader.fillOpen()
							is InterstitialAd -> AdmobLoader.fillInter()
						}
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						Log.e(TAG, "Failed to fill open/interstitial ad pool", e)
					}
				}
			}
			val contentCallback = object : FullScreenContentCallback() {
				override fun onAdFailedToShowFullScreenContent(error: AdError) {
					if (finished.get()) return
					fillPoolInBackground()
					logShowEvent(LogAdEvent.ad_show_fail)
					fail(ShowFailResult.FAILED_TO_SHOW_CONTENT)
				}

				override fun onAdDismissedFullScreenContent() {
					if (!finished.compareAndSet(false, true)) return
					logShowEvent(LogAdEvent.ad_close)
					AppStatus.isShowingAd = false
					callback.onAdClosed()
				}

				override fun onAdImpression() {
					if (finished.get()) return
					fillPoolInBackground()
					callback.showSuccess()
				}

				override fun onAdClicked() {
					if (finished.get()) return
					logShowEvent(LogAdEvent.ad_click)
					callback.onClicked()
				}
			}
			val paidCallback = OnPaidEventListener { adValue ->
				val revenue = adValue.valueMicros / 1_000_000.0
				val revenueParams = callback.adContext.toAdLogParams(FirebaseAnalytics.Param.AD_FORMAT) + mapOf(
					FirebaseAnalytics.Param.AD_SOURCE to adSource,
					FirebaseAnalytics.Param.CURRENCY to adValue.currencyCode,
					FirebaseAnalytics.Param.VALUE to revenue,
				)
				LogUtil.log(LogAdEvent.ad_impression, revenueParams)
				LogUtil.log(LogAdEvent.ad_revenue, revenueParams)
				LogUtil.logSingularAdRevenue(callback.adContext, revenue)
				callback.onPaid()
			}

			showCommitted = true
			try {
				// 只消费胜出的广告，另一类广告继续留在池中。
				when (ad) {
					is AppOpenAd -> {
						AdmobLoader.openPool.remove(ad)
						ad.fullScreenContentCallback = contentCallback
						ad.onPaidEventListener = paidCallback
						ad.show(activity)
					}
					is InterstitialAd -> {
						AdmobLoader.interPool.remove(ad)
						ad.fullScreenContentCallback = contentCallback
						ad.onPaidEventListener = paidCallback
						ad.show(activity)
					}
				}
			} finally {
				fillPoolInBackground()
			}
			if (showFailed) AdShowStatus.SHOW_FAIL else AdShowStatus.SHOW_SUCCESS
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.e(TAG, "showOpenInter failed", e)
			fail(ShowFailResult.SHOW_AD_EXCEPTION)
		} finally {
			if (!showCommitted && finished.compareAndSet(false, true)) {
				AppStatus.isShowingAd = false
			}
		}

	}

	/**
	 * 广告展示（插屏 & 视频），等待和比价规则与 showOpenInter 一致。
	 * 限制：
	 * 1. 等待加载和展示期间占用 AppStatus.isShowingAd，其他全屏广告返回 OTHER_AD_IS_SHOWING。
	 * 2. 调用 ad.show 或加载失败、超时后才返回；超时后加载成功的广告只入池，不自动展示。
	 *
	 * 展示规则：
	 * 1. 两个池都有广告时比价，展示价格更高的广告；同价或缺少有效价格时优先插屏。
	 * 2. 只有插屏时等待视频加载，成功后比价；达到最大等待时间仍无视频则展示插屏。
	 * 3. 只有视频时等待插屏加载，成功后比价；达到最大等待时间仍无插屏则展示视频。
	 * 4. 两个池都为空时并行加载，共用最大等待时间；都成功则比价，只成功一个则展示该广告。
	 *    都加载失败返回 LOAD_FAIL；超时且没有可用广告返回 TIMEOUT。
	 * 5. 加载耗时计入最小等待时间，展示前重新检查广告有效性；只消费并补充胜出广告的池。
	 * 6. 视频奖励通过 SDK 奖励回调转发给 onReward。
	 */
	suspend fun showInterVideo(activity: Activity, callback: ShowCallback): AdShowStatus =
		showInterVideoInternal(activity, callback, preferVideoOnTie = false)

	/**
	 * 广告展示（视频 & 插屏），等待和比价规则与 showInterVideo 一致。
	 * 同价或缺少有效价格时优先视频。
	 */
	suspend fun showVideoInter(activity: Activity, callback: ShowCallback): AdShowStatus =
		showInterVideoInternal(activity, callback, preferVideoOnTie = true)

	private suspend fun showInterVideoInternal(
		activity: Activity,
		callback: ShowCallback,
		preferVideoOnTie: Boolean,
	): AdShowStatus = withContext(Dispatchers.Main.immediate) {
		LogUtil.log(LogAdEvent.ad_occur, callback.adContext.toAdLogParams())
		if (AppStatus.isShowingAd) {
			callback.showFailed(ShowFailResult.OTHER_AD_IS_SHOWING)
			return@withContext AdShowStatus.OTHER_AD_IS_SHOWING
		}
		AppStatus.isShowingAd = true
		val startShowTime = SystemClock.elapsedRealtime()
		val minimumShowTime = showMinTime.coerceAtLeast(0L)
		val maximumWaitTime = showMaxTime.coerceAtLeast(0L)
		val finished = AtomicBoolean(false)
		var showCommitted = false
		var showFailed = false
		var loadFailure = ShowFailResult.LOAD_FAILED
		var adSource = LogAdParam.unknow

		fun fail(reason: ShowFailResult, status: AdShowStatus = AdShowStatus.SHOW_FAIL): AdShowStatus {
			if (finished.compareAndSet(false, true)) {
				showFailed = true
				AppStatus.isShowingAd = false
				callback.showFailed(reason)
			}
			return status
		}

		fun logShowEvent(eventName: String) {
			try {
				LogUtil.log(
					eventName,
					callback.adContext.toAdLogParams() + mapOf(
						LogAdParam.duration to (SystemClock.elapsedRealtime() - startShowTime),
						LogAdParam.ad_source to adSource,
					)
				)
			} catch (e: Exception) {
				Log.e(TAG, "Failed to log interstitial/rewarded ad event: $eventName", e)
			}
		}

		try {
			if (activity.isFinishing || activity.isDestroyed) {
				return@withContext fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			AdmobLoader.checkPool(AdFormat.INTER)
			AdmobLoader.checkPool(AdFormat.VIDEO)
			fun bestInterAd() = AdmobLoader.interPool.keys.maxByOrNull {
				it.reflectPrice?.valueMicros ?: Long.MIN_VALUE
			}
			fun bestVideoAd() = AdmobLoader.videoPool.keys.maxByOrNull {
				it.reflectPrice?.valueMicros ?: Long.MIN_VALUE
			}
			suspend fun loadInterAd(): InterstitialAd? {
				val result = try {
					AdmobLoader.loadInterResult(callback.adContext.copy(
						adFormat = AdFormat.INTER, adUnitId = AdmobConfig.interID,
					))
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					AdmobLoader.InterLoadResult.Failed(exception = e)
				}
				return when (result) {
					is AdmobLoader.InterLoadResult.Loaded -> result.ad
					is AdmobLoader.InterLoadResult.Failed -> {
						if (result.loadError == null) loadFailure = ShowFailResult.LOAD_AD_EXCEPTION
						Log.e(TAG, "Interstitial ad load failed: ${result.loadError?.message}", result.exception)
						null
					}
					AdmobLoader.InterLoadResult.PoolFull -> AdmobLoader.interPool.keys.firstOrNull()
				}
			}
			suspend fun loadVideoAd(): RewardedAd? {
				val result = try {
					AdmobLoader.loadVideoResult(callback.adContext.copy(
						adFormat = AdFormat.VIDEO, adUnitId = AdmobConfig.videoID,
					))
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					AdmobLoader.VideoLoadResult.Failed(exception = e)
				}
				return when (result) {
					is AdmobLoader.VideoLoadResult.Loaded -> result.ad
					is AdmobLoader.VideoLoadResult.Failed -> {
						if (result.loadError == null) loadFailure = ShowFailResult.LOAD_AD_EXCEPTION
						Log.e(TAG, "Rewarded ad load failed: ${result.loadError?.message}", result.exception)
						null
					}
					AdmobLoader.VideoLoadResult.PoolFull -> AdmobLoader.videoPool.keys.firstOrNull()
				}
			}
			val selection = selectAdPair<Any>(
				primaryAd = if (preferVideoOnTie) bestVideoAd() else bestInterAd(),
				secondaryAd = if (preferVideoOnTie) bestInterAd() else bestVideoAd(),
				maxWaitTimeMs = (maximumWaitTime - (SystemClock.elapsedRealtime() - startShowTime)).coerceAtLeast(0L),
				loadPrimary = { if (preferVideoOnTie) loadVideoAd() else loadInterAd() },
				loadSecondary = { if (preferVideoOnTie) loadInterAd() else loadVideoAd() },
				priceMicros = ::reflectPriceMicros,
			)
			fun noAvailableAd(): AdShowStatus {
				if (selection.timedOut) {
					logShowEvent(LogAdEvent.ad_show_timeout)
					return fail(ShowFailResult.LOAD_TIMEOUT, AdShowStatus.TIMEOUT)
				}
				return fail(loadFailure, AdShowStatus.LOAD_FAIL)
			}
			if (selection.ad == null) return@withContext noAvailableAd()
			if (activity.isFinishing || activity.isDestroyed) {
				return@withContext fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			waitForMinimumShowTime(startShowTime, minimumShowTime)
			currentCoroutineContext().ensureActive()
			if (activity.isFinishing || activity.isDestroyed) {
				return@withContext fail(ShowFailResult.ACTIVITY_IS_FINISHING)
			}
			// 等待期间缓存可能过期或被后台补池替换，展示前重新比价。
			AdmobLoader.checkPool(AdFormat.INTER)
			AdmobLoader.checkPool(AdFormat.VIDEO)
			val ad = if (preferVideoOnTie) {
				higherPricedAd<Any>(bestVideoAd(), bestInterAd()) { reflectPriceMicros(it) }
			} else {
				higherPricedAd<Any>(bestInterAd(), bestVideoAd()) { reflectPriceMicros(it) }
			} ?: return@withContext noAvailableAd()
			when (ad) {
				is InterstitialAd -> {
					callback.adContext.adFormat = AdFormat.INTER
					callback.adContext.adUnitId = ad.adUnitId
					adSource = ad.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow
				}
				is RewardedAd -> {
					callback.adContext.adFormat = AdFormat.VIDEO
					callback.adContext.adUnitId = ad.adUnitId
					adSource = ad.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow
				}
			}
			val fillPoolStarted = AtomicBoolean(false)
			fun fillPoolInBackground() {
				if (!fillPoolStarted.compareAndSet(false, true)) return
				adScope.launch {
					try {
						when (ad) {
							is InterstitialAd -> AdmobLoader.fillInter()
							is RewardedAd -> AdmobLoader.fillVideo()
						}
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						Log.e(TAG, "Failed to fill interstitial/rewarded ad pool", e)
					}
				}
			}
			val contentCallback = object : FullScreenContentCallback() {
				override fun onAdFailedToShowFullScreenContent(error: AdError) {
					if (finished.get()) return
					fillPoolInBackground()
					logShowEvent(LogAdEvent.ad_show_fail)
					fail(ShowFailResult.FAILED_TO_SHOW_CONTENT)
				}

				override fun onAdDismissedFullScreenContent() {
					if (!finished.compareAndSet(false, true)) return
					logShowEvent(LogAdEvent.ad_close)
					AppStatus.isShowingAd = false
					callback.onAdClosed()
				}

				override fun onAdImpression() {
					if (finished.get()) return
					fillPoolInBackground()
					callback.showSuccess()
				}

				override fun onAdClicked() {
					if (finished.get()) return
					logShowEvent(LogAdEvent.ad_click)
					callback.onClicked()
				}
			}
			val paidCallback = OnPaidEventListener { adValue ->
				val revenue = adValue.valueMicros / 1_000_000.0
				val revenueParams = callback.adContext.toAdLogParams(FirebaseAnalytics.Param.AD_FORMAT) + mapOf(
					FirebaseAnalytics.Param.AD_SOURCE to adSource,
					FirebaseAnalytics.Param.CURRENCY to adValue.currencyCode,
					FirebaseAnalytics.Param.VALUE to revenue,
				)
				LogUtil.log(LogAdEvent.ad_impression, revenueParams)
				LogUtil.log(LogAdEvent.ad_revenue, revenueParams)
				LogUtil.logSingularAdRevenue(callback.adContext, revenue)
				callback.onPaid()
			}

			showCommitted = true
			try {
				// 只消费胜出的广告，另一类广告继续留在池中。
				when (ad) {
					is InterstitialAd -> {
						AdmobLoader.interPool.remove(ad)
						ad.fullScreenContentCallback = contentCallback
						ad.onPaidEventListener = paidCallback
						ad.show(activity)
					}
					is RewardedAd -> {
						AdmobLoader.videoPool.remove(ad)
						ad.fullScreenContentCallback = contentCallback
						ad.onPaidEventListener = paidCallback
						ad.show(activity) {
							callback.onReward()
						}
					}
				}
			} finally {
				fillPoolInBackground()
			}
			if (showFailed) AdShowStatus.SHOW_FAIL else AdShowStatus.SHOW_SUCCESS
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.e(TAG, "${if (preferVideoOnTie) "showVideoInter" else "showInterVideo"} failed", e)
			fail(ShowFailResult.SHOW_AD_EXCEPTION)
		} finally {
			if (!showCommitted && finished.compareAndSet(false, true)) {
				AppStatus.isShowingAd = false
			}
		}

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
