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
import androidx.lifecycle.lifecycleScope
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.ShowFailResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AdActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "AdActivity"
		private val currentActivity = AtomicReference<WeakReference<AdActivity>?>(null)
		private val launchPending = AtomicBoolean(false)

		private fun activeActivity(): AdActivity? {
			val activity = currentActivity.get()?.get() ?: return null
			return activity.takeUnless { it.isFinishing || it.isDestroyed }
		}

		// 保留无返回值的公开入口，内部入口用于判断是否成功启动。
		fun showAd(activity: Activity, adFormat: AdFormat = AdFormat.INTER_VIDEO, areaKey: String) {
			// TODO: 广告策略判断是否应该播放广告
			tryShowAd(activity, adFormat, areaKey)
		}

		internal fun tryShowAd(activity: Activity, adFormat: AdFormat, areaKey: String): Boolean {
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
			val intent = Intent(activity, AdActivity::class.java)
			intent.putExtra("adFormat", adFormat.name)
			intent.putExtra("areaKey", areaKey)
			return try {
				activity.startActivity(intent)
				true
			} catch (exception: RuntimeException) {
				launchPending.set(false)
				Log.e(TAG, "Unable to start ad activity for $areaKey", exception)
				false
			}
		}

	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		currentActivity.set(WeakReference(this))
		launchPending.set(false)
		enableEdgeToEdge()
		setContentView(R.layout.activity_ad)
		findViewById<Button>(R.id.close_ad).setOnClickListener { finish() }
		showRequestedAd()
	}

	override fun onDestroy() {
		val reference = currentActivity.get()
		if (reference?.get() === this) {
			currentActivity.compareAndSet(reference, null)
		}
		super.onDestroy()
	}

	private fun showRequestedAd() {
		val loading = findViewById<ProgressBar>(R.id.ad_loading)
		val requestedAdFormat = AdFormat.valueOf(intent.getStringExtra("adFormat") ?: "OPEN")
		val requestedAreaKey = intent.getStringExtra("areaKey") ?: "unknown"
		if (Core.appMod == AppMod.DEBUG) {
			Toast.makeText(Core.app, "展示广告 areaKey= $requestedAreaKey", Toast.LENGTH_LONG).show()
		}
		val callback = object : ShowCallback {
			override var areaKey = requestedAreaKey
			override var adFormat = requestedAdFormat
			override lateinit var adPlatform: AdPlatform

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
				Core.showAd(this@AdActivity, callback, requestedAdFormat)
				loading.visibility = View.GONE
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				Log.e(TAG, "Unexpected failure while showing $requestedAreaKey", exception)
				finish()
			}
		}
	}

}
