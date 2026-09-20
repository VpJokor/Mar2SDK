package com.mar2sdk.core.ad.impl.admob

import android.util.Log
import androidx.annotation.MainThread
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProxyReader
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import com.mar2sdk.core.ad.impl.admob.probe.AdmobReflectProbe
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.policy.ScreenAdTrigger
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdLoadStatus
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.log.LogAdEvent
import com.mar2sdk.core.log.LogAdParam
import com.mar2sdk.core.log.toAdLogParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * admob的广告加载器
 * 每种格式复用一个当前广告位的加载请求；切换后旧 SDK 请求可能仍返回，但不再使用。
 */
object AdmobLoader {
	private const val TAG = "AdmobLoader"
	internal class AdUnitChangedException : IllegalStateException("AdMob ad unit changed during loading")

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
	private var openLoadingAdUnitId: String? = null
	private var interLoadingAdUnitId: String? = null
	private var videoLoadingAdUnitId: String? = null

	// SDK 请求入口与请求状态分开，便于用可控回调验证配置切换期间的加载顺序。
	internal var requestOpenAd: (String, AppOpenAd.AppOpenAdLoadCallback) -> Unit = { id, callback ->
		AppOpenAd.load(Core.app, id, AdRequest.Builder().build(), callback)
	}
	internal var requestInterAd: (String, InterstitialAdLoadCallback) -> Unit = { id, callback ->
		InterstitialAd.load(Core.app, id, AdRequest.Builder().build(), callback)
	}
	internal var requestVideoAd: (String, RewardedAdLoadCallback) -> Unit = { id, callback ->
		RewardedAd.load(Core.app, id, AdRequest.Builder().build(), callback)
	}

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
		Core.log(
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
			if (loadOpenResult() !is OpenLoadResult.Loaded) return
		}
	}

	// 等待正在加载的广告，加载完一个再加载下一个，直到广告池填满
	suspend fun fillInter() {
		while (true) {
			if (loadInterResult() !is InterLoadResult.Loaded) return
		}
	}

	// 等待正在加载的广告，加载完一个再加载下一个，直到广告池填满
	suspend fun fillVideo() {
		while (true) {
			if (loadVideoResult() !is VideoLoadResult.Loaded) return
		}
	}

	// 加载开屏
	suspend fun loadOpen(
		adContext: ScreenAdContext = defaultContext(AdFormat.OPEN, AdmobConfig.openID)
	): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkPool(adContext.adFormat)
		// 有广告正在加载
		if (isLoadingOpen) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (openPool.size >= AdmobConfig.openConfig.poolSize) return@withContext AdLoadStatus.POOL_FULL

		when (startOpenLoad(adContext).await()) {
			is OpenLoadResult.Loaded -> AdLoadStatus.LOAD_SUCCESS
			is OpenLoadResult.Failed -> AdLoadStatus.LOAD_FAIL
			OpenLoadResult.PoolFull -> AdLoadStatus.POOL_FULL
		}
	}

	internal suspend fun loadOpenResult(
		adContext: ScreenAdContext = defaultContext(AdFormat.OPEN, AdmobConfig.openID)
	): OpenLoadResult = loadResult(
		checkPool = { checkPool(AdFormat.OPEN) },
		currentLoad = { openLoadDeferred },
		isPoolFull = { openPool.size >= AdmobConfig.openConfig.poolSize },
		poolFull = OpenLoadResult.PoolFull,
		isObsolete = { it is OpenLoadResult.Failed && it.exception is AdUnitChangedException },
		startLoad = { startOpenLoad(adContext) },
	)

	private fun startOpenLoad(
		adContext: ScreenAdContext
	): CompletableDeferred<OpenLoadResult> {
		val loadContext = adContext.copy(adUnitId = AdmobConfig.openID)
		val probeConfig = AdmobConfig.openConfig.probeConfig.let {
			it.copy(instances = it.instances.toList())
		}
		val loadDeferred = CompletableDeferred<OpenLoadResult>()
		openLoadDeferred = loadDeferred
		openLoadingAdUnitId = loadContext.adUnitId
		isLoadingOpen = true
		try {
			logLoad(LogAdEvent.ad_start_loading, loadContext)
			val loadCallback = object : AppOpenAd.AppOpenAdLoadCallback() {
				override fun onAdLoaded(openAd: AppOpenAd) {
					// 旧请求不能在切换后入池，也不能覆盖新请求的加载状态。
					if (openLoadDeferred !== loadDeferred || loadContext.adUnitId != AdmobConfig.openID) {
						completeLoad(loadDeferred, OpenLoadResult.Failed(exception = AdUnitChangedException()))
						return
					}
					val price = AdmobReflectProbe.read(openAd)
					openAd.reflectPrice = price
					openAd.adapterProbeResult = AdmobAdapterProxyReader.read(openAd.responseInfo, probeConfig)
					cacheLoadedAd(openAd, openPool, loadContext, "open", price) {
						completeLoad(loadDeferred, OpenLoadResult.Loaded(openAd))
					}
				}

				override fun onAdFailedToLoad(loadAdError: LoadAdError) {
					completeLoad(loadDeferred, OpenLoadResult.Failed(loadError = loadAdError))
				}
			}
			requestOpenAd(loadContext.adUnitId, loadCallback)
		} catch (error: Exception) {
			Log.e(TAG, "Failed to start loading open ad", error)
			completeLoad(loadDeferred, OpenLoadResult.Failed(exception = error))
		}
		return loadDeferred
	}

	// 加载插屏
	suspend fun loadInter(
		adContext: ScreenAdContext = defaultContext(AdFormat.INTER, AdmobConfig.interID)
	): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkPool(adContext.adFormat)
		// 有广告正在加载
		if (isLoadingInter) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (interPool.size >= AdmobConfig.interConfig.poolSize) return@withContext AdLoadStatus.POOL_FULL

		when (startInterLoad(adContext).await()) {
			is InterLoadResult.Loaded -> AdLoadStatus.LOAD_SUCCESS
			is InterLoadResult.Failed -> AdLoadStatus.LOAD_FAIL
			InterLoadResult.PoolFull -> AdLoadStatus.POOL_FULL
		}
	}

	internal suspend fun loadInterResult(
		adContext: ScreenAdContext = defaultContext(AdFormat.INTER, AdmobConfig.interID)
	): InterLoadResult = loadResult(
		checkPool = { checkPool(AdFormat.INTER) },
		currentLoad = { interLoadDeferred },
		isPoolFull = { interPool.size >= AdmobConfig.interConfig.poolSize },
		poolFull = InterLoadResult.PoolFull,
		isObsolete = { it is InterLoadResult.Failed && it.exception is AdUnitChangedException },
		startLoad = { startInterLoad(adContext) },
	)

	private fun startInterLoad(
		adContext: ScreenAdContext
	): CompletableDeferred<InterLoadResult> {
		val loadContext = adContext.copy(adUnitId = AdmobConfig.interID)
		val probeConfig = AdmobConfig.interConfig.probeConfig.let {
			it.copy(instances = it.instances.toList())
		}
		val loadDeferred = CompletableDeferred<InterLoadResult>()
		interLoadDeferred = loadDeferred
		interLoadingAdUnitId = loadContext.adUnitId
		isLoadingInter = true
		try {
			logLoad(LogAdEvent.ad_start_loading, loadContext)
			val loadCallback = object : InterstitialAdLoadCallback() {
				override fun onAdLoaded(interstitialAd: InterstitialAd) {
					if (interLoadDeferred !== loadDeferred || loadContext.adUnitId != AdmobConfig.interID) {
						completeLoad(loadDeferred, InterLoadResult.Failed(exception = AdUnitChangedException()))
						return
					}
					val price = AdmobReflectProbe.read(interstitialAd)
					interstitialAd.reflectPrice = price
					interstitialAd.adapterProbeResult = AdmobAdapterProxyReader.read(interstitialAd.responseInfo, probeConfig)
					cacheLoadedAd(interstitialAd, interPool, loadContext, "interstitial", price) {
						completeLoad(loadDeferred, InterLoadResult.Loaded(interstitialAd))
					}
				}

				override fun onAdFailedToLoad(loadAdError: LoadAdError) {
					completeLoad(loadDeferred, InterLoadResult.Failed(loadError = loadAdError))
				}
			}
			requestInterAd(loadContext.adUnitId, loadCallback)
		} catch (error: Exception) {
			Log.e(TAG, "Failed to start loading interstitial ad", error)
			completeLoad(loadDeferred, InterLoadResult.Failed(exception = error))
		}
		return loadDeferred
	}

	// 加载视频
	suspend fun loadVideo(
		adContext: ScreenAdContext = defaultContext(AdFormat.VIDEO, AdmobConfig.videoID)
	): AdLoadStatus = withContext(Dispatchers.Main.immediate) {
		// 检查过期广告
		checkPool(adContext.adFormat)
		// 有广告正在加载
		if (isLoadingVideo) return@withContext AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (videoPool.size >= AdmobConfig.videoConfig.poolSize) return@withContext AdLoadStatus.POOL_FULL

		when (startVideoLoad(adContext).await()) {
			is VideoLoadResult.Loaded -> AdLoadStatus.LOAD_SUCCESS
			is VideoLoadResult.Failed -> AdLoadStatus.LOAD_FAIL
			VideoLoadResult.PoolFull -> AdLoadStatus.POOL_FULL
		}
	}

	internal suspend fun loadVideoResult(
		adContext: ScreenAdContext = defaultContext(AdFormat.VIDEO, AdmobConfig.videoID)
	): VideoLoadResult = loadResult(
		checkPool = { checkPool(AdFormat.VIDEO) },
		currentLoad = { videoLoadDeferred },
		isPoolFull = { videoPool.size >= AdmobConfig.videoConfig.poolSize },
		poolFull = VideoLoadResult.PoolFull,
		isObsolete = { it is VideoLoadResult.Failed && it.exception is AdUnitChangedException },
		startLoad = { startVideoLoad(adContext) },
	)

	private fun startVideoLoad(
		adContext: ScreenAdContext
	): CompletableDeferred<VideoLoadResult> {
		val loadContext = adContext.copy(adUnitId = AdmobConfig.videoID)
		val probeConfig = AdmobConfig.videoConfig.probeConfig.let {
			it.copy(instances = it.instances.toList())
		}
		val loadDeferred = CompletableDeferred<VideoLoadResult>()
		videoLoadDeferred = loadDeferred
		videoLoadingAdUnitId = loadContext.adUnitId
		isLoadingVideo = true
		try {
			logLoad(LogAdEvent.ad_start_loading, loadContext)
			val loadCallback = object : RewardedAdLoadCallback() {
				override fun onAdLoaded(rewardedAd: RewardedAd) {
					if (videoLoadDeferred !== loadDeferred || loadContext.adUnitId != AdmobConfig.videoID) {
						completeLoad(loadDeferred, VideoLoadResult.Failed(exception = AdUnitChangedException()))
						return
					}
					val price = AdmobReflectProbe.read(rewardedAd)
					rewardedAd.reflectPrice = price
					rewardedAd.adapterProbeResult = AdmobAdapterProxyReader.read(rewardedAd.responseInfo, probeConfig)
					cacheLoadedAd(rewardedAd, videoPool, loadContext, "rewarded", price) {
						completeLoad(loadDeferred, VideoLoadResult.Loaded(rewardedAd))
					}
				}

				override fun onAdFailedToLoad(loadAdError: LoadAdError) {
					completeLoad(loadDeferred, VideoLoadResult.Failed(loadError = loadAdError))
				}
			}
			requestVideoAd(loadContext.adUnitId, loadCallback)
		} catch (error: Exception) {
			Log.e(TAG, "Failed to start loading rewarded ad", error)
			completeLoad(loadDeferred, VideoLoadResult.Failed(exception = error))
		}
		return loadDeferred
	}

	private fun <R> completeLoad(
		loadDeferred: CompletableDeferred<R>,
		result: R,
	) {
		when {
			openLoadDeferred === loadDeferred -> {
				openLoadDeferred = null
				openLoadingAdUnitId = null
				isLoadingOpen = false
			}
			interLoadDeferred === loadDeferred -> {
				interLoadDeferred = null
				interLoadingAdUnitId = null
				isLoadingInter = false
			}
			videoLoadDeferred === loadDeferred -> {
				videoLoadDeferred = null
				videoLoadingAdUnitId = null
				isLoadingVideo = false
			}
		}
		loadDeferred.complete(result)
	}

	private suspend fun <R> loadResult(
		checkPool: () -> Unit,
		currentLoad: () -> CompletableDeferred<R>?,
		isPoolFull: () -> Boolean,
		poolFull: R,
		isObsolete: (R) -> Boolean,
		startLoad: () -> CompletableDeferred<R>,
	): R = withContext(Dispatchers.Main.immediate) {
		var result: R
		do {
			checkPool()
			val pending = currentLoad()
			if (pending == null && isPoolFull()) return@withContext poolFull
			result = (pending ?: startLoad()).await()
			// 配置切换唤醒旧请求的等待者，在原有展示超时预算内改用新 ID。
		} while (isObsolete(result))
		result
	}

	private fun defaultContext(adFormat: AdFormat, adUnitId: String) = ScreenAdContext(
		adFormat = adFormat,
		adPlatform = AdPlatform.ADMOB,
		trigger = ScreenAdTrigger.UNKNOW,
		adUnitId = adUnitId
	)

	private fun logLoad(eventName: String, adContext: ScreenAdContext, price: AdmobPrice? = null) {
		val params = adContext.toAdLogParams() + mapOf(LogAdParam.ad_preload to (adContext.areaKey == "preload"))
		val priceParams = if (price == null) emptyMap() else mapOf(
			LogAdParam.ad_price_micros to price.valueMicros,
			LogAdParam.ad_price_currency to price.currencyCode,
			LogAdParam.ad_price_precision to price.precisionType,
			LogAdParam.ad_ecpm to price.ecpm,
		)
		Core.log(eventName, params + priceParams)
	}

	private inline fun <T : Any> cacheLoadedAd(
		ad: T,
		pool: MutableMap<T, Long>,
		adContext: ScreenAdContext,
		adName: String,
		price: AdmobPrice?,
		complete: () -> Unit,
	) {
		try {
			logLoad(LogAdEvent.ad_finish_loading, adContext, price)
		} catch (error: Exception) {
			Log.e(TAG, "Failed to log loaded $adName ad", error)
		}
		try {
			pool[ad] = System.currentTimeMillis()
		} finally {
			complete()
		}
	}

	@MainThread
	internal fun onConfigChanged() {
		checkPool(AdFormat.OPEN)
		checkPool(AdFormat.INTER)
		checkPool(AdFormat.VIDEO)
	}

	// 清理过期或不属于当前广告位的广告，并解除对旧请求的等待。
	@MainThread
	fun checkPool(adFormat: AdFormat) {
		when(adFormat) {
			AdFormat.OPEN -> {
				openPool.entries.removeIf { (ad, time) ->
					ad.adUnitId != AdmobConfig.openID || System.currentTimeMillis() - time > AdmobConfig.openConfig.timeout
				}
				openLoadDeferred?.takeIf { openLoadingAdUnitId != AdmobConfig.openID }?.let {
					completeLoad(it, OpenLoadResult.Failed(exception = AdUnitChangedException()))
				}
			}
			AdFormat.VIDEO -> {
				videoPool.entries.removeIf { (ad, time) ->
					ad.adUnitId != AdmobConfig.videoID || System.currentTimeMillis() - time > AdmobConfig.videoConfig.timeout
				}
				videoLoadDeferred?.takeIf { videoLoadingAdUnitId != AdmobConfig.videoID }?.let {
					completeLoad(it, VideoLoadResult.Failed(exception = AdUnitChangedException()))
				}
			}
			else -> {
				interPool.entries.removeIf { (ad, time) ->
					ad.adUnitId != AdmobConfig.interID || System.currentTimeMillis() - time > AdmobConfig.interConfig.timeout
				}
				interLoadDeferred?.takeIf { interLoadingAdUnitId != AdmobConfig.interID }?.let {
					completeLoad(it, InterLoadResult.Failed(exception = AdUnitChangedException()))
				}
			}
		}
	}
}
