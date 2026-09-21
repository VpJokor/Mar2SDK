package com.mar2sdk.debug

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdView
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.policy.ScreenAdTrigger
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.ShowFailResult
import com.mar2sdk.core.notify.NotificationUtil
import com.mar2sdk.core.notify.app.AppNotificationUtil
import com.mar2sdk.core.common.TestMod
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.common.status.UserType
import com.mar2sdk.impl.AdActivity

class DebugActivity : AppCompatActivity() {
	private var bannerView: AdView? = null
	private var bannerRequestId: String? = null

	private val notificationPermissionLauncher =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			handleNotificationPermissionResult(granted)
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(R.layout.activity_debug)
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
			insets
		}
		findViewById<View>(R.id.back).setOnClickListener {
			finish()
		}
		findViewById<View>(R.id.refresh_btn).setOnClickListener {
			refreshData()
			Toast.makeText(Core.app, "刷新成功", Toast.LENGTH_LONG).show()
		}
		findViewById<View>(R.id.user_info_card).setOnClickListener {
			openInfo("User")
		}
		findViewById<View>(R.id.log_btn).setOnClickListener {
			openInfo("Log")
		}
		findViewById<View>(R.id.config_btn).setOnClickListener {
			openInfo("Config")
		}
		findViewById<View>(R.id.notification_content).setOnClickListener {
			openInfo("Content")
		}
		findViewById<View>(R.id.notification_config).setOnClickListener {
			openInfo(InfoActivity.NOTIFICATION_POLICY_LABEL)
		}
		findViewById<View>(R.id.ad_config).setOnClickListener {
			openInfo(InfoActivity.AD_POLICY_LABEL)
		}
		findViewById<View>(R.id.send_test_fcm).setOnClickListener {
			Toast.makeText(this@DebugActivity, "发送FCM测试信息", Toast.LENGTH_LONG).show()
		}
		findViewById<View>(R.id.send_notification).setOnClickListener {
			if (NotificationUtil.hasNotiAccess()) {
				AppNotificationUtil.sendNotificationContent("TEST")
			} else {
				Toast.makeText(Core.app, "没有通知权限", Toast.LENGTH_LONG).show()
			}
		}
		findViewById<View>(R.id.req_notification).setOnClickListener {
			NotificationUtil.reqNotiAccess(
				activity = this@DebugActivity,
				launcher = notificationPermissionLauncher,
				onResult = ::handleNotificationPermissionResult
			)
		}
		findViewById<View>(R.id.per_notification).setOnClickListener {
			if (NotificationUtil.hasNotiAccess()) {
				Core.startFGS()
			} else {
				Toast.makeText(Core.app, "没有通知权限", Toast.LENGTH_LONG).show()
			}
		}

		findViewById<View>(R.id.change_test_mod).setOnClickListener {
			if (Core.testMod == TestMod.FORCE) {
				Core.testMod = TestMod.POLICY
			} else {
				Core.testMod = TestMod.FORCE
			}
			refreshData()
		}
		findViewById<View>(R.id.test_user).setOnClickListener {
			if ((Core.appMod == AppMod.DEBUG || Core.appMod == AppMod.TEST) && Core.testMod == TestMod.FORCE) {
				val userIndex = UserType.entries.indexOf(Core.userType)
				var nextIndex = userIndex + 1
				if (nextIndex >= UserType.entries.size) nextIndex = 0
				Core.userType = UserType.entries[nextIndex]
				refreshData()
			} else {
				Toast.makeText(this@DebugActivity, "仅 FORCE 测试模式下可用", Toast.LENGTH_LONG).show()
			}
		}

		findViewById<View>(R.id.test_open).setOnClickListener {
			showTestAd(AreaKeys.KEY_TEST_OPEN, AdFormat.OPEN)
		}
		findViewById<View>(R.id.test_inter).setOnClickListener {
			showTestAd(AreaKeys.KEY_TEST_INTER, AdFormat.INTER)
		}
		findViewById<View>(R.id.test_video).setOnClickListener {
			showTestAd(AreaKeys.KEY_TEST_VIDEO, AdFormat.VIDEO)
		}
		findViewById<View>(R.id.test_open_inter).setOnClickListener {
			showTestAd(AreaKeys.KEY_TEST_OPEN_INTER, AdFormat.VIDEO)
		}
		findViewById<View>(R.id.test_inter_video).setOnClickListener {
			showTestAd(AreaKeys.KEY_TEST_INTER_VIDEO, AdFormat.VIDEO)
		}
		findViewById<View>(R.id.test_banner).setOnClickListener {
			showTestBanner()
		}
		findViewById<View>(R.id.destroy_banner).apply {
			isEnabled = false
			setOnClickListener { destroyTestBanner() }
		}
		findViewById<View>(R.id.req_ump).setOnClickListener {
			setConsentButtonsEnabled(false)
			// 命中缓存时不会触发回调，需要直接继续展示流程。
			val cached = Core.initConsent(this@DebugActivity, ::handleConsentRequestResult)
			if (cached) handleConsentRequestResult(true)
		}
		findViewById<View>(R.id.show_ump).setOnClickListener {
			if (Core.isPrivacyOptionsRequired) {
				Core.showPrivacyOptions(this@DebugActivity)
			} else {
				Toast.makeText(this@DebugActivity, "当前无需展示UMP隐私选项", Toast.LENGTH_LONG).show()
			}
		}

	}

	private fun handleConsentRequestResult(success: Boolean) {
		if (isFinishing || isDestroyed) return
		if (success) {
			Core.showSplashConsent(this) {
				if (isFinishing || isDestroyed) return@showSplashConsent
				setConsentButtonsEnabled(true)
				Toast.makeText(this, "UMP同意流程结束", Toast.LENGTH_SHORT).show()
			}
		} else {
			setConsentButtonsEnabled(true)
			Toast.makeText(this, "UMP请求失败", Toast.LENGTH_SHORT).show()
		}
	}

	private fun setConsentButtonsEnabled(enabled: Boolean) {
		findViewById<View>(R.id.req_ump).isEnabled = enabled
		findViewById<View>(R.id.show_ump).isEnabled = enabled
	}

	private fun showTestAd(area: String, adFormat: AdFormat) {
		AdActivity.showAd(
			this,
			ScreenAdContext(
				areaKey = area,
				adFormat = adFormat,
				adPlatform = AdConfig.defaultPlatform,
				trigger = ScreenAdTrigger.UNKNOW
			)
		)
	}

	private fun showTestBanner() {
		// 重复点击展示时，先移除并释放上一次请求的广告。
		destroyTestBanner()
		val context = ScreenAdContext(
			areaKey = AreaKeys.KEY_TEST_BANNER,
			adFormat = AdFormat.BANNER,
			adPlatform = AdConfig.defaultPlatform,
			trigger = ScreenAdTrigger.UNKNOW,
		)
		bannerRequestId = context.requestId
		findViewById<TextView>(R.id.banner_status).setText(R.string.banner_status_loading)
		val view = Core.getBanner(this, object : ShowCallback {
			override val adContext = context

			private fun updateStatus(message: String) {
				// 销毁或替换 Banner 后，旧请求的延迟回调不再更新页面。
				if (bannerRequestId != adContext.requestId || isFinishing || isDestroyed) return
				findViewById<TextView>(R.id.banner_status).text = message
			}

			override fun showFailed(reason: ShowFailResult) {
				updateStatus(getString(R.string.banner_status_failed, reason.name))
			}

			override fun showSuccess() {
				updateStatus(getString(R.string.banner_status_showing))
			}

			override fun onClicked() {
				updateStatus(getString(R.string.banner_status_clicked))
			}

			override fun onAdClosed() {
				updateStatus(getString(R.string.banner_status_closed))
			}

			override fun onPaid() {
				updateStatus(getString(R.string.banner_status_paid))
			}

			override fun onReward() = Unit
		})
		bannerView = view
		if (view == null) {
			bannerRequestId = null
			return
		}
		findViewById<FrameLayout>(R.id.banner_container).apply {
			visibility = View.VISIBLE
			addView(view, FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.CENTER,
			))
		}
		findViewById<View>(R.id.destroy_banner).apply {
			isEnabled = true
			alpha = 1f
		}
	}

	private fun destroyTestBanner() {
		bannerRequestId = null
		val view = bannerView
		bannerView = null
		// 先从父容器移除，再销毁；清空引用后可安全重复调用。
		(view?.parent as? ViewGroup)?.removeView(view)
		view?.destroy()
		findViewById<View>(R.id.banner_container).visibility = View.GONE
		findViewById<View>(R.id.destroy_banner).apply {
			isEnabled = false
			alpha = 0.5f
		}
		findViewById<TextView>(R.id.banner_status).setText(R.string.banner_status_destroyed)
	}

	private fun handleNotificationPermissionResult(granted: Boolean) {
		if (granted) {
			Toast.makeText(this, "通知权限请求成功", Toast.LENGTH_SHORT).show()
		} else {
			Toast.makeText(this, "通知权限未授权", Toast.LENGTH_SHORT).show()
		}
	}

	private fun openInfo(label: String) {
		val intent = Intent(this@DebugActivity, InfoActivity::class.java)
		intent.putExtra("label", label)
		startActivity(intent)
	}

	override fun onResume() {
		super.onResume()
		bannerView?.resume()
		refreshData()
	}

	override fun onPause() {
		bannerView?.pause()
		super.onPause()
	}

	override fun onDestroy() {
		destroyTestBanner()
		super.onDestroy()
	}

	fun refreshData() {
		findViewById<TextView>(R.id.app_mod).text = Core.appMod.name
		findViewById<TextView>(R.id.user_type).text = Core.userType.name
		findViewById<TextView>(R.id.risk_ip).text = UserInfo.riskIP.name
		findViewById<TextView>(R.id.risk_package).text = UserInfo.riskPackage.name
		findViewById<TextView>(R.id.ecpm_type).text = UserInfo.ecpmType.name
		findViewById<TextView>(R.id.test_mod_name).text = Core.testMod.name
		if (Core.testMod == TestMod.POLICY) {
			findViewById<TextView>(R.id.test_user_name).text = "关闭"
		} else {
			findViewById<TextView>(R.id.test_user_name).text = Core.userType.name
		}

	}
}
