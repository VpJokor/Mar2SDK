package com.mar2sdk.core.ad.impl

import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.status.AdLoadStatus
import com.mar2sdk.core.log.LogAdEvent
import com.mar2sdk.core.log.LogAdParam
import com.mar2sdk.core.log.LogUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

	// 展示等待超时后不取消实际加载，加载完成的广告仍然放入广告池
	private var openLoadDeferred: CompletableDeferred<OpenLoadResult>? = null

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
		launch { fillOpen() }
		launch { fillInter() }
		launch { fillVideo() }
	}

	// 调用 loadOpen() 填满广告池，加载完一个再加载下一个，直到广告池填满
	suspend fun fillOpen() {
		while (true) {
			when (loadOpen()) {
				AdLoadStatus.LOAD_SUCCESS -> continue
				AdLoadStatus.IS_LOADING,
				AdLoadStatus.POOL_FULL,
				AdLoadStatus.LOAD_FAIL -> return
			}
		}
	}

	// 调用 loadInter() 填满广告池，加载完一个再加载下一个，直到广告池填满
	suspend fun fillInter() {
		while (true) {
			when (loadInter()) {
				AdLoadStatus.LOAD_SUCCESS -> continue
				AdLoadStatus.IS_LOADING,
				AdLoadStatus.POOL_FULL,
				AdLoadStatus.LOAD_FAIL -> return
			}
		}
	}

	// 调用 loadVideo() 填满广告池，加载完一个再加载下一个，直到广告池填满
	suspend fun fillVideo() {
		while (true) {
			when (loadVideo()) {
				AdLoadStatus.LOAD_SUCCESS -> continue
				AdLoadStatus.IS_LOADING,
				AdLoadStatus.POOL_FULL,
				AdLoadStatus.LOAD_FAIL -> return
			}
		}
	}

	// 加载开屏
	suspend fun loadOpen(areaKey: String = "preload"): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkOpenPool()
		// 有广告正在加载
		if (isLoadingOpen) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (openPool.size >= AdmobConfig.openPoolSize) return@withContext AdLoadStatus.POOL_FULL

		when (startOpenLoad(areaKey).await()) {
			is OpenLoadResult.Loaded -> AdLoadStatus.LOAD_SUCCESS
			is OpenLoadResult.Failed -> AdLoadStatus.LOAD_FAIL
			OpenLoadResult.PoolFull -> AdLoadStatus.POOL_FULL
		}
	}

	internal suspend fun loadOpenResult(areaKey: String = "preload"): OpenLoadResult = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkOpenPool()
		openLoadDeferred?.let { return@withContext it.await() }
		// 检查广告池是否满
		if (openPool.size >= AdmobConfig.openPoolSize) return@withContext OpenLoadResult.PoolFull
		startOpenLoad(areaKey).await()
	}

	private fun startOpenLoad(areaKey: String): CompletableDeferred<OpenLoadResult> {
		val loadDeferred = CompletableDeferred<OpenLoadResult>()
		openLoadDeferred = loadDeferred
		isLoadingOpen = true
		try {
			logOpenLoad(LogAdEvent.ad_start_loading, areaKey)
			val loadCallback = object : AppOpenAd.AppOpenAdLoadCallback() {
				override fun onAdLoaded(openAd: AppOpenAd) {
					try {
						logOpenLoad(LogAdEvent.ad_finish_loading, areaKey)
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
			AppOpenAd.load(Core.app, AdmobConfig.openID, AdRequest.Builder().build(), loadCallback)
		} catch (error: Exception) {
			Log.e(TAG, "Failed to start loading open ad", error)
			completeOpenLoad(loadDeferred, OpenLoadResult.Failed(exception = error))
		}
		return loadDeferred
	}

	private fun logOpenLoad(eventName: String, areaKey: String) {
		LogUtil.log(
			eventName,
			mapOf(
				LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
				LogAdParam.ad_areakey to areaKey,
				LogAdParam.ad_format to LogAdParam.ad_format_open,
				LogAdParam.ad_unit_name to AdmobConfig.openID,
				LogAdParam.ad_preload to (areaKey == "preload"),
			)
		)
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
	suspend fun loadInter(areaKey: String = "preload"): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkInterPool()
		// 有广告正在加载
		if (isLoadingInter) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (interPool.size >= AdmobConfig.interPoolSize) return@withContext AdLoadStatus.POOL_FULL
		LogUtil.log(
			LogAdEvent.ad_start_loading,
			mapOf(
				LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
				LogAdParam.ad_areakey to areaKey,
				LogAdParam.ad_format to LogAdParam.ad_format_inter,
				LogAdParam.ad_unit_name to AdmobConfig.interID,
				LogAdParam.ad_preload to (areaKey == "preload"),
			)
		)
		// 开始加载插屏广告
		isLoadingInter = true
		suspendCancellableCoroutine { continuation ->
			try {
				val loadCallback = object : InterstitialAdLoadCallback() {
					override fun onAdLoaded(interstitialAd: InterstitialAd) {
						isLoadingInter = false
						try {
							LogUtil.log(
								LogAdEvent.ad_finish_loading,
								mapOf(
									LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
									LogAdParam.ad_areakey to areaKey,
									LogAdParam.ad_format to LogAdParam.ad_format_inter,
									LogAdParam.ad_unit_name to AdmobConfig.interID,
									LogAdParam.ad_preload to (areaKey == "preload"),
								)
							)
							interPool[interstitialAd] = System.currentTimeMillis()
						} finally {
							if (continuation.isActive) {
								continuation.resumeWith(Result.success(AdLoadStatus.LOAD_SUCCESS))
							}
						}
					}

					override fun onAdFailedToLoad(loadAdError: LoadAdError) {
						isLoadingInter = false
						if (continuation.isActive) {
							continuation.resumeWith(Result.success(AdLoadStatus.LOAD_FAIL))
						}
					}
				}
				InterstitialAd.load(Core.app, AdmobConfig.interID, AdRequest.Builder().build(), loadCallback)
			} catch (error: Exception) {
				Log.e(TAG, "Failed to start loading interstitial ad", error)
				isLoadingInter = false
				if (continuation.isActive) {
					continuation.resumeWith(Result.success(AdLoadStatus.LOAD_FAIL))
				}
			}
		}
	}

	// 加载视频
	suspend fun loadVideo(areaKey: String = "preload"): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkVideoPool()
		// 有广告正在加载
		if (isLoadingVideo) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (videoPool.size >= AdmobConfig.videoPoolSize) return@withContext AdLoadStatus.POOL_FULL
		LogUtil.log(
			LogAdEvent.ad_start_loading,
			mapOf(
				LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
				LogAdParam.ad_areakey to areaKey,
				LogAdParam.ad_format to LogAdParam.ad_format_video,
				LogAdParam.ad_unit_name to AdmobConfig.VideoID,
				LogAdParam.ad_preload to (areaKey == "preload"),
			)
		)
		// 开始加载视频广告
		isLoadingVideo = true
		suspendCancellableCoroutine { continuation ->
			try {
				val loadCallback = object : RewardedAdLoadCallback() {
					override fun onAdLoaded(rewardedAd: RewardedAd) {
						isLoadingVideo = false
						try {
							LogUtil.log(
								LogAdEvent.ad_finish_loading,
								mapOf(
									LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
									LogAdParam.ad_areakey to areaKey,
									LogAdParam.ad_format to LogAdParam.ad_format_video,
									LogAdParam.ad_unit_name to AdmobConfig.VideoID,
									LogAdParam.ad_preload to (areaKey == "preload"),
								)
							)
							videoPool[rewardedAd] = System.currentTimeMillis()
						} finally {
							if (continuation.isActive) {
								continuation.resumeWith(Result.success(AdLoadStatus.LOAD_SUCCESS))
							}
						}
					}

					override fun onAdFailedToLoad(loadAdError: LoadAdError) {
						isLoadingVideo = false
						if (continuation.isActive) {
							continuation.resumeWith(Result.success(AdLoadStatus.LOAD_FAIL))
						}
					}
				}
				RewardedAd.load(Core.app, AdmobConfig.VideoID, AdRequest.Builder().build(), loadCallback)
			} catch (error: Exception) {
				Log.e(TAG, "Failed to start loading rewarded ad", error)
				isLoadingVideo = false
				if (continuation.isActive) {
					continuation.resumeWith(Result.success(AdLoadStatus.LOAD_FAIL))
				}
			}
		}
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
