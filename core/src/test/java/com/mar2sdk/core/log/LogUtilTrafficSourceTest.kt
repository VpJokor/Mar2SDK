package com.mar2sdk.core.log

import com.mar2sdk.core.common.UserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LogUtilTrafficSourceTest {
	private var previousTrafficSource = ""

	@Before
	fun saveTrafficSource() {
		Dispatchers.setMain(StandardTestDispatcher())
		previousTrafficSource = UserInfo.trafficSource
	}

	@After
	fun restoreTrafficSource() {
		UserInfo.trafficSource = previousTrafficSource
		Dispatchers.resetMain()
	}

	@Test
	fun addsCurrentTrafficSourceWhenMissing() {
		UserInfo.trafficSource = "notification"

		val params = mapOf("route" to "home")
		val result = LogUtil.withTrafficSource(params)

		assertEquals("notification", result[LogAdParam.traffic_source])
		assertEquals("home", result["route"])
	}

	@Test
	fun keepsExplicitNonBlankTrafficSource() {
		UserInfo.trafficSource = "desktop"
		val params = mapOf(LogAdParam.traffic_source to "campaign")

		val result = LogUtil.withTrafficSource(params)

		assertSame(params, result)
		assertEquals("campaign", result[LogAdParam.traffic_source])
	}

	@Test
	fun replacesBlankTrafficSourceAndFallsBackToUnknown() {
		UserInfo.trafficSource = ""

		val result = LogUtil.withTrafficSource(mapOf(LogAdParam.traffic_source to "  "))

		assertEquals("unknown", result[LogAdParam.traffic_source])
	}
}
