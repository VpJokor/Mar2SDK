package com.mar2sdk.core.ad.impl.admob.probe

import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.VersionInfo
import com.google.android.gms.ads.mediation.Adapter
import com.google.android.gms.ads.mediation.InitializationCompleteCallback
import com.google.android.gms.ads.mediation.MediationAdLoadCallback
import com.google.android.gms.ads.mediation.MediationAppOpenAd
import com.google.android.gms.ads.mediation.MediationAppOpenAdCallback
import com.google.android.gms.ads.mediation.MediationAppOpenAdConfiguration
import com.google.android.gms.ads.mediation.MediationConfiguration
import com.google.android.gms.ads.mediation.MediationInterstitialAd
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback
import com.google.android.gms.ads.mediation.MediationInterstitialAdConfiguration
import com.google.android.gms.ads.mediation.MediationRewardedAd
import com.google.android.gms.ads.mediation.MediationRewardedAdCallback
import com.google.android.gms.ads.mediation.MediationRewardedAdConfiguration

/**
 * 在瀑布流中返回专用 no-fill，供 Reader 确认本次请求执行到了对应价格档位。
 * 价格与请求结果由 Reader 解析；适配器本身不保留请求状态。
 */
class AdmobProxyAdapter : Adapter() {

	override fun initialize(
		context: Context,
		initializationCompleteCallback: InitializationCompleteCallback,
		mediationConfigurations: List<MediationConfiguration>,
	) {
		initializationCompleteCallback.onInitializationSucceeded()
	}

	override fun getVersionInfo(): VersionInfo = VersionInfo(1, 0, 0)

	override fun getSDKVersionInfo(): VersionInfo = VersionInfo(1, 0, 0)

	override fun loadAppOpenAd(
		adConfiguration: MediationAppOpenAdConfiguration,
		callback: MediationAdLoadCallback<MediationAppOpenAd, MediationAppOpenAdCallback>,
	) {
		callback.onFailure(probeNoFill())
	}

	override fun loadInterstitialAd(
		adConfiguration: MediationInterstitialAdConfiguration,
		callback: MediationAdLoadCallback<MediationInterstitialAd, MediationInterstitialAdCallback>,
	) {
		callback.onFailure(probeNoFill())
	}

	override fun loadRewardedAd(
		adConfiguration: MediationRewardedAdConfiguration,
		callback: MediationAdLoadCallback<MediationRewardedAd, MediationRewardedAdCallback>,
	) {
		callback.onFailure(probeNoFill())
	}

	private fun probeNoFill(): AdError = AdError(
		PROBE_NO_FILL_CODE,
		"Intentional probe no fill",
		PROBE_ERROR_DOMAIN,
	)

	companion object {
		const val PROBE_ERROR_DOMAIN = "com.mar2sdk.admob.probe"
		const val PROBE_NO_FILL_CODE = 10001
	}
}
