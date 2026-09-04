package com.mar2sdk.core.util

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.Core
import com.mar2sdk.core.log.LogAppEvent
import com.mar2sdk.core.log.LogUtil
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 监听进程存活期间的手机状态变化。
 *
 * Home/最近任务依赖系统的关闭系统窗口广播，部分厂商系统可能不会发送该广播。
 */
object AppObs {

	private const val TAG = "AppObs"
	private const val ACTION_CLOSE_SYSTEM_DIALOGS = "android.intent.action.CLOSE_SYSTEM_DIALOGS"
	private const val EXTRA_SYSTEM_DIALOG_REASON = "reason"
	private const val SYSTEM_DIALOG_REASON_HOME = "homekey"
	private const val SYSTEM_DIALOG_REASON_RECENTS = "recentapps"
	private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
	private const val USB_CONNECTED = "connected"

	private val mainHandler = Handler(Looper.getMainLooper())
	private val listeners = CopyOnWriteArraySet<Listener>()
	private val registrationLock = Any()
	private val registeredReceivers = mutableSetOf<BroadcastReceiver>()
	private val registeredNetworkCallbacks = mutableSetOf<ConnectivityManager.NetworkCallback>()
	private var registeredApplication: Application? = null
	private var activityCallbacksRegistered = false
	private var startedActivityCount = 0
	private var volumeObserver: ContentObserver? = null
	private val mediaObservers = mutableListOf<ContentObserver>()
	private var defaultNetwork: Network? = null
	private val wifiNetworks = mutableSetOf<Network>()
	private var volumeSnapshot = emptyMap<Int, Int>()
	private var lastWifiState: WifiState? = null
	private var lastNetworkState: NetworkState? = null

	@Volatile
	var isCharging = false
		private set

	@Volatile
	var isUsbConnected = false
		private set

	@Volatile
	var isWifiConnected = false
		private set

	@Volatile
	var isNetworkConnected = false
		private set

	fun interface Listener {
		fun onEvent(event: Event)
	}

	sealed class Event {
		object HomePressed : Event()
		object RecentAppsPressed : Event()
		data class ForegroundChanged(val isForeground: Boolean) : Event()
		data class ScreenChanged(val isScreenOn: Boolean, val isLocked: Boolean) : Event()
		data class PackageChanged(val packageName: String, val change: PackageChange) : Event()
		data class MediaChanged(val collection: MediaCollection, val uri: Uri?) : Event()
		data class PowerChanged(val isCharging: Boolean) : Event()
		data class VolumeChanged(val streamType: Int, val volume: Int, val maxVolume: Int) : Event()
		data class UsbChanged(val isConnected: Boolean, val device: UsbDevice?) : Event()
		data class WifiChanged(val state: WifiState) : Event()
		data class NetworkChanged(val state: NetworkState) : Event()
	}

