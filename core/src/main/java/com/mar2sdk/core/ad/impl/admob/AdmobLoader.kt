package com.mar2sdk.core.ad.impl.admob

import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.policy.ScreenAdTrigger
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdLoadStatus
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.log.LogAdEvent
import com.mar2sdk.core.log.LogAdParam
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.log.toAdLogParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * admob的广告加载器
 * 同一时间只能有1个开屏，1个插屏，1个视频广告加载
 */
object AdmobLoader {
	private const val TAG = "AdmobLoader"

	internal sealed interface OpenLoadResult {
		data class Loaded(val ad: AppOpenAd) : OpenLoadResult
		data class Failed(
			val loadError: LoadAdError? = null,
			val exception: Exception? = null,
		) : OpenLoadResult
		data object PoolFull : OpenLoadResult
	}
	internal sealed interface InterLoadResult {
		data class Loaded(val ad: InterstitialAd) : InterLoadResult
		data class Failed(
			val loadError: LoadAdError? = null,
			val exception: Exception? = null,
		) : InterLoadResult
		data object PoolFull : InterLoadResult
	}
	internal sealed interface VideoLoadResult {
		data class Loaded(val ad: RewardedAd) : VideoLoadResult
		data class Failed(
			val loadError: LoadAdError? = null,
			val exception: Exception? = null,
		) : VideoLoadResult
		data object PoolFull : VideoLoadResult
	}

	// 展示等待超时后不取消实际加载，加载完成的广告仍然放入广告池
	private var openLoadDeferred: CompletableDeferred<OpenLoadResult>? = null
	private var interLoadDeferred: CompletableDeferred<InterLoadResult>? = null
	private var videoLoadDeferred: CompletableDeferred<VideoLoadResult>? = null

	// 广告池，里面放已经加载成功的广告
	val openPool = mutableMapOf<AppOpenAd, Long>()
	val interPool = mutableMapOf<InterstitialAd, Long>()
	val videoPool = mutableMapOf<RewardedAd, Long>()

	// 正在加载中的 开屏/插屏/视频 广告
	var isLoadingOpen = false
	var isLoadingInter = false
	var isLoadingVideo = false

	// 填满所有广告池 fillOpen/fillInter/fillVideo 可同时执行
	suspend fun fillPool() = coroutineScope {
		LogUtil.log(
			LogAdEvent.fill_pool,
			mapOf(
				LogAdParam.scene to LogAdParam.scene_open_app,
				LogAdParam.ad_platform to AdPlatform.ADMOB.name,

			)
		)
		launch { fillOpen() }
		launch { fillInter() }
		launch { fillVideo() }
	}

	// 等待正在加载的广告，加载完一个再加载下一个，直到广告池填满
	suspend fun fillOpen() {
		while (true) {
			when (loadOpenResult()) {
				is OpenLoadResult.Loaded -> continue
				is OpenLoadResult.Failed,
				OpenLoadResult.PoolFull -> return
			}
		}
	}

	// 等待正在加载的广告，加载完一个再加载下一个，直到广告池填满
	suspend fun fillInter() {
		while (true) {
			when (loadInterResult()) {
				is InterLoadResult.Loaded -> continue
				is InterLoadResult.Failed,
				InterLoadResult.PoolFull -> return
			}
		}
	}

	// 等待正在加载的广告，加载完一个再加载下一个，直到广告池填满
	suspend fun fillVideo() {
		while (true) {
			when (loadVideoResult()) {
				is VideoLoadResult.Loaded -> continue
				is VideoLoadResult.Failed,
				VideoLoadResult.PoolFull -> return
			}
		}
	}

	// 加载开屏
	suspend fun loadOpen(
		adContext: ScreenAdContext = ScreenAdContext(
			adFormat = AdFormat.OPEN,
			adPlatform = AdPlatform.ADMOB,
			trigger = ScreenAdTrigger.UNKNOW,
			adUnitId = AdmobConfig.openID
		)
	): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkOpenPool()
		// 有广告正在加载
		if (isLoadingOpen) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (openPool.size >= AdmobConfig.openPoolSize) return@withContext AdLoadStatus.POOL_FULL

