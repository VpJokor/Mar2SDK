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
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.ShowFailResult
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

	// 定时器任务是否执行完毕，防止重复执行
	var finished = false
	val handler = Handler(Looper.getMainLooper())

	fun showOpen(activity: Activity, callback: ShowCallback) {
		if (AppStatus.isShowingAd) {
			Log.e(TAG, "showOpen: AppStatus.isShowingAd" )
			callback.showFailed(ShowFailResult.OTHER_AD_IS_SHOWING)
			return
		}
		//修改APP状态
		AppStatus.isShowingAd = true
		//检查广告池广告是否过期
		AdmobLoader.checkOpenPool()
		val startShowTime = System.currentTimeMillis()
		// TODO: 处理广告展示回调
		val showCallback = object : FullScreenContentCallback() {
			override fun onAdFailedToShowFullScreenContent(p0: AdError) {
				super.onAdFailedToShowFullScreenContent(p0)
				AppStatus.isShowingAd = false
				callback.showFailed(ShowFailResult.FAILED_TO_SHOW_CONTENT)
			}

			override fun onAdDismissedFullScreenContent() {
				super.onAdDismissedFullScreenContent()
				AppStatus.isShowingAd = false
				// TODO: 各种打点逻辑

				callback.onAdClosed()
			}

			override fun onAdImpression() {
				super.onAdImpression()
				// TODO: 各种打点逻辑

				callback.showSuccess()
			}

			override fun onAdClicked() {
				super.onAdClicked()
				// TODO: 各种打点逻辑

				callback.onClicked()
			}
		}
		// TODO: 处理广告收入回调
		val paidCallback = OnPaidEventListener { adValue ->
			Log.e(TAG, "showOpen: $adValue" )
			// TODO: 各种打点逻辑

			callback.onPaid()
		}

		fun show(ad: AppOpenAd) {
			if (activity.isFinishing || activity.isDestroyed) {
				AppStatus.isShowingAd = false
				callback.showFailed(ShowFailResult.ACTIVITY_IS_FINISHING)
				return
			}
			// 广告只能展示一次，展示前从池中移除
			AdmobLoader.openPool.remove(ad)
			ad.fullScreenContentCallback = showCallback
			ad.onPaidEventListener = paidCallback
			ad.show(activity)
			AdmobLoader.loadOpen()
		}

		val cachedAd = AdmobLoader.openPool.keys.firstOrNull()
		if (cachedAd != null) {
			show(cachedAd)
			return
		}

		if (AdmobLoader.openShowCall != null) {
			AdmobLoader.openShowCall
		}

		val currentOpenCallback = object : AdmobLoader.OpenCallback {
			override var usedBy: String? = TAG
			override fun onLoaded(ad: AppOpenAd) {
				if (System.currentTimeMillis() - startShowTime > showOpenTimeout) {
					// 展示超时
					AppStatus.isShowingAd = false
				} else {
					show(ad)
				}
			}
			override fun onLoadFailed(err: LoadAdError) {
				Log.e(TAG, "Open ad load failed: ${err.message}")
				AppStatus.isShowingAd = false
				callback.showFailed(ShowFailResult.LOAD_FAILED)
			}
		}
		AdmobLoader.openShowCall = currentOpenCallback

		// 启动定时器计算超时
		val timeoutTask = Runnable {
			if (finished) return@Runnable
			finished = true
			AppStatus.isShowingAd = false
			if (AdmobLoader.openShowCall === currentOpenCallback) {
				AdmobLoader.openShowCall = null
			}
			callback.showFailed(ShowFailResult.LOAD_TIMEOUT)
		}
		handler.postDelayed(timeoutTask, showOpenTimeout)

		AdmobLoader.loadOpen()
	}

	fun showInter(activity: Activity, callback: ShowCallback) {

	}

	fun showVideo(activity: Activity, callback: ShowCallback) {

	}


}