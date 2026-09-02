package com.mar2sdk.core.ad.impl

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnPaidEventListener
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

	fun showOpen(activity: Activity, areaKey: String) {
		//检查广告池广告是否过期
		AdmobLoader.checkOpenPool()
		//取广告
		val openAd = AdmobLoader.openPool.entries.firstOrNull { (_, time) ->
			System.currentTimeMillis() - time < AdmobConfig.openTimeout
		}?.key
		val adShowCallback = object : FullScreenContentCallback() {
			override fun onAdFailedToShowFullScreenContent(p0: AdError) {
				super.onAdFailedToShowFullScreenContent(p0)
			}

			override fun onAdShowedFullScreenContent() {
				super.onAdShowedFullScreenContent()
			}

			override fun onAdDismissedFullScreenContent() {
				super.onAdDismissedFullScreenContent()
			}

			override fun onAdImpression() {
				super.onAdImpression()
			}

			override fun onAdClicked() {
				super.onAdClicked()
			}
		}
		val adPaidCallback = OnPaidEventListener { adValue ->
			Log.e(TAG, "showOpen: $adValue" )
		}
		if (openAd != null) {
			// 直接展示
			openAd.fullScreenContentCallback = adShowCallback
			openAd.onPaidEventListener = adPaidCallback
			openAd.show(activity)

		} else if (AdmobLoader.isLoadingOpen) {
			// TODO: 监听广告加载情况，加载完毕后立即展示
		} else {
			// TODO: 监听广告加载情况，加载完毕后立即展示
			// TODO: 开始加载广告
		}
	}

	fun showInter() {

	}

	fun showVideo() {

	}
}