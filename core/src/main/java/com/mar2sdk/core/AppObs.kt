package com.mar2sdk.core

import android.util.Log

/**
 * 监听手机状态
 *
 * */
object AppObs {

	private const val TAG = "AppNotificationUtil"

	fun init() {
		obsHome()
		obsRecents()
		obsScreenStatus()
		obsInstall()
		obsFiles()
		obsPower()
		obsVolume()
		obsUsb()
		obsWifi()
		obsNet()
	}

	// TODO: 监听Home键 
	private fun obsHome() {
		try {

		} catch (e: Exception) {
			Log.e(TAG, "obsHome: ", e)
		}
	}

	// TODO: 监听RecentApps键 
	private fun obsRecents() {

	}

	// TODO: 监听屏幕亮屏和熄屏
	private fun obsScreenStatus() {
		
	}

	// TODO: 监听手机安装/卸载应用
	private fun obsInstall() {

	}

	// TODO: 监听手机文件的新增，修改，删除 
	private fun obsFiles() {

	}

	// TODO: 监听手机充电
	private fun obsPower() {
		
	}

	// TODO: 监听音量变化
	private fun obsVolume() {

	}

	// TODO: 监听USB
	private fun obsUsb() {

	}

	// TODO: 监听wifi连接情况
	private fun obsWifi() {

	}

	// TODO: 监听网络连接状态
	private fun obsNet() {

	}

}