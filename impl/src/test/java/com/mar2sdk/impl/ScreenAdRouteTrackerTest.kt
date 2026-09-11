package com.mar2sdk.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenAdRouteTrackerTest {
	@Test
	fun firstDestinationHasNoSourceToOverwriteRestoredState() {
		val tracker = ScreenAdRouteTracker()

		assertNull(tracker.onDestinationChanged("restored-content1", "content1"))
		assertEquals("content1", tracker.onDestinationChanged("content2-entry", "content2"))
	}

	@Test
	fun enteringContentFromAnUnwrappedDestinationUsesItsRoute() {
		val tracker = ScreenAdRouteTracker()
		tracker.onDestinationChanged("main-entry", "main")

		assertEquals("main", tracker.onDestinationChanged("content1-entry", "content1"))
	}

	@Test
	fun returningToAnEarlierEntryUsesThePageJustLeft() {
		val tracker = ScreenAdRouteTracker()
		tracker.onDestinationChanged("main-entry", "main")
		tracker.onDestinationChanged("content1-entry", "content1")
		tracker.onDestinationChanged("content2-entry", "content2")

		assertEquals("content2", tracker.onDestinationChanged("content1-entry", "content1"))
		assertEquals("content1", tracker.onDestinationChanged("main-entry", "main"))
	}

	@Test
	fun replacingTheCurrentPageKeepsTheRemovedPageAsSource() {
		val tracker = ScreenAdRouteTracker()
		tracker.onDestinationChanged("content2-entry", "content2")

		assertEquals("content2", tracker.onDestinationChanged("new-content1-entry", "content1"))
	}

	@Test
	fun repeatedDestinationCallbacksDoNotOverwriteTheSource() {
		val tracker = ScreenAdRouteTracker()
		tracker.onDestinationChanged("main-entry", "main")
		assertEquals("main", tracker.onDestinationChanged("content1-entry", "content1"))

		assertNull(tracker.onDestinationChanged("content1-entry", "content1"))
		assertEquals("content1", tracker.onDestinationChanged("content2-entry", "content2"))
	}

	@Test
	fun navigatingToAnotherEntryOfTheSameRouteStillRecordsTheSource() {
		val tracker = ScreenAdRouteTracker()
		tracker.onDestinationChanged("content1-first", "content1")

		assertEquals("content1", tracker.onDestinationChanged("content1-second", "content1"))
		assertEquals("content1", tracker.onDestinationChanged("content1-first", "content1"))
	}
}
