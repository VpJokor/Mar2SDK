package com.mar2sdk.impl

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.status.AdFormat

/**
 * Base class for traditional View/XML screens that participate in screen ads.
 *
 * A concrete screen must provide the business route name used by the ad
 * configuration, for example `override val screenName = "content1"`.
 * Navigation from a content screen must go through [navigateWithAd] so the
 * `${screenName}_leave` placement can run before the next Activity starts.
 */
abstract class ContentActivity : BaseActivity() {
	abstract val screenName: String

	private val adSession by lazy { ScreenAdSession(screenName) }
	private var hasEntered = false
	private var backRequested = false

	private val backCallback = object : OnBackPressedCallback(true) {
		override fun handleOnBackPressed() {
			if (backRequested) return
			backRequested = true
			val targetRoute = intent.getStringExtra(EXTRA_FROM_ROUTE).orEmpty()
			adSession.navigateAfterAd(targetRoute, ::launchScreenAd) {
				if (!isFinishing && !isDestroyed) {
					setResult(RESULT_OK, Intent().putExtra(EXTRA_RETURNED_FROM_ROUTE, screenName))
					finish()
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		check(screenName.isNotBlank()) { "ContentActivity screenName must not be blank" }
		hasEntered = savedInstanceState?.getBoolean(STATE_HAS_ENTERED) == true
		onBackPressedDispatcher.addCallback(this, backCallback)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == NAVIGATION_REQUEST_CODE && resultCode == RESULT_OK) {
			data?.getStringExtra(EXTRA_RETURNED_FROM_ROUTE)?.let {
				intent.putExtra(EXTRA_FROM_ROUTE, it)
			}
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putBoolean(STATE_HAS_ENTERED, hasEntered)
		super.onSaveInstanceState(outState)
	}

	override fun onResume() {
		super.onResume()
		if (isFinishing || isDestroyed) return
		adSession.onResume(
			hasEntered = hasEntered,
			markEntered = { hasEntered = true },
			launchAd = ::launchScreenAd,
			fromRoute = intent.getStringExtra(EXTRA_FROM_ROUTE).orEmpty()
		)
	}

	override fun onPause() {
		adSession.onPause()
		super.onPause()
	}

	/**
	 * Starts [intent] after this screen's leave ad has completed. The supplied
	 * target name must match the destination Activity's [screenName].
	 */
	protected fun navigateWithAd(toScreenName: String, intent: Intent) {
		require(toScreenName.isNotBlank()) { "The ad navigation target route must not be blank" }
		val routedIntent = intent
			.putExtra(EXTRA_FROM_ROUTE, screenName)
			.putExtra(EXTRA_TO_ROUTE, toScreenName)
		adSession.navigateAfterAd(toScreenName, ::launchScreenAd) {
			if (!isFinishing && !isDestroyed) {
				startActivityForResult(routedIntent, NAVIGATION_REQUEST_CODE)
			}
		}
	}

	private fun launchScreenAd(request: ScreenAdRequest): Boolean {
		if (isFinishing || isDestroyed) return false
		return showScreenAd(
			ScreenAdContext(
				areaKey = request.areaKey,
				adFormat = AdFormat.INTER,
				adPlatform = AdConfig.defaultPlatform,
				trigger = request.trigger,
				fromRoute = request.fromRoute,
				toRoute = request.toRoute
			)
		)
	}

	/** Hook for tests and host applications that need to observe or replace ad launching. */
	protected open fun showScreenAd(context: ScreenAdContext): Boolean =
		AdActivity.showAd(this, context)

	companion object {
		const val EXTRA_FROM_ROUTE = "com.mar2sdk.impl.extra.CONTENT_FROM_ROUTE"
		const val EXTRA_TO_ROUTE = "com.mar2sdk.impl.extra.CONTENT_TO_ROUTE"
		private const val EXTRA_RETURNED_FROM_ROUTE = "com.mar2sdk.impl.extra.CONTENT_RETURNED_FROM_ROUTE"
		private const val NAVIGATION_REQUEST_CODE = 0x4D52
		private const val STATE_HAS_ENTERED = "com.mar2sdk.impl.state.CONTENT_HAS_ENTERED"
	}
}