	enum class PackageChange { INSTALLED, REMOVED, REPLACED }
	enum class MediaCollection { IMAGES, VIDEOS, AUDIO, DOWNLOADS }
	enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, BLUETOOTH, OTHER }

	data class WifiState(val connected: Boolean, val validated: Boolean)

	data class NetworkState(
		val connected: Boolean,
		val validated: Boolean,
		val metered: Boolean,
		val transports: Set<Transport>,
	)

	fun addListener(listener: Listener) {
		listeners.add(listener)
	}

	fun removeListener(listener: Listener) {
		listeners.remove(listener)
	}

	/** Register all observers. Calling this repeatedly for the same Application is a no-op. */
	fun init(listener: Listener? = AppStatus.listener) {
		listener?.let(::addListener)
		val application = Core.app
		synchronized(registrationLock) {
			if (registeredApplication === application) return
			releaseLocked()
			registeredApplication = application
			observe("app lifecycle") { registerActivityCallbacks(application) }
			observe("initial state") { syncInitialState(application) }
			observe("system broadcasts") { registerSystemReceiver(application) }
			observe("packages") { registerPackageReceiver(application) }
			registerMediaObservers(application)
			observe("volume") { registerVolumeObserver(application) }
			application.getSystemService(ConnectivityManager::class.java)?.let { manager ->
				observe("Wi-Fi") { registerWifiCallback(manager) }
				observe("network") { registerNetworkCallback(manager) }
			}
		}
	}

	/** Unregister all observers owned by this object. */
	fun release() {
		synchronized(registrationLock) {
			releaseLocked()
			registeredApplication = null
		}
	}

	private fun syncInitialState(application: Application) {
		val powerManager = application.getSystemService(PowerManager::class.java)
		val keyguardManager = application.getSystemService(KeyguardManager::class.java)
		AppStatus.isScreenOn = powerManager?.isInteractive == true
		AppStatus.isLocked = keyguardManager?.isKeyguardLocked == true

		val batteryIntent = application.registerReceiver(null,
			IntentFilter(Intent.ACTION_BATTERY_CHANGED)
		)
		isCharging = batteryIntent?.isCharging() == true
		isUsbConnected = application.registerReceiver(null, IntentFilter(ACTION_USB_STATE))
			?.getBooleanExtra(USB_CONNECTED, false) == true
	}

	private fun registerActivityCallbacks(application: Application) {
		startedActivityCount = 0
		AppStatus.isForeground = false
		application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
		activityCallbacksRegistered = true
	}

	private fun registerSystemReceiver(application: Application) {
		val filter = IntentFilter().apply {
			addAction(ACTION_CLOSE_SYSTEM_DIALOGS)
			addAction(Intent.ACTION_SCREEN_ON)
			addAction(Intent.ACTION_SCREEN_OFF)
			addAction(Intent.ACTION_USER_PRESENT)
			addAction(Intent.ACTION_POWER_CONNECTED)
			addAction(Intent.ACTION_POWER_DISCONNECTED)
			addAction(Intent.ACTION_BATTERY_CHANGED)
			addAction(ACTION_USB_STATE)
			addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
			addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
		}
		ContextCompat.registerReceiver(
			application,
			systemReceiver,
			filter,
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		registeredReceivers.add(systemReceiver)
	}

	private fun registerPackageReceiver(application: Application) {
		val filter = IntentFilter().apply {
			addAction(Intent.ACTION_PACKAGE_ADDED)
			addAction(Intent.ACTION_PACKAGE_REMOVED)
			addAction(Intent.ACTION_PACKAGE_REPLACED)
			addDataScheme("package")
		}
		ContextCompat.registerReceiver(
			application,
			packageReceiver,
			filter,
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		registeredReceivers.add(packageReceiver)
	}

	private fun registerMediaObservers(application: Application) {
		MEDIA_COLLECTIONS.forEach { (collection, uri) ->
			observe("media collection $collection") {
				val observer = object : ContentObserver(mainHandler) {
					override fun onChange(selfChange: Boolean) {
						emit(Event.MediaChanged(collection, null))
					}

					override fun onChange(selfChange: Boolean, changedUri: Uri?) {
						emit(Event.MediaChanged(collection, changedUri))
					}
				}
				application.contentResolver.registerContentObserver(uri, true, observer)
				mediaObservers.add(observer)
			}
		}
	}

	private fun registerVolumeObserver(application: Application) {
		val audioManager = application.getSystemService(AudioManager::class.java) ?: return
		volumeSnapshot = readVolumes(audioManager)
		volumeObserver = object : ContentObserver(mainHandler) {
			override fun onChange(selfChange: Boolean) {
				val currentVolumes = readVolumes(audioManager)
				currentVolumes.forEach { (streamType, volume) ->
					if (volumeSnapshot[streamType] != volume) {
						emit(Event.VolumeChanged(streamType, volume, audioManager.getStreamMaxVolume(streamType)))
					}
				}
				volumeSnapshot = currentVolumes
			}
		}.also {
			application.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, it)
		}
	}

	private fun registerWifiCallback(manager: ConnectivityManager) {
		synchronized(wifiNetworks) {
			wifiNetworks.clear()
			manager.activeNetwork?.let { network ->
				if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
					wifiNetworks.add(network)
				}
			}
		}
		updateWifiState(manager)
		val callback = object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) {
				synchronized(wifiNetworks) { wifiNetworks.add(network) }
				updateWifiState(manager)
			}

			override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
				synchronized(wifiNetworks) {
					if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
						wifiNetworks.add(network)
					} else {
						wifiNetworks.remove(network)
					}
				}
				updateWifiState(manager)
			}

			override fun onLost(network: Network) {
				synchronized(wifiNetworks) { wifiNetworks.remove(network) }
				updateWifiState(manager)
			}
		}
		manager.registerNetworkCallback(
			NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
			callback,
		)
		registeredNetworkCallbacks.add(callback)
	}

	private fun registerNetworkCallback(manager: ConnectivityManager) {
		defaultNetwork = manager.activeNetwork
		updateNetworkState(manager.getNetworkCapabilities(defaultNetwork))
		val callback = object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) {
				defaultNetwork = network
				updateNetworkState(manager.getNetworkCapabilities(network))
			}

			override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
				defaultNetwork = network
				updateNetworkState(capabilities)
			}

			override fun onLost(network: Network) {
				if (defaultNetwork == network) {
					defaultNetwork = null
					updateNetworkState(null)
				}
			}
		}
		manager.registerDefaultNetworkCallback(callback)
		registeredNetworkCallbacks.add(callback)
	}

	private val systemReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			when (intent.action) {
				ACTION_CLOSE_SYSTEM_DIALOGS -> when (intent.getStringExtra(EXTRA_SYSTEM_DIALOG_REASON)) {
					SYSTEM_DIALOG_REASON_HOME -> emit(Event.HomePressed)
					SYSTEM_DIALOG_REASON_RECENTS -> emit(Event.RecentAppsPressed)
				}
				Intent.ACTION_SCREEN_ON,
				Intent.ACTION_SCREEN_OFF,
				Intent.ACTION_USER_PRESENT -> updateScreenState(context)
				Intent.ACTION_POWER_CONNECTED -> updatePowerState(true)
				Intent.ACTION_POWER_DISCONNECTED -> updatePowerState(false)
				Intent.ACTION_BATTERY_CHANGED -> updatePowerState(intent.isCharging())
				ACTION_USB_STATE -> updateUsbState(intent.getBooleanExtra(USB_CONNECTED, false), null)
				UsbManager.ACTION_USB_DEVICE_ATTACHED -> updateUsbState(true, intent.usbDevice())
				UsbManager.ACTION_USB_DEVICE_DETACHED -> updateUsbState(false, intent.usbDevice())
			}
		}
	}

	private val packageReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			val packageName = intent.data?.schemeSpecificPart ?: return
			val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
			val change = when (intent.action) {
				Intent.ACTION_PACKAGE_REPLACED -> PackageChange.REPLACED
				Intent.ACTION_PACKAGE_ADDED -> if (replacing) return else PackageChange.INSTALLED
				Intent.ACTION_PACKAGE_REMOVED -> if (replacing) return else PackageChange.REMOVED
				else -> return
			}
			emit(Event.PackageChanged(packageName, change))
		}
	}

	private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

		override fun onActivityStarted(activity: Activity) {
			startedActivityCount++
			if (!AppStatus.isForeground) {
				AppStatus.isForeground = true
				LogUtil.log(LogAppEvent.app_foreground, emptyMap())
				emit(Event.ForegroundChanged(true))
			}
		}

		override fun onActivityResumed(activity: Activity) = Unit

		override fun onActivityPaused(activity: Activity) = Unit

		override fun onActivityStopped(activity: Activity) {
			if (startedActivityCount > 0) startedActivityCount--
			if (startedActivityCount == 0 && AppStatus.isForeground && !activity.isChangingConfigurations) {
				AppStatus.isForeground = false
				LogUtil.log(LogAppEvent.app_background, emptyMap())
				emit(Event.ForegroundChanged(false))
			}
		}

		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

		override fun onActivityDestroyed(activity: Activity) = Unit
	}

	private fun updateScreenState(context: Context) {
		val powerManager = context.getSystemService(PowerManager::class.java)
		val keyguardManager = context.getSystemService(KeyguardManager::class.java)
		AppStatus.isScreenOn = powerManager?.isInteractive == true
		AppStatus.isLocked = keyguardManager?.isKeyguardLocked == true
		emit(Event.ScreenChanged(AppStatus.isScreenOn, AppStatus.isLocked))
	}

	private fun updatePowerState(charging: Boolean) {
		if (isCharging == charging) return
		isCharging = charging
		emit(Event.PowerChanged(charging))
	}

	private fun updateUsbState(connected: Boolean, device: UsbDevice?) {
		if (isUsbConnected == connected && device == null) return
		isUsbConnected = connected
		emit(Event.UsbChanged(connected, device))
	}

	private fun updateWifiState(manager: ConnectivityManager) {
		val networks = synchronized(wifiNetworks) { wifiNetworks.toList() }
		val capabilities = networks.mapNotNull(manager::getNetworkCapabilities)
		val state = WifiState(
			connected = capabilities.isNotEmpty(),
			validated = capabilities.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) },
		)
		isWifiConnected = state.connected
		if (state == lastWifiState) return
		lastWifiState = state
		emit(Event.WifiChanged(state))
	}

	private fun updateNetworkState(capabilities: NetworkCapabilities?) {
		val state = NetworkState(
			connected = capabilities != null,
			validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
			metered = capabilities != null &&
				!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
			transports = capabilities?.toTransports().orEmpty(),
		)
		isNetworkConnected = state.connected
		if (state == lastNetworkState) return
		lastNetworkState = state
		emit(Event.NetworkChanged(state))
	}

	private fun emit(event: Event) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			mainHandler.post { emit(event) }
			return
		}
		listeners.forEach { listener ->
			try {
				listener.onEvent(event)
			} catch (exception: RuntimeException) {
				Log.e(TAG, "Listener failed for $event", exception)
			}
		}
	}

	private inline fun observe(name: String, block: () -> Unit) {
		try {
			block()
		} catch (exception: RuntimeException) {
			Log.e(TAG, "Unable to observe $name", exception)
		}
	}

	private fun releaseLocked() {
		val application = registeredApplication
		if (application != null && activityCallbacksRegistered) {
			application.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
		}
		if (application != null) {
			registeredReceivers.forEach { receiver ->
				runCatching { application.unregisterReceiver(receiver) }
					.onFailure { Log.w(TAG, "Unable to unregister receiver", it) }
			}
		}
		volumeObserver?.let { observer ->
			application?.contentResolver?.unregisterContentObserver(observer)
		}
		if (application != null) {
			mediaObservers.forEach(application.contentResolver::unregisterContentObserver)
		}
		application?.getSystemService(ConnectivityManager::class.java)?.let { manager ->
			registeredNetworkCallbacks.forEach { callback ->
				runCatching { manager.unregisterNetworkCallback(callback) }
			}
		}
		registeredReceivers.clear()
		registeredNetworkCallbacks.clear()
		activityCallbacksRegistered = false
		startedActivityCount = 0
		AppStatus.isForeground = false
		volumeObserver = null
		mediaObservers.clear()
		defaultNetwork = null
		volumeSnapshot = emptyMap()
		lastWifiState = null
		lastNetworkState = null
		synchronized(wifiNetworks) { wifiNetworks.clear() }
		mainHandler.removeCallbacksAndMessages(null)
	}

	private fun readVolumes(audioManager: AudioManager): Map<Int, Int> = VOLUME_STREAMS.associateWith { stream ->
		audioManager.getStreamVolume(stream)
	}

	private fun Intent.isCharging(): Boolean = when (getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
		BatteryManager.BATTERY_STATUS_CHARGING,
		BatteryManager.BATTERY_STATUS_FULL -> true
		else -> false
	}

	@Suppress("DEPRECATION")
	private fun Intent.usbDevice(): UsbDevice? = getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice

	private fun NetworkCapabilities.toTransports(): Set<Transport> = buildSet {
		if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(Transport.WIFI)
		if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(Transport.CELLULAR)
		if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(Transport.ETHERNET)
		if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(Transport.VPN)
		if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(Transport.BLUETOOTH)
		if (isEmpty()) add(Transport.OTHER)
	}

	private val VOLUME_STREAMS = intArrayOf(
		AudioManager.STREAM_ALARM,
		AudioManager.STREAM_MUSIC,
		AudioManager.STREAM_NOTIFICATION,
		AudioManager.STREAM_RING,
		AudioManager.STREAM_SYSTEM,
		AudioManager.STREAM_VOICE_CALL,
	)

	private val MEDIA_COLLECTIONS = mapOf(
		MediaCollection.IMAGES to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
		MediaCollection.VIDEOS to MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
		MediaCollection.AUDIO to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
		MediaCollection.DOWNLOADS to MediaStore.Downloads.EXTERNAL_CONTENT_URI,
	)
}