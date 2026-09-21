package com.mar2sdk.core.ad

import android.app.Activity
import androidx.annotation.MainThread
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.impl.admob.AdmobShower
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.AdShowStatus

// 广告展示器
object AdShower {

	// 开屏插屏比价，开屏优先，插屏替补
	suspend fun showOpenInter(activity: Activity, callback: ShowCallback): AdShowStatus {
		return when(callback.adContext.adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showOpenInter(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showOpenInter(activity, callback)
		}
	}

	// 插屏视频比价，插屏优先，视频替补
	suspend fun showInterVideo(activity: Activity, callback: ShowCallback): AdShowStatus {
		return when(callback.adContext.adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showInterVideo(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showInterVideo(activity, callback)
		}
	}

	// 视频插屏比价，同价或缺少有效价格时视频优先
	suspend fun showVideoInter(activity: Activity, callback: ShowCallback): AdShowStatus {
		return when(callback.adContext.adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showVideoInter(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showVideoInter(activity, callback)
		}
	}

	// 一直等待到 AdmobShower.showOpen 返回结果再返回
	suspend fun showOpen(activity: Activity, callback: ShowCallback): AdShowStatus {
		// TODO: 执行比价算法，确定播哪个平台的广告
		return when(callback.adContext.adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showOpen(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showOpen(activity, callback)
		}
	}

	suspend fun showInter(activity: Activity, callback: ShowCallback): AdShowStatus {
		return when(callback.adContext.adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showInter(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showInter(activity, callback)
		}
	}

	suspend fun showVideo(activity: Activity, callback: ShowCallback): AdShowStatus {
		return when(callback.adContext.adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showVideo(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showVideo(activity, callback)
		}
	}

	/**
	 * 开始加载 Banner 并立即返回广告 View；无法开始加载时返回 null。
	 * 调用方负责将 View 添加到页面，并随页面生命周期调用 pause、resume 和 destroy。
	 * showSuccess 在广告曝光时回调。
	 */
	@MainThread
	fun getBanner(
		activity: Activity,
		callback: ShowCallback,
		adSize: AdSize = AdSize.BANNER,
	): AdView? {
		return when(callback.adContext.adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.getBanner(activity, callback, adSize)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.getBanner(activity, callback, adSize)
		}
	}
}
