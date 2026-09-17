package com.mar2sdk.core.common

import com.mar2sdk.core.common.net.LoginSession
import com.mar2sdk.core.common.net.PlatformLoginUser
import com.mar2sdk.core.common.net.ServerApiException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSessionTest {

	@Test
	fun firstLoginCreatesAndSavesGuest() = runTest {
		val fixture = SessionFixture()

		val result = fixture.session.login().getOrThrow()

		assertEquals(result, fixture.cachedUser)
		assertEquals(result, fixture.identity)
		assertEquals(1, fixture.guestCalls)
		assertEquals(0, fixture.tokenCalls)
		assertTrue(fixture.restoredUsers.isEmpty())
		assertNull(fixture.session.onNetworkAvailable())
	}

	@Test
	fun loginAndExplicitRefreshRestoreValidUserAndSaveRefreshedCredentials() = runTest {
		for (explicitRefresh in listOf(false, true)) {
			val cached = user()
			val refreshed = cached.copy(token = "rotated-token", loginTime = NOW, name = "renamed")
			val fixture = SessionFixture(cached)
			fixture.tokenResponse = {
				assertEquals(cached, fixture.identity)
				assertEquals(cached, fixture.cachedUser)
				Result.success(refreshed)
			}

			val result = if (explicitRefresh) fixture.session.refreshUser() else fixture.session.login()

			assertEquals(refreshed, result.getOrThrow())
			assertEquals(refreshed, fixture.cachedUser)
			assertEquals(refreshed, fixture.identity)
			assertEquals(listOf(cached), fixture.restoredUsers)
			assertEquals(1, fixture.tokenCalls)
			assertEquals(0, fixture.guestCalls)
		}
	}

	@Test
	fun localCredentialChecksRespectSixtySecondMarginAndRejectInvalidTimestamps() = runTest {
		val cases = listOf(
			user().copy(loginTime = NOW - 59_999) to true,
			user().copy(loginTime = NOW - 60_000) to false,
			user().copy(loginTime = NOW - 120_000) to false,
			user().copy(loginTime = NOW + 1) to false,
			user().copy(loginTime = 0) to false,
			user().copy(expiredTime = 60) to false,
			user().copy(uid = 0) to false,
			user().copy(token = " ") to false,
		)
		for ((cached, valid) in cases) {
			val fixture = SessionFixture(cached)

			assertTrue(fixture.session.login().isSuccess)

			assertEquals(if (valid) 1 else 0, fixture.tokenCalls)
			assertEquals(if (valid) 0 else 1, fixture.guestCalls)
			assertEquals(if (valid) 0 else 1, fixture.clearCalls)
		}
	}

	@Test
	fun invalidGuestTokenFallsBackAndFailedFallbackCannotRestoreRejectedCredentials() = runTest {
		for (failFallback in listOf(false, true)) {
			val cached = user()
			val fixture = SessionFixture(cached)
			val failure = IOException("Guest login unavailable")
			fixture.tokenResponse = { Result.failure(ServerApiException(1002, "Invalid token")) }
			fixture.guestResponse = {
				assertNull(fixture.cachedUser)
				assertNull(fixture.identity)
				if (failFallback) Result.failure(failure) else Result.success(user(token = "guest-token"))
			}

			val result = fixture.session.login()

			if (failFallback) {
				assertSame(failure, result.exceptionOrNull())
				assertNull(fixture.cachedUser)
				assertNull(fixture.identity)
				fixture.guestResponse = { Result.success(user(token = "recovered-guest-token")) }
				assertTrue(fixture.session.onNetworkAvailable()!!.isSuccess)
				assertEquals(2, fixture.guestCalls)
			} else {
				assertEquals("guest-token", result.getOrThrow().token)
				assertEquals(1, fixture.guestCalls)
			}
			assertEquals(1, fixture.tokenCalls)
			assertEquals(1, fixture.clearCalls)
			assertEquals(listOf(cached), fixture.restoredUsers)
			assertNull(fixture.session.onNetworkAvailable())
		}
	}

	@Test
	fun bannedUnknownAndNonGuestInvalidAccountsAreClearedWithoutGuestFallback() = runTest {
		for ((accountType, code) in listOf(1 to 1003, 1 to 9999, 2 to 1002)) {
			val fixture = SessionFixture(user().copy(accountType = accountType))
			val failure = ServerApiException(code, "Login rejected")
			fixture.tokenResponse = { Result.failure(failure) }

			assertSame(failure, fixture.session.login().exceptionOrNull())

			assertNull(fixture.cachedUser)
			assertNull(fixture.identity)
			assertEquals(1, fixture.clearCalls)
			assertEquals(0, fixture.guestCalls)
			assertNull(fixture.session.onNetworkAvailable())
			assertEquals(1, fixture.tokenCalls)
		}
	}

	@Test
	fun temporaryFailuresPreserveCachedIdentityAndNetworkRecoveryRefreshesOnce() = runTest {
		for (failure in listOf(IOException("Offline"), ServerApiException(-1, "Temporary failure"), ServerApiException(1025, "Server unavailable"))) {
			val cached = user()
			val fixture = SessionFixture(cached)
			fixture.tokenResponse = { Result.failure(failure) }

			assertSame(failure, fixture.session.login().exceptionOrNull())
			assertEquals(cached, fixture.cachedUser)
			assertEquals(cached, fixture.identity)
			assertEquals(0, fixture.clearCalls)
			fixture.tokenResponse = { Result.success(cached.copy(token = "recovered-token", loginTime = NOW)) }

			assertEquals("recovered-token", fixture.session.onNetworkAvailable()!!.getOrThrow().token)
			assertNull(fixture.session.onNetworkAvailable())
			assertEquals(2, fixture.tokenCalls)
			assertEquals(0, fixture.guestCalls)
		}
	}

	@Test
	fun credentialsExpiringWhileOfflineUseGuestLoginWhenNetworkReturns() = runTest {
		val fixture = SessionFixture(user())
		fixture.tokenResponse = { Result.failure(IOException("Offline")) }
		assertTrue(fixture.session.login().isFailure)
		fixture.currentTime = user().loginTime + 60_000

		assertTrue(fixture.session.onNetworkAvailable()!!.isSuccess)

		assertEquals(1, fixture.tokenCalls)
		assertEquals(1, fixture.guestCalls)
		assertEquals(1, fixture.clearCalls)
		assertNull(fixture.session.onNetworkAvailable())
	}

	@Test
	fun networkEventsBeforeLoginDoNotStartRequests() = runTest {
		for (cached in listOf(null, user())) {
			val fixture = SessionFixture(cached)

			assertNull(fixture.session.onNetworkAvailable())
			assertNull(fixture.session.onNetworkAvailable())

			assertEquals(0, fixture.tokenCalls)
			assertEquals(0, fixture.guestCalls)
			assertTrue(fixture.restoredUsers.isEmpty())
		}
	}

	@Test
	fun refreshWithoutSavedUserDoesNotCreateAccountOrScheduleRetry() = runTest {
		val fixture = SessionFixture()

		assertTrue(fixture.session.refreshUser().isFailure)

		assertEquals(0, fixture.tokenCalls)
		assertEquals(0, fixture.guestCalls)
		assertNull(fixture.cachedUser)
		assertNull(fixture.session.onNetworkAvailable())
	}

	@Test
	fun cancellingPendingNetworkRefreshDoesNotFallBackOrScheduleAnotherRetry() = runTest {
		val cached = user()
		val fixture = SessionFixture(cached)
		fixture.tokenResponse = { Result.failure(IOException("Offline")) }
		assertTrue(fixture.session.login().isFailure)
		fixture.tokenResponse = { awaitCancellation() }
		val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
			fixture.session.onNetworkAvailable()
		}
		assertEquals(2, fixture.tokenCalls)

		refresh.cancelAndJoin()

		assertTrue(refresh.isCancelled)
		assertNull(fixture.session.onNetworkAvailable())
		assertEquals(cached, fixture.cachedUser)
		assertEquals(cached, fixture.identity)
		assertEquals(2, fixture.tokenCalls)
		assertEquals(0, fixture.guestCalls)
	}

	@Test
	fun concurrentNetworkEventsWaitForInFlightLoginAndDoNotRepeatSuccessfulRequest() = runTest {
		for (retrying in listOf(false, true)) {
			val fixture = SessionFixture(user())
			if (retrying) {
				fixture.tokenResponse = { Result.failure(IOException("Offline")) }
				assertTrue(fixture.session.login().isFailure)
			}
			val response = CompletableDeferred<Result<PlatformLoginUser>>()
			fixture.tokenResponse = { response.await() }
			val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
				if (retrying) fixture.session.onNetworkAvailable()!! else fixture.session.login()
			}
			val events = List(3) {
				async(start = CoroutineStart.UNDISPATCHED) { fixture.session.onNetworkAvailable() }
			}
			assertFalse(inFlight.isCompleted)
			assertTrue(events.none { it.isCompleted })

			response.complete(Result.success(user(token = "completed-token")))

			assertEquals("completed-token", inFlight.await().getOrThrow().token)
			for (event in events) assertNull(event.await())
			assertEquals(if (retrying) 2 else 1, fixture.tokenCalls)
			assertEquals(0, fixture.guestCalls)
		}
	}

	@Test
	fun networkEventQueuedDuringFailedLoginRetriesOnceAfterFailureArrives() = runTest {
		val fixture = SessionFixture(user())
		val firstResponse = CompletableDeferred<Result<PlatformLoginUser>>()
		val failure = IOException("Connection lost during login")
		val recovered = user(token = "recovered-token")
		fixture.tokenResponse = {
			if (fixture.tokenCalls == 1) firstResponse.await() else Result.success(recovered)
		}
		val login = async(start = CoroutineStart.UNDISPATCHED) { fixture.session.login() }
		val events = List(3) {
			async(start = CoroutineStart.UNDISPATCHED) { fixture.session.onNetworkAvailable() }
		}
		assertFalse(login.isCompleted)
		assertTrue(events.none { it.isCompleted })
		assertEquals(1, fixture.tokenCalls)

		firstResponse.complete(Result.failure(failure))

		assertSame(failure, login.await().exceptionOrNull())
		val refreshes = events.mapNotNull { it.await() }
		assertEquals(1, refreshes.size)
		assertEquals(recovered, refreshes.single().getOrThrow())
		assertEquals(recovered, fixture.cachedUser)
		assertEquals(recovered, fixture.identity)
		assertEquals(2, fixture.tokenCalls)
		assertEquals(0, fixture.guestCalls)
		assertNull(fixture.session.onNetworkAvailable())
	}

	private class SessionFixture(initialUser: PlatformLoginUser? = null) {
		var cachedUser = initialUser
		var identity: PlatformLoginUser? = null
		var currentTime = NOW
		val restoredUsers = mutableListOf<PlatformLoginUser>()
		var clearCalls = 0
		var tokenCalls = 0
		var guestCalls = 0
		var tokenResponse: suspend () -> Result<PlatformLoginUser> = { Result.success(user(token = "refreshed-token")) }
		var guestResponse: suspend () -> Result<PlatformLoginUser> = { Result.success(user(token = "guest-token")) }
		val session = LoginSession(
			loadUser = { cachedUser },
			restoreUser = { restoredUsers += it; identity = it },
			clearUser = { clearCalls++; cachedUser = null; identity = null },
			tokenLogin = { tokenCalls++; tokenResponse().onSuccess(::saveUser) },
			guestLogin = { guestCalls++; guestResponse().onSuccess(::saveUser) },
			now = { currentTime },
		)

		private fun saveUser(user: PlatformLoginUser) {
			cachedUser = user
			identity = user
		}
	}

	private companion object {
		const val NOW = 1_000_000L

		fun user(token: String = "cached-token") = PlatformLoginUser(
			uid = 123L,
			name = "guest",
			loginName = "",
			displayType = 1,
			token = token,
			expiredTime = 120L,
			registerTime = 900_000L,
			countryCode = "CN",
			accountType = 1,
			newAccount = 0,
			loginTime = NOW - 1_000,
		)
	}
}
