package com.mar2sdk.impl

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.mar2sdk.core.notify.NotificationUtil

/**
 * 供业务层代码引用，参考MainActivity
 */
open class BaseActivity: AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		NotificationUtil.trackAppOpen(intent)
	}

}
