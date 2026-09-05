package com.mar2sdk.impl

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

class AdActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "AdActivity"
		private val currentActivity = AtomicReference<WeakReference<AdActivity>?>(null)

		fun showing(): Boolean {
			val activity = currentActivity.get()?.get() ?: return false
			return !activity.isFinishing && !activity.isDestroyed
		}

		// adFormat 以远端配置的为主，如果没有远端配置则使用传入的 adFormat
		fun showAd(activity: Activity, adFormat: AdFormat = AdFormat.INTER_VIDEO, areaKey: String) {
			if (showing()) {
				if (Core.appMod == AppMod.DEBUG) {
					Toast.makeText(Core.app, "AdActivity 正在展示", Toast.LENGTH_LONG).show()
				}
				return
			}
			val intent = Intent(activity, AdActivity::class.java)
			intent.putExtra("adFormat", adFormat.name)
			intent.putExtra("areaKey", areaKey)
			activity.startActivity(intent)
		}

	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		currentActivity.set(WeakReference(this))
		enableEdgeToEdge()
		setContentView(R.layout.activity_ad)
		findViewById<Button>(R.id.close_ad).setOnClickListener { finish() }
		showAd()
	}

	override fun onDestroy() {
		val reference = currentActivity.get()
		if (reference?.get() === this) {
			currentActivity.compareAndSet(reference, null)
		}
		super.onDestroy()
	}

	private fun showAd() {
		val loading = findViewById<ProgressBar>(R.id.ad_loading)
		val adFormat = AdFormat.valueOf(intent.getStringExtra("adFormat") ?: "OPEN")
		val areaKey = intent.getStringExtra("areaKey") ?: "unknow"
		val callback = object : ShowCallback{
			override var areaKey: String
				get() = areaKey
				set(value) {}
			override lateinit var adFormat: AdFormat

			override lateinit var adPlatform: AdPlatform

			override fun showFailed(reason: ShowFailResult) {
				finish()
			}
			override fun onAdClosed() {
				finish()
			}
			override fun showSuccess() {
				loading.visibility = View.GONE
			}
			override fun onClicked() {}
			override fun onPaid() {}
			override fun onReward() {}
		}
		lifecycleScope.launch {
			Core.showAd(this@AdActivity, callback, adFormat)
			loading.visibility = View.GONE
		}
	}

}
