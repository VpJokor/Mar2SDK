package com.mar2sdk.core.ad.impl

import android.R
import com.chartboost.sdk.impl.fa
import com.chartboost.sdk.impl.fi
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


/**
 * admob的广告加载器
 */
object AdmobLoader {

	var openShowCall: OpenCallback? = null

	// 广告池，里面放已经加载成功的广告
	val openPool = mutableMapOf<AppOpenAd, Long>()
	val interPool = mutableMapOf<InterstitialAd, Long>()
	val videoPool = mutableMapOf<RewardedAd, Long>()

	// 正在加载中的 开屏/插屏/视频 广告
	var isLoadingOpen = false
	var isLoadingInter = false
	var isLoadingVideo = false

	// 填充所有广告池
	fun fillPool() {
		loadOpen(true)
		loadInter(true)
		loadVideo(true)
	}

	// 加载开屏
	fun loadOpen(fillPool : Boolean = false, areaKey : String = "preload"): AdLoadStatus {
		// 检查过期广告
		checkOpenPool()
		// 有广告正在加载
		if (isLoadingOpen) return AdLoadStatus.IS_LOADING
		// 检查广告池是否满
		if (openPool.size >= AdmobConfig.openPoolSize) return AdLoadStatus.POOL_FULL
		// 开始加载开屏广告
		isLoadingOpen = true
		LogUtil.log(
			LogAdEvent.ad_start_loading,
			mapOf(
				LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
				LogAdParam.ad_areakey to areaKey,
				LogAdParam.ad_format to LogAdParam.ad_format_open,
				LogAdParam.ad_unit_name to AdmobConfig.openID,
				LogAdParam.ad_preload to (areaKey == "preload"),
			)
		)
		AppOpenAd.load(
			Core.app,
			AdmobConfig.openID,
			AdRequest.Builder().build(),
			object : AppOpenAd.AppOpenAdLoadCallback() {
				override fun onAdLoaded(openAd: AppOpenAd) {
					LogUtil.log(
						LogAdEvent.ad_finish_loading,
						mapOf(
							LogAdParam.ad_platform to LogAdParam.ad_platform_admob,
							LogAdParam.ad_areakey to areaKey,
							LogAdParam.ad_format to LogAdParam.ad_format_open,
							LogAdParam.ad_unit_name to AdmobConfig.openID,
							LogAdParam.ad_preload to (areaKey == "preload"),
						)
					)
					openPool[openAd] = System.currentTimeMillis()
					isLoadingOpen = false
					if (fillPool) {
						loadOpen(true)
					}
					// 接口回调供展示用
					val callback = openShowCall
					openShowCall = null
					callback?.onLoaded(openAd)
				}

				override fun onAdFailedToLoad(loadAdError: LoadAdError) {
					isLoadingOpen = false
					val callback = openShowCall
					openShowCall = null
					callback?.onLoadFailed(loadAdError)
				}
			},
		)
		return AdLoadStatus.LOAD_STARTED
	}

	// 加载插屏
	fun loadInter(fillPool : Boolean = false): AdLoadStatus {
		checkInterPool()
		if (isLoadingInter) return AdLoadStatus.IS_LOADING
		if (interPool.size >= AdmobConfig.interPoolSize) return AdLoadStatus.POOL_FULL
		isLoadingInter = true
		InterstitialAd.load(
			Core.app,
			AdmobConfig.interID,
			AdRequest.Builder().build(),
			object : InterstitialAdLoadCallback() {
				override fun onAdLoaded(adInter: InterstitialAd) {
					interPool[adInter] = System.currentTimeMillis()
					isLoadingInter = false
					if (fillPool) {
						loadInter(true)
					}
				}

				override fun onAdFailedToLoad(adError: LoadAdError) {
					isLoadingInter = false
				}
			},
		)
		return AdLoadStatus.LOAD_STARTED
	}

	// 加载视频
	fun loadVideo(fillPool : Boolean = false): AdLoadStatus {
		checkVideoPool()
		if (isLoadingVideo) return AdLoadStatus.IS_LOADING
		if (videoPool.size >= AdmobConfig.videoPoolSize) return AdLoadStatus.POOL_FULL
		isLoadingVideo = true
		RewardedAd.load(
			Core.app,
			AdmobConfig.VideoID,
			AdRequest.Builder().build(),
			object : RewardedAdLoadCallback() {
				override fun onAdLoaded(ad: RewardedAd) {
					videoPool[ad] = System.currentTimeMillis()
					isLoadingVideo = false
					if (fillPool) {
						loadVideo(true)
					}
				}

				override fun onAdFailedToLoad(adError: LoadAdError) {
					isLoadingVideo = false
				}
			},
		)
		return AdLoadStatus.LOAD_STARTED
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

	interface OpenCallback {
		var usedBy: String?
		//加载成功
		fun onLoaded(ad: AppOpenAd)
		//加载失败
		fun onLoadFailed(err: LoadAdError)
	}
}