		when (startOpenLoad(adContext).await()) {
			is OpenLoadResult.Loaded -> AdLoadStatus.LOAD_SUCCESS
			is OpenLoadResult.Failed -> AdLoadStatus.LOAD_FAIL
			OpenLoadResult.PoolFull -> AdLoadStatus.POOL_FULL
		}
	}

	internal suspend fun loadOpenResult(
		adContext: ScreenAdContext = ScreenAdContext(
			adFormat = AdFormat.OPEN,
			adPlatform = AdPlatform.ADMOB,
			trigger = ScreenAdTrigger.UNKNOW,
			adUnitId = AdmobConfig.openID
		)
	): OpenLoadResult = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkOpenPool()
		openLoadDeferred?.let { return@withContext it.await() }
		// 检查广告池是否满
		if (openPool.size >= AdmobConfig.openPoolSize) return@withContext OpenLoadResult.PoolFull
		startOpenLoad(adContext).await()
	}

	private fun startOpenLoad(
		adContext: ScreenAdContext = ScreenAdContext(
			adFormat = AdFormat.OPEN,
			adPlatform = AdPlatform.ADMOB,
			trigger = ScreenAdTrigger.UNKNOW,
			adUnitId = AdmobConfig.openID
		)
	): CompletableDeferred<OpenLoadResult> {
		val loadContext = adContext.copy()
		val loadDeferred = CompletableDeferred<OpenLoadResult>()
		openLoadDeferred = loadDeferred
		isLoadingOpen = true
		try {
			logOpenLoad(LogAdEvent.ad_start_loading, loadContext)
			val loadCallback = object : AppOpenAd.AppOpenAdLoadCallback() {
				override fun onAdLoaded(openAd: AppOpenAd) {
					try {
						logOpenLoad(LogAdEvent.ad_finish_loading, loadContext)
					} catch (error: Exception) {
						Log.e(TAG, "Failed to log loaded open ad", error)
					}
					try {
						openPool[openAd] = System.currentTimeMillis()
					} finally {
						completeOpenLoad(loadDeferred, OpenLoadResult.Loaded(openAd))
					}
				}

				override fun onAdFailedToLoad(loadAdError: LoadAdError) {
					completeOpenLoad(loadDeferred, OpenLoadResult.Failed(loadError = loadAdError))
				}
			}
			AppOpenAd.load(Core.app, adContext.adUnitId, AdRequest.Builder().build(), loadCallback)
		} catch (error: Exception) {
			Log.e(TAG, "Failed to start loading open ad", error)
			completeOpenLoad(loadDeferred, OpenLoadResult.Failed(exception = error))
		}
		return loadDeferred
	}

	private fun logOpenLoad(eventName: String, adContext: ScreenAdContext) {
		val params = adContext.toAdLogParams()
		LogUtil.log(eventName, params + mapOf(LogAdParam.ad_preload to (adContext.areaKey == "preload")))
	}

	private fun completeOpenLoad(
		loadDeferred: CompletableDeferred<OpenLoadResult>,
		result: OpenLoadResult,
	) {
		if (openLoadDeferred === loadDeferred) {
			openLoadDeferred = null
			isLoadingOpen = false
		}
		loadDeferred.complete(result)
	}

	// 加载插屏
	suspend fun loadInter(): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkInterPool()
		// 有广告正在加载
		if (isLoadingInter) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (interPool.size >= AdmobConfig.interPoolSize) return@withContext AdLoadStatus.POOL_FULL

		when (startInterLoad().await()) {
			is InterLoadResult.Loaded -> AdLoadStatus.LOAD_SUCCESS
			is InterLoadResult.Failed -> AdLoadStatus.LOAD_FAIL
			InterLoadResult.PoolFull -> AdLoadStatus.POOL_FULL
		}
	}


	internal suspend fun loadInterResult(
		adContext: ScreenAdContext = ScreenAdContext(
			adFormat = AdFormat.INTER,
			adPlatform = AdPlatform.ADMOB,
			trigger = ScreenAdTrigger.UNKNOW,
			adUnitId = AdmobConfig.interID
		)
	): InterLoadResult = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkInterPool()
		interLoadDeferred?.let { return@withContext it.await() }
		// 检查广告池是否满
		if (interPool.size >= AdmobConfig.interPoolSize) return@withContext InterLoadResult.PoolFull
		startInterLoad( adContext).await()
	}

	private fun startInterLoad(
		adContext: ScreenAdContext = ScreenAdContext(
			adFormat = AdFormat.INTER,
			adPlatform = AdPlatform.ADMOB,
			trigger = ScreenAdTrigger.UNKNOW,
			adUnitId = AdmobConfig.interID
		)
	): CompletableDeferred<InterLoadResult> {
		val loadContext = adContext.copy()
		val loadDeferred = CompletableDeferred<InterLoadResult>()
		interLoadDeferred = loadDeferred
		isLoadingInter = true
		try {
			logInterLoad(LogAdEvent.ad_start_loading, loadContext)
			val loadCallback = object : InterstitialAdLoadCallback() {
				override fun onAdLoaded(interstitialAd: InterstitialAd) {
					try {
						logInterLoad(LogAdEvent.ad_finish_loading, loadContext)
					} catch (error: Exception) {
						Log.e(TAG, "Failed to log loaded interstitial ad", error)
					}
					try {
						interPool[interstitialAd] = System.currentTimeMillis()
					} finally {
						completeInterLoad(loadDeferred, InterLoadResult.Loaded(interstitialAd))
					}
				}

				override fun onAdFailedToLoad(loadAdError: LoadAdError) {
					completeInterLoad(loadDeferred, InterLoadResult.Failed(loadError = loadAdError))
				}
			}
			InterstitialAd.load(Core.app, adContext.adUnitId, AdRequest.Builder().build(), loadCallback)
		} catch (error: Exception) {
			Log.e(TAG, "Failed to start loading interstitial ad", error)
			completeInterLoad(loadDeferred, InterLoadResult.Failed(exception = error))
		}
		return loadDeferred
	}

	private fun logInterLoad(eventName: String, adContext: ScreenAdContext) {
		val params = adContext.toAdLogParams()
		LogUtil.log(
			eventName,
			params + mapOf(
				LogAdParam.ad_preload to (adContext.areaKey == "preload"),
			)
		)
	}

	private fun completeInterLoad(
		loadDeferred: CompletableDeferred<InterLoadResult>,
		result: InterLoadResult,
	) {
		if (interLoadDeferred === loadDeferred) {
			interLoadDeferred = null
			isLoadingInter = false
		}
		loadDeferred.complete(result)
	}

	// 加载视频
	suspend fun loadVideo(): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkVideoPool()
		// 有广告正在加载
		if (isLoadingVideo) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (videoPool.size >= AdmobConfig.videoPoolSize) return@withContext AdLoadStatus.POOL_FULL

		when (startVideoLoad().await()) {
			is VideoLoadResult.Loaded -> AdLoadStatus.LOAD_SUCCESS
			is VideoLoadResult.Failed -> AdLoadStatus.LOAD_FAIL
			VideoLoadResult.PoolFull -> AdLoadStatus.POOL_FULL
		}
	}

	internal suspend fun loadVideoResult(
		adContext: ScreenAdContext = ScreenAdContext(
			adFormat = AdFormat.VIDEO,
			adPlatform = AdPlatform.ADMOB,
			trigger = ScreenAdTrigger.UNKNOW,
			adUnitId = AdmobConfig.videoID
		)
	): VideoLoadResult = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkVideoPool()
		videoLoadDeferred?.let { return@withContext it.await() }
		// 检查广告池是否满
		if (videoPool.size >= AdmobConfig.videoPoolSize) return@withContext VideoLoadResult.PoolFull
		startVideoLoad(adContext).await()
	}

	private fun startVideoLoad(
		adContext: ScreenAdContext = ScreenAdContext(
			adFormat = AdFormat.VIDEO,
			adPlatform = AdPlatform.ADMOB,
			trigger = ScreenAdTrigger.UNKNOW,
			adUnitId = AdmobConfig.videoID
		)
	): CompletableDeferred<VideoLoadResult> {
		val adUnitId = AdmobConfig.videoID
		val loadContext = adContext.copy(adUnitId = adUnitId)
		val loadDeferred = CompletableDeferred<VideoLoadResult>()
		videoLoadDeferred = loadDeferred
		isLoadingVideo = true
		try {
			logVideoLoad(LogAdEvent.ad_start_loading, loadContext)
			val loadCallback = object : RewardedAdLoadCallback() {
				override fun onAdLoaded(rewardedAd: RewardedAd) {
					try {
						logVideoLoad(LogAdEvent.ad_finish_loading, loadContext)
					} catch (error: Exception) {
						Log.e(TAG, "Failed to log loaded rewarded ad", error)
					}
					try {
						videoPool[rewardedAd] = System.currentTimeMillis()
					} finally {
						completeVideoLoad(loadDeferred, VideoLoadResult.Loaded(rewardedAd))
					}
				}

				override fun onAdFailedToLoad(loadAdError: LoadAdError) {
					completeVideoLoad(loadDeferred, VideoLoadResult.Failed(loadError = loadAdError))
				}
			}
			RewardedAd.load(Core.app, adUnitId, AdRequest.Builder().build(), loadCallback)
		} catch (error: Exception) {
			Log.e(TAG, "Failed to start loading rewarded ad", error)
			completeVideoLoad(loadDeferred, VideoLoadResult.Failed(exception = error))
		}
		return loadDeferred
	}

	private fun logVideoLoad(eventName: String, adContext: ScreenAdContext) {
		val params = adContext.toAdLogParams()
		LogUtil.log(eventName, params + mapOf(LogAdParam.ad_preload to (adContext.areaKey == "preload")))
	}

	private fun completeVideoLoad(
		loadDeferred: CompletableDeferred<VideoLoadResult>,
		result: VideoLoadResult,
	) {
		if (videoLoadDeferred === loadDeferred) {
			videoLoadDeferred = null
			isLoadingVideo = false
		}
		loadDeferred.complete(result)
	}

	// 检查并移除开屏广告池过期广告
	fun checkOpenPool() {
		openPool.entries.removeIf { (_, time) ->
			System.currentTimeMillis() - time > AdmobConfig.openTimeout
		}
	}

	// 检查并移除插屏广告池过期广告
	fun checkInterPool() {
		interPool.entries.removeIf { (_, time) ->
			System.currentTimeMillis() - time > AdmobConfig.interTimeout
		}
	}

	// 检查并移除视频广告池过期广告
	fun checkVideoPool() {
		videoPool.entries.removeIf { (_, time) ->
			System.currentTimeMillis() - time > AdmobConfig.videoTimeout
		}
	}

}
