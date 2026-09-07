package com.mar2sdk.impl

internal class ScreenAdSession(private val screenName: String) {
	private enum class PendingAd {
		SCREEN,
		NAVIGATION
	}

	private var resumeHandled = false
	private var pendingAd: PendingAd? = null
	private var pendingNavigation: (() -> Unit)? = null
	private var navigationRequested = false

	fun onResume(
		hasEntered: Boolean,
		markEntered: () -> Unit,
		launchAd: (areaKey: String) -> Boolean
	) {
		if (resumeHandled) return
		resumeHandled = true

		val ad = pendingAd
		if (ad != null) {
			pendingAd = null
			if (ad == PendingAd.NAVIGATION) {
				runPendingNavigation()
			} else if (pendingNavigation != null) {
				launchNavigationAdOrRun(launchAd)
			}
			return
		}

		navigationRequested = false
		val suffix = if (hasEntered) "back" else "start"
		if (!hasEntered) markEntered()
		requestAd("${screenName}_$suffix", PendingAd.SCREEN, launchAd)
	}

	fun onPause() {
		resumeHandled = false
	}

	fun navigateAfterAd(
		launchAd: (areaKey: String) -> Boolean,
		navigation: () -> Unit
	) {
		if (navigationRequested) return

		navigationRequested = true
		pendingNavigation = navigation
		if (pendingAd == null) launchNavigationAdOrRun(launchAd)
	}

	private fun requestAd(
		areaKey: String,
		ad: PendingAd,
		launchAd: (areaKey: String) -> Boolean
	): Boolean {
		pendingAd = ad
		val launched = try {
			launchAd(areaKey)
		} catch (_: RuntimeException) {
			false
		}
		if (!launched) pendingAd = null
		return launched
	}

	private fun launchNavigationAdOrRun(launchAd: (areaKey: String) -> Boolean) {
		if (!requestAd("${screenName}_to", PendingAd.NAVIGATION, launchAd)) {
			runPendingNavigation()
		}
	}

	private fun runPendingNavigation() {
		val navigation = pendingNavigation
		pendingNavigation = null
		navigation?.invoke()
	}
}
