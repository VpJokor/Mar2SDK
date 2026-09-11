package com.mar2sdk.impl

import com.mar2sdk.core.ad.policy.ScreenAdTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenAdSessionTest {
	@Test
	fun firstResumeShowsStartThenLaterResumeShowsBack() {
		val requests = mutableListOf<ScreenAdRequest>()
		val session = ScreenAdSession("screenx")
		var hasEntered = false
		var markEnteredCount = 0

		session.onResume(
			hasEntered = hasEntered,
			markEntered = {
				hasEntered = true
				markEnteredCount++
			},
			launchAd = { requests += it; true },
			fromRoute = "home"
		)
		session.onPause()
		session.onResume(hasEntered, {}, { requests += it; true }, fromRoute = "home")
		session.onPause()
		session.onResume(hasEntered, {}, { requests += it; true }, fromRoute = "screeny")

		assertEquals(
			listOf(
				ScreenAdRequest("screenx_start", ScreenAdTrigger.ENTER, "home", "screenx"),
				ScreenAdRequest("screenx_back", ScreenAdTrigger.RETURN, "screeny", "screenx")
			),
			requests
		)
		assertEquals(1, markEnteredCount)
	}

	@Test
	fun laterResumeShowsBack() {
		val areaKeys = mutableListOf<String>()
		val session = ScreenAdSession("screenx")

		session.onResume(false, {}, { false })
		session.onPause()
		session.onResume(true, {}, { areaKeys += it.areaKey; true })

		assertEquals(listOf("screenx_back"), areaKeys)
	}

	@Test
	fun navigationWaitsForLeaveAdAndRunsExactlyOnce() {
		val requests = mutableListOf<ScreenAdRequest>()
		val session = ScreenAdSession("screenx")
		var navigationCount = 0
		session.onResume(false, {}, { false })

		session.navigateAfterAd(
			toRoute = "screeny",
			launchAd = { requests += it; true },
			navigation = { navigationCount++ }
		)
		assertEquals(0, navigationCount)

		session.onPause()
		session.onResume(true, {}, { requests += it; true })
		session.onResume(true, {}, { requests += it; true })

		assertEquals(
			listOf(ScreenAdRequest("screenx_leave", ScreenAdTrigger.LEAVE, "screenx", "screeny")),
			requests
		)
		assertEquals(1, navigationCount)
	}

	@Test
	fun navigationContinuesImmediatelyWhenAdCannotStart() {
		val session = ScreenAdSession("screenx")
		var navigationCount = 0
		session.onResume(false, {}, { false })

		session.navigateAfterAd(
			toRoute = "screeny",
			launchAd = { false },
			navigation = { navigationCount++ }
		)

		assertEquals(1, navigationCount)
	}

	@Test
	fun repeatedNavigationIsIgnoredWhileAdIsShowing() {
		val requests = mutableListOf<ScreenAdRequest>()
		val session = ScreenAdSession("screenx")
		val navigations = mutableListOf<String>()
		session.onResume(false, {}, { false })

		session.navigateAfterAd("screeny", { requests += it; true }) { navigations += "first" }
		session.navigateAfterAd("screenz", { requests += it; true }) { navigations += "second" }
		session.onPause()
		session.onResume(true, {}, { requests += it; true })

		assertEquals(
			listOf(ScreenAdRequest("screenx_leave", ScreenAdTrigger.LEAVE, "screenx", "screeny")),
			requests
		)
		assertEquals(listOf("first"), navigations)
	}

	@Test
	fun navigationRequestedDuringScreenAdWaitsForItsOwnLeaveAd() {
		val requests = mutableListOf<ScreenAdRequest>()
		val session = ScreenAdSession("screenx")
		var navigationCount = 0
		session.onResume(false, {}, { requests += it; true }, fromRoute = "home")

		session.navigateAfterAd("screeny", { requests += it; true }) { navigationCount++ }
		session.navigateAfterAd("screenz", { requests += it; true }) { navigationCount++ }
		session.onPause()
		session.onResume(true, {}, { requests += it; true }, fromRoute = "changed")
		assertEquals(0, navigationCount)

		session.onPause()
		session.onResume(true, {}, { requests += it; true })

		assertEquals(
			listOf(
				ScreenAdRequest("screenx_start", ScreenAdTrigger.ENTER, "home", "screenx"),
				ScreenAdRequest("screenx_leave", ScreenAdTrigger.LEAVE, "screenx", "screeny")
			),
			requests
		)
		assertEquals(1, navigationCount)
	}

	@Test
	fun failedAdLaunchDebouncesRepeatedNavigation() {
		val session = ScreenAdSession("screenx")
		val navigations = mutableListOf<String>()
		session.onResume(false, {}, { false })

		session.navigateAfterAd("screeny", { false }) { navigations += "first" }
		session.navigateAfterAd("screenz", { false }) { navigations += "second" }

		assertEquals(listOf("first"), navigations)
	}

	@Test
	fun adLaunchExceptionContinuesNavigation() {
		val session = ScreenAdSession("screenx")
		var navigationCount = 0
		session.onResume(false, {}, { false })

		session.navigateAfterAd(
			toRoute = "screeny",
			launchAd = { throw IllegalStateException("cannot launch") },
			navigation = { navigationCount++ }
		)

		assertEquals(1, navigationCount)
	}

	@Test
	fun queuedNavigationContinuesWhenLeaveAdCannotStart() {
		val areaKeys = mutableListOf<String>()
		val session = ScreenAdSession("screenx")
		var navigationCount = 0
		session.onResume(false, {}, { areaKeys += it.areaKey; true })
		session.navigateAfterAd("screeny", { areaKeys += it.areaKey; false }) { navigationCount++ }

		session.onPause()
		session.onResume(true, {}, { areaKeys += it.areaKey; false })

		assertEquals(listOf("screenx_start", "screenx_leave"), areaKeys)
		assertEquals(1, navigationCount)
	}

	@Test
	fun screenAdLaunchExceptionDoesNotSuppressLaterBackAd() {
		val areaKeys = mutableListOf<String>()
		val session = ScreenAdSession("screenx")
		session.onResume(false, {}, { throw IllegalStateException("cannot launch") })

		session.onPause()
		session.onResume(true, {}, { areaKeys += it.areaKey; true })

		assertEquals(listOf("screenx_back"), areaKeys)
	}
}
