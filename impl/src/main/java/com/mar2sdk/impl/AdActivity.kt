package com.mar2sdk.impl

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.status.ShowFailResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AdActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "AdActivity"
		private const val EXTRA_AD_CONTEXT = "com.mar2sdk.impl.extra.AD_CONTEXT"
		private val currentActivity = AtomicReference<WeakReference<AdActivity>?>(null)
		private val launchPending = AtomicBoolean(false)

		private fun activeActivity(): AdActivity? {
			val activity = currentActivity.get()?.get() ?: return null
			return activity.takeUnless { it.isFinishing || it.isDestroyed }
		}

		// 展示广告，返回是否成功启动广告页。
		fun showAd(activity: Activity, adContext: ScreenAdContext): Boolean {
			// TODO: 广告策略判断是否应该播放广告
			return tryShowAd(activity, adContext)
		}

		private fun tryShowAd(activity: Activity, adContext: ScreenAdContext): Boolean {
			if (activity.isFinishing || activity.isDestroyed) return false
			if (!launchPending.compareAndSet(false, true)) {
				if (Core.appMod == AppMod.DEBUG) {
					Toast.makeText(Core.app, "AdActivity 正在展示", Toast.LENGTH_LONG).show()
				}
				return false
			}
			if (activeActivity() != null) {
				launchPending.set(false)
				if (Core.appMod == AppMod.DEBUG) {
					Toast.makeText(Core.app, "AdActivity 正在展示", Toast.LENGTH_LONG).show()
				}
				return false
			}
			return try {
				val intent = Intent(activity, AdActivity::class.java)
					.putExtra(EXTRA_AD_CONTEXT, adContext)
				activity.startActivity(intent)
				true
			} catch (exception: RuntimeException) {
				launchPending.set(false)
				Log.e(TAG, "Unable to start ad activity for $adContext", exception)
				false
			}
		}

	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		currentActivity.set(WeakReference(this))
		launchPending.set(false)
		val adContext = try {
			IntentCompat.getParcelableExtra(intent, EXTRA_AD_CONTEXT, ScreenAdContext::class.java)
		} catch (exception: RuntimeException) {
			Log.e(TAG, "Unable to read ad context", exception)
			finish()
			return
		}
		if (adContext == null) {
			Log.e(TAG, "Missing ad context")
			finish()
			return
		}
		enableEdgeToEdge()
		setContentView(R.layout.activity_ad)
		findViewById<Button>(R.id.close_ad).setOnClickListener { finish() }
		showRequestedAd(adContext)
	}

	override fun onDestroy() {
		val reference = currentActivity.get()
		if (reference?.get() === this) {
			currentActivity.compareAndSet(reference, null)
		}
		super.onDestroy()
	}

	private fun showRequestedAd(context: ScreenAdContext) {
		val loading = findViewById<ProgressBar>(R.id.ad_loading)
		if (Core.appMod == AppMod.DEBUG) {
			Toast.makeText(Core.app, "展示广告 areaKey= ${context.areaKey}", Toast.LENGTH_LONG).show()
		}
		val callback = object : ShowCallback {
			override val adContext = context

			override fun showFailed(reason: ShowFailResult) = finish()
			override fun onAdClosed() = finish()
			override fun showSuccess() {
				loading.visibility = View.GONE
			}
			override fun onClicked() = Unit
			override fun onPaid() = Unit
			override fun onReward() = Unit
		}
		lifecycleScope.launch {
			try {
				Core.showAd(this@AdActivity, callback)
				loading.visibility = View.GONE
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				Log.e(TAG, "Unexpected failure while showing ${context.areaKey} (request ${context.requestId})", exception)
				finish()
			}
		}
	}

}
