package com.mar2sdk.impl

import com.mar2sdk.core.ad.policy.ScreenAdTrigger

internal data class ScreenAdRequest(
	val areaKey: String,
	val trigger: ScreenAdTrigger,
	val fromRoute: String,
	val toRoute: String
)

/**
 * 管理单个页面会话中的插屏广告展示与广告结束后的导航。
 * 广告启动后，以页面下一次恢复作为广告结束的信号，并保证同一次恢复事件只处理一次。
 */
internal class ScreenAdSession(private val screenName: String) {
	// 已启动且等待页面恢复后处理的广告类型。
	private enum class PendingAd {
		// 页面首次进入或返回时展示的广告。
		SCREEN,

		// 执行页面跳转前展示的广告。
		NAVIGATION
	}

	private data class PendingNavigation(val toRoute: String, val navigate: () -> Unit)

	// 当前恢复周期是否已经处理过，防止重复响应恢复事件。
	private var resumeHandled = false

	// 已启动并等待结束的广告；没有待处理广告时为 `null`。
	private var pendingAd: PendingAd? = null

	// 导航广告结束或无法启动时要执行的跳转操作。
	private var pendingNavigation: PendingNavigation? = null

	// 当前页面停留期间是否已接收导航请求，用于忽略重复请求。
	private var navigationRequested = false

	/**
	 * 处理页面恢复事件，并根据会话状态展示页面广告或继续待执行的导航。
	 *
	 * @param hasEntered 页面是否曾经进入过；用于区分首次进入广告与返回广告。
	 * @param markEntered 将页面标记为已进入的操作，仅在首次进入时调用。
	 * @param launchAd 根据广告请求启动广告的操作；成功启动时返回 `true`。
	 * @param fromRoute 本次进入或返回的来源路由。
	 */
	fun onResume(
		hasEntered: Boolean,
		markEntered: () -> Unit,
		launchAd: (ScreenAdRequest) -> Boolean,
		fromRoute: String = ""
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
		requestAd(
			ScreenAdRequest(
				areaKey = "${screenName}_$suffix",
				trigger = if (hasEntered) ScreenAdTrigger.RETURN else ScreenAdTrigger.ENTER,
				fromRoute = fromRoute,
				toRoute = screenName
			),
			PendingAd.SCREEN,
			launchAd
		)
	}

	// 标记页面已离开恢复状态，使下一次恢复事件可以被处理。
	fun onPause() {
		resumeHandled = false
	}

	/**
	 * 请求在展示导航广告后执行跳转。
	 *
	 * 如果页面广告仍在展示，则先保存跳转操作，待页面广告结束后再尝试展示导航广告；
	 * 如果导航广告无法启动，则立即执行跳转。同一页面停留期间的重复请求会被忽略。
	 *
	 * @param toRoute 跳转的目标路由。
	 * @param launchAd 根据广告请求启动广告的操作；成功启动时返回 `true`。
	 * @param navigation 广告结束或无法启动时执行的跳转操作。
	 */
	fun navigateAfterAd(
		toRoute: String,
		launchAd: (ScreenAdRequest) -> Boolean,
		navigation: () -> Unit
	) {
		if (navigationRequested) return

		navigationRequested = true
		pendingNavigation = PendingNavigation(toRoute, navigation)
		if (pendingAd == null) launchNavigationAdOrRun(launchAd)
	}

	/**
	 * 尝试启动指定广告，并记录等待处理的广告类型。
	 *
	 * 启动操作返回 `false` 或抛出 [RuntimeException] 时视为启动失败，并清除等待状态。
	 *
	 * @param request 广告位、触发类型及路由信息。
	 * @param ad 要记录的广告类型。
	 * @param launchAd 实际启动广告的操作。
	 * @return 广告是否成功启动。
	 */
	private fun requestAd(
		request: ScreenAdRequest,
		ad: PendingAd,
		launchAd: (ScreenAdRequest) -> Boolean
	): Boolean {
		pendingAd = ad
		val launched = try {
			launchAd(request)
		} catch (_: RuntimeException) {
			false
		}
		if (!launched) pendingAd = null
		return launched
	}

	/**
	 * 尝试展示当前页面的导航广告；广告无法启动时直接执行待处理的跳转。
	 * @param launchAd 根据广告请求启动广告的操作。
	 */
	private fun launchNavigationAdOrRun(launchAd: (ScreenAdRequest) -> Boolean) {
		val navigation = pendingNavigation ?: return
		val request = ScreenAdRequest(
			areaKey = "${screenName}_to",
			trigger = ScreenAdTrigger.LEAVE,
			fromRoute = screenName,
			toRoute = navigation.toRoute
		)
		if (!requestAd(request, PendingAd.NAVIGATION, launchAd)) {
			runPendingNavigation()
		}
	}

	// 取出并执行待处理的跳转，同时保证该跳转最多执行一次。
	private fun runPendingNavigation() {
		val navigation = pendingNavigation
		pendingNavigation = null
		navigation?.navigate?.invoke()
	}
}
