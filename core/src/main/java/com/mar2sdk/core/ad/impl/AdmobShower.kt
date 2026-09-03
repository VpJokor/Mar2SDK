package com.mar2sdk.core.ad.impl

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.firebase.analytics.FirebaseAnalytics
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.ShowFailResult
import com.mar2sdk.core.log.LogAdEvent
import com.mar2sdk.core.log.LogAdParam
import com.mar2sdk.core.log.LogUtil
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * 开屏广告展示器
 */
object AdmobShower {

	private const val TAG = "AdmobShower"

	// 开屏广告展示超时时间
	var showOpenTimeout = 10 * 1000L
	var showInterTimeout = 10 * 1000L
	var showVideoTimeout = 10 * 1000L

	fun showOpen(activity: Activity, callback: ShowCallback) {
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
			return
		}
		if (AdmobLoader.openShowCall != null) {
			Log.e(TAG, "showOpen: AdmobLoader.openShowCall is not null" )
			callback.showFailed(ShowFailResult.OTHER_AD_IS_SHOWING)
			return
		}
		//检查广告池广告是否过期
		AdmobLoader.checkOpenPool()

		//修改APP状态
		AppStatus.isShowingAd = true
		//超时处理相关变量定义
		val startShowTime = System.currentTimeMillis()
		var timeoutTask: Runnable? = null
		// 定时器任务是否执行完毕，防止重复执行
		var finished = false
		val handler = Handler(Looper.getMainLooper())
		var currentOpenAd: AppOpenAd? = null

		// INFO: 处理广告展示回调
		val contentCallback = object : FullScreenContentCallback() {
			override fun onAdFailedToShowFullScreenContent(p0: AdError) {
				super.onAdFailedToShowFullScreenContent(p0)
				LogUtil.log(
					LogAdEvent.ad_show_fail,
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
				AppStatus.isShowingAd = false
				callback.showFailed(ShowFailResult.FAILED_TO_SHOW_CONTENT)
			}

			override fun onAdDismissedFullScreenContent() {
				super.onAdDismissedFullScreenContent()
				LogUtil.log(
					LogAdEvent.ad_close,
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

				AppStatus.isShowingAd = false
				callback.onAdClosed()
			}

			override fun onAdImpression() {
				super.onAdImpression()
				// info: 处理展示
				callback.showSuccess()
			}

			override fun onAdClicked() {
				super.onAdClicked()
				LogUtil.log(
					LogAdEvent.ad_click,
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

				callback.onClicked()
			}
		}
		// INFO: 处理广告收入回调
		val paidCallback = OnPaidEventListener { adValue ->
			Log.e(TAG, "showOpen: $adValue" )
			// info: 处理收入打点
			val revenue = adValue.valueMicros / 1_000_000.0
			LogUtil.log(
				LogAdEvent.ad_impression,
				mapOf(
					LogAdParam.ad_areakey to callback.areaKey,
					FirebaseAnalytics.Param.AD_PLATFORM to LogAdParam.ad_platform_admob,
					FirebaseAnalytics.Param.AD_UNIT_NAME to AdmobConfig.openID,
					FirebaseAnalytics.Param.AD_FORMAT to LogAdParam.ad_format_open,
					FirebaseAnalytics.Param.AD_SOURCE to (currentOpenAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
					FirebaseAnalytics.Param.CURRENCY to adValue.currencyCode,
					FirebaseAnalytics.Param.VALUE to revenue,
					LogAdParam.ad_preload to true,
				)
			)
			LogUtil.log(
				LogAdEvent.ad_revenue,
				mapOf(
					LogAdParam.ad_areakey to callback.areaKey,
					FirebaseAnalytics.Param.AD_PLATFORM to LogAdParam.ad_platform_admob,
					FirebaseAnalytics.Param.AD_UNIT_NAME to AdmobConfig.openID,
					FirebaseAnalytics.Param.AD_FORMAT to LogAdParam.ad_format_open,
					FirebaseAnalytics.Param.AD_SOURCE to (currentOpenAd?.responseInfo?.loadedAdapterResponseInfo?.adSourceName ?: LogAdParam.unknow),
					FirebaseAnalytics.Param.CURRENCY to adValue.currencyCode,
					FirebaseAnalytics.Param.VALUE to revenue,
					LogAdParam.ad_preload to true,
				)
			)
			LogUtil.logSingularAdRevenue(LogAdParam.adMob, revenue)
			callback.onPaid()
		}

		fun show(ad: AppOpenAd) {
			currentOpenAd = ad
			if (activity.isFinishing || activity.isDestroyed) {
				AppStatus.isShowingAd = false
				callback.showFailed(ShowFailResult.ACTIVITY_IS_FINISHING)
				return
			}
			// 广告只能展示一次，展示前从池中移除
			AdmobLoader.openPool.remove(ad)
			ad.fullScreenContentCallback = contentCallback
			ad.onPaidEventListener = paidCallback
			try {
				ad.show(activity)
			} catch (e: Exception) {
				Log.e(TAG, "show: ", e)
				AppStatus.isShowingAd = false
				// 移除超时定时器
				timeoutTask?.let {
					handler.removeCallbacks(it)
				}
				callback.showFailed(ShowFailResult.SHOW_AD_EXCEPTION)
			}
			AdmobLoader.loadOpen()
		}

		val cachedAd = AdmobLoader.openPool.keys.firstOrNull()
		if (cachedAd != null) {
			show(cachedAd)
			return
		}

		val currentOpenCallback = object : AdmobLoader.OpenCallback {
			override var usedBy: String? = TAG
			override fun onLoaded(ad: AppOpenAd) {
				// 移除超时定时器
				timeoutTask?.let {
					handler.removeCallbacks(it)
				}
				if (System.currentTimeMillis() - startShowTime > showOpenTimeout) {
					// 展示超时
					AppStatus.isShowingAd = false
				} else {
					show(ad)
				}
			}
			override fun onLoadFailed(err: LoadAdError) {
				Log.e(TAG, "Open ad load failed: ${err.message}")
				// 移除超时定时器
				timeoutTask?.let {
					handler.removeCallbacks(it)
				}
				AppStatus.isShowingAd = false
				callback.showFailed(ShowFailResult.LOAD_FAILED)
			}
		}
		AdmobLoader.openShowCall = currentOpenCallback

		// 启动定时器计算超时
		 timeoutTask = Runnable {
			if (finished) return@Runnable
			finished = true
			LogUtil.log(
				 LogAdEvent.ad_show_timeout,
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
			if (AdmobLoader.openShowCall === currentOpenCallback) {
				AdmobLoader.openShowCall = null
			}
			AppStatus.isShowingAd = false
			callback.showFailed(ShowFailResult.LOAD_TIMEOUT)
		}
		handler.postDelayed(timeoutTask, showOpenTimeout)
		try {
			AdmobLoader.loadOpen()
		} catch (e: Exception) {
			// INFO: 处理抛出的异常
			Log.e(TAG, "showOpen: ", e)
			AdmobLoader.isLoadingOpen = false
			if (AdmobLoader.openShowCall === currentOpenCallback) {
				AdmobLoader.openShowCall = null
			}
			timeoutTask.let {
				handler.removeCallbacks(it)
			}
			AppStatus.isShowingAd = false
			callback.showFailed(ShowFailResult.LOAD_AD_EXCEPTION)
		}
	}

	fun showInter(activity: Activity, callback: ShowCallback) {

	}

	fun showVideo(activity: Activity, callback: ShowCallback) {

	}


}
