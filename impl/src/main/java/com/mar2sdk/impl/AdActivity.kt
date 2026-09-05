package com.mar2sdk.impl

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.ShowFailResult
import kotlinx.coroutines.launch

class AdActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "AdActivity"

		// TODO: 判断 AdActivity 是否正在展示
		fun adShowing() : Boolean {
			return false
		}

		fun showAd(activity: Activity, adFormat: AdFormat, areaKey: String) {
			val intent = Intent(activity, AdActivity::class.java)
			intent.putExtra("adFormat", adFormat.name)
			intent.putExtra("areaKey", areaKey)
			activity.startActivity(intent)
		}

	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(R.layout.activity_ad)

		showAd()
	}

	private fun showAd() {
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
			override fun showSuccess() {}
			override fun onClicked() {}
			override fun onPaid() {}
			override fun onReward() {}
		}
		lifecycleScope.launch {
			Core.showAd(this@AdActivity, callback, adFormat)
		}
	}

}