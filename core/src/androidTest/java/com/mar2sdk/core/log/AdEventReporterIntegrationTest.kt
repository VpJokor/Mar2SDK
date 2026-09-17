package com.mar2sdk.core.log

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.thinkingdata.analytics.TDAnalytics
import cn.thinkingdata.analytics.TDConfig
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.CommonConfig
import com.mar2sdk.core.common.PolicyKey
import com.mar2sdk.core.common.PreferenceUtil
import com.mar2sdk.core.common.net.NetUtil
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证真实登录回调、事件采集、SQLite 队列和 HTTP 客户端组成的完整链路。 */
@RunWith(AndroidJUnit4::class)
class AdEventReporterIntegrationTest {
	@Test
	fun loginEnablesConfiguredEventsAndBusinessFailureRetriesTheOriginalEvents() = runBlocking(Dispatchers.IO) {
		val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
		val uid = 1542198364433551363L + (System.nanoTime() and 0xfffff)
		val identity = AdReportIdentity(APP_ID, uid)
		val marker = UUID.randomUUID().toString()
		val thinkingKey = marker.replace("-", "")
		val restorations = mutableListOf<() -> Unit>()
		val previousAppID = CommonConfig.serverAppID
		val previousClientKey = CommonConfig.serverClientKey
		val previousUrl = CommonConfig.serverUrl
		val previousLoginPath = CommonConfig.platformLoginPath
		val previousFbEvents = LogConfig.fbEvents
		val previousLocalEvents = LogConfig.localEvents
		val previousThEvents = LogConfig.thEvents
		val previousNetEvents = LogConfig.netEvents
		val previousBatchSize = LogConfig.reportBatchSize
		val previousFlushIntervalMillis = LogConfig.reportFlushIntervalMillis
		restorations += {
			LogConfig.fbEvents = previousFbEvents
			LogConfig.localEvents = previousLocalEvents
			LogConfig.thEvents = previousThEvents
			LogConfig.netEvents = previousNetEvents
			LogConfig.reportBatchSize = previousBatchSize
			LogConfig.reportFlushIntervalMillis = previousFlushIntervalMillis
		}
		val preferences = app.getSharedPreferences("preference", Context.MODE_PRIVATE)
		val preferenceKeys = listOf(
			"mar2sdk.login_user.$APP_ID", "mar2sdk.singular_attribution.$APP_ID",
			"sf_device_id", "sf_temp_uid", PolicyKey.KEY_NETWORK,
		)
		val previousPreferences = preferenceKeys.associateWith { preferences.getString(it, null) }
		for ((type, fieldName) in listOf(
			Core::class.java to "app",
			Core::class.java to "appMod",
			PreferenceUtil::class.java to "sharedPreferences",
			TDAnalytics::class.java to "instance",
			AdEventReporter::class.java to "identity",
		)) {
			val field = type.getDeclaredField(fieldName).apply { isAccessible = true }
			val previous = field.get(null)
			restorations += { field.set(null, previous) }
		}
		@Suppress("UNCHECKED_CAST")
		val attributionSyncs = NetUtil::class.java.getDeclaredField("attributionSyncs")
			.apply { isAccessible = true }.get(null) as MutableMap<Int, Any>
		val previousAttributionSync = attributionSyncs[APP_ID]
		restorations += {
			if (previousAttributionSync == null) attributionSyncs.remove(APP_ID)
			else attributionSyncs[APP_ID] = previousAttributionSync
		}
		val server = LocalReportServer(uid)
		val store = SqliteAdReportStore(app)
		var thinkingInitialized = false
		try {
			Core.app = app
			Core.appMod = AppMod.TEST
			PreferenceUtil.resetForTests()
			PreferenceUtil.init()
			attributionSyncs.remove(APP_ID)
			preferenceKeys.take(2).plus(PolicyKey.KEY_NETWORK).forEach(PreferenceUtil::removeByKey)
			// 单独保存的分析属性不应代替完整归因快照触发用户归因上报。
			PreferenceUtil.commitString(PolicyKey.KEY_NETWORK, "cached-analysis-network")
			CommonConfig.serverAppID = APP_ID
			CommonConfig.serverClientKey = CLIENT_KEY
			CommonConfig.serverUrl = server.url
			CommonConfig.platformLoginPath = LOGIN_PATH
			// 仅启用自有服务端渠道，避免路由验证触发其他日志系统。
			LogConfig.fbEvents = emptyList()
			LogConfig.localEvents = emptyList()
			LogConfig.thEvents = emptyList()
			LogConfig.netEvents = listOf("*")
			LogConfig.reportBatchSize = 20
			LogConfig.reportFlushIntervalMillis = 100L
			withContext(Dispatchers.Main) {
				// 隔离默认数数实例，避免影响其他设备测试。
				TDAnalytics::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
				TDAnalytics.init(TDConfig.getInstance(app, thinkingKey, server.url))
				thinkingInitialized = true
				TDAnalytics.setTrackStatus(TDAnalytics.TDTrackStatus.SAVE_ONLY)
			}
			val distinctId = TDAnalytics.getDistinctId()
			assertFalse(distinctId.isNullOrBlank())
			val login = withTimeout(10_000) { NetUtil.platformLogin(APP_ID, CLIENT_KEY, Core.SDK_VERSION) }
			assertEquals(uid, login.getOrThrow().uid)
			assertEquals(uid, NetUtil.getPlatformLoginUser()?.uid)

			val commonParams = mapOf<String, Any>(
				"request_id" to marker,
				"ad_platform" to "ADMOB",
				"ad_source" to "AdMob",
				"ad_unit_name" to "unit-中文&=+",
				"ad_preload" to false,
				"extra" to JSONObject().put("source", "integration").put("values", JSONArray().put(1).put(true)),
			)
			LogUtil.logNet(LogAdEvent.ad_impression, commonParams + ("format" to "INTER"))
			LogUtil.logNet(LogAdEvent.ad_revenue, commonParams + mapOf("value" to 0.000153, "currency" to "USD", "ad_format" to "INTER"))
			LogUtil.logNet(LogAdEvent.ad_click, commonParams + mapOf("format" to "INTER", "duration_time" to 14356L))
			// 自定义事件通过统一入口和通配符配置上报，无需广告收入字段。
			LogUtil.log("level_complete", commonParams + mapOf("level_id" to 7, "value" to "bonus", "currency" to "gems"))

			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
			val failed = server.nextReport(deadline)
			val retried = server.nextReport(deadline)
			assertEquals("Retries must preserve the complete serialized events", failed.form.getValue("data"), retried.form.getValue("data"))
			val received = linkedMapOf<String, JSONObject>()
			fun inspect(request: RecordedRequest) {
				assertEquals(REPORT_PATH, request.path)
				assertEquals("POST", request.method)
				assertEquals("application/x-www-form-urlencoded", request.headers["content-type"])
				assertEquals(APP_ID.toString(), request.headers["appid"])
				assertEquals(Core.SDK_VERSION, request.headers["sdkversion"])
				assertEquals("2", request.headers["platformid"])
				val form = request.form
				assertEquals(setOf("appID", "timestamp", "data", "sign"), form.keys)
				assertEquals(APP_ID.toString(), form["appID"])
				assertEquals(request.headers.getValue("devicetime").toLong() / 1000, form.getValue("timestamp").toLong())
				val raw = "appID=$APP_ID&data=${form.getValue("data")}&timestamp=${form.getValue("timestamp")}&secretKey=$CLIENT_KEY"
				val sign = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
					.joinToString("") { "%02X".format(it.toInt() and 0xff) }
				assertEquals(sign, form["sign"])
				val events = JSONArray(form.getValue("data"))
				assertTrue(events.length() > 0)
				for (index in 0 until events.length()) {
					val event = events.getJSONObject(index)
					assertEquals(uid.toString(), event.get("#account_id").toString())
					assertEquals(distinctId, event.getString("#distinct_id"))
					assertEquals("track", event.getString("#type"))
					assertTrue(event.getString("#time").isNotBlank())
					assertTrue(event.getString("#uuid").isNotBlank())
					val eventId = event.get("#event_id")
					assertTrue("Event ID must remain an integral JSON number", eventId is Long || eventId is Int)
					assertTrue(eventId.toString().toLong() > 0)
					val properties = event.getJSONObject("properties")
					assertEquals(app.packageName, properties.getString("#bundle_id"))
					assertEquals("Android", properties.getString("#lib"))
					assertEquals("Android", properties.getString("#os"))
					assertEquals(TDAnalytics.getSDKVersion(), properties.getString("#lib_version"))
					assertEquals("Native_SDK", properties.getString("#data_source"))
					assertTrue(properties.has("#device_model"))
					assertTrue(properties.has("#zone_offset"))
					assertEquals(marker, properties.getString("request_id"))
					assertEquals("unit-中文&=+", properties.getString("ad_unit_name"))
					assertFalse(properties.getBoolean("ad_preload"))
					assertEquals("integration", properties.getJSONObject("extra").getString("source"))
					received[event.getString("#event_name")] = event
				}
			}
			inspect(failed)
			inspect(retried)
			while (received.size < 4) inspect(server.nextReport(deadline))
			assertEquals(setOf("ad_impression", "ad_revenue", "ad_click", "level_complete"), received.keys)
			assertEquals(4, received.values.map { it.getString("#uuid") }.toSet().size)
			assertEquals(4, received.values.map { it.get("#event_id").toString() }.toSet().size)
			assertEquals(0.000153, received.getValue("ad_revenue").getJSONObject("properties").getDouble("value"), 0.0)
			assertEquals("USD", received.getValue("ad_revenue").getJSONObject("properties").getString("currency"))
			assertEquals(14356L, received.getValue("ad_click").getJSONObject("properties").getLong("duration_time"))
			val customProperties = received.getValue("level_complete").getJSONObject("properties")
			assertEquals(7, customProperties.getInt("level_id"))
			assertEquals("bonus", customProperties.getString("value"))
			assertEquals("gems", customProperties.getString("currency"))
			withTimeout(5_000) {
				while (store.peek(identity, 100, Int.MAX_VALUE).isNotEmpty()) delay(25)
			}

			// 具名配置允许自定义事件，并过滤未启用的事件。
			LogConfig.netEvents = listOf("purchase_success")
			LogUtil.log("disabled_custom_event", commonParams)
			LogUtil.log("purchase_success", commonParams + ("item_id" to "item-中文&=+"))
			val namedRequest = server.nextReport(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
			val namedEvents = JSONArray(namedRequest.form.getValue("data"))
			assertEquals(1, namedEvents.length())
			assertEquals("purchase_success", namedEvents.getJSONObject(0).getString("#event_name"))
			inspect(namedRequest)
			assertEquals("item-中文&=+", received.getValue("purchase_success").getJSONObject("properties").getString("item_id"))
			withTimeout(5_000) {
				while (store.peek(identity, 100, Int.MAX_VALUE).isNotEmpty()) delay(25)
			}
			assertNull("未启用的事件不应触发上报", server.pollReport(500))

			val reportCount = server.reportCount.get()
			val validEvent = JSONArray().put(received.getValue("ad_impression"))
			val wrongApp = NetUtil.report(validEvent, APP_ID + 1, uid).await().exceptionOrNull()
			val wrongUser = NetUtil.report(validEvent, APP_ID, uid + 1).await().exceptionOrNull()
			assertTrue(wrongApp is IllegalArgumentException)
			assertTrue(wrongApp?.message.orEmpty().contains("product"))
			assertTrue(wrongUser is IllegalArgumentException)
			assertTrue(wrongUser?.message.orEmpty().contains("user"))
			assertEquals(reportCount, server.reportCount.get())
			NetUtil.uploadUser().join()
			assertEquals(1, server.paths.count { it == LOGIN_PATH })
			assertFalse(server.paths.any { it.endsWith("/uploadUser") })
			server.assertHealthy()
		} finally {
			AdEventReporter.onLogout(APP_ID)
			server.close()
			try {
				// 采集后的入库在 IO 协程执行，清理前等待已调度的写入完成。
				delay(100)
				val ownIds = store.peek(identity, 100, Int.MAX_VALUE).filter {
					JSONObject(it.data).getJSONObject("properties").optString("request_id") == marker
				}.map { it.id }
				store.remove(ownIds)
			} finally {
				store.close()
				withContext(Dispatchers.Main) {
					if (thinkingInitialized) TDAnalytics.setTrackStatus(TDAnalytics.TDTrackStatus.STOP)
					TDAnalytics.sInstances.remove(thinkingKey)
				}
				CommonConfig.serverAppID = previousAppID
				CommonConfig.serverClientKey = previousClientKey
				CommonConfig.serverUrl = previousUrl
				CommonConfig.platformLoginPath = previousLoginPath
				preferences.edit().apply {
					previousPreferences.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
				}.commit()
				restorations.asReversed().forEach { it() }
			}
		}
	}

	private data class RecordedRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
		val form: Map<String, String>
			get() = body.split('&').associate { field ->
				val parts = field.split('=', limit = 2)
				URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
			}
	}

	private class LocalReportServer(private val uid: Long) : Closeable {
		private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
		private val connections = ConcurrentHashMap.newKeySet<Socket>()
		private val workers = Executors.newCachedThreadPool { task -> Thread(task, "ReportTestHttp").apply { isDaemon = true } }
		private val reports = LinkedBlockingQueue<RecordedRequest>()
		private val failure = AtomicReference<Throwable?>()
		@Volatile private var open = true
		val reportCount = AtomicInteger()
		val paths = CopyOnWriteArrayList<String>()
		val url = "http://127.0.0.1:${socket.localPort}"
		private val acceptor = Thread({
			while (open) {
				try {
					val connection = socket.accept()
					connections += connection
					workers.execute { respond(connection) }
				} catch (error: Exception) {
					if (open) failure.compareAndSet(null, error)
				}
			}
		}, "ReportTestAccept").apply { isDaemon = true; start() }

		fun nextReport(deadlineNanos: Long): RecordedRequest {
			val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
			return reports.poll(remaining, TimeUnit.NANOSECONDS)
				?: throw AssertionError("No report received before deadline; paths=$paths", failure.get())
		}

		fun assertHealthy() {
			failure.get()?.let { throw AssertionError("Local HTTP responder failed", it) }
		}

		fun pollReport(timeoutMillis: Long): RecordedRequest? = reports.poll(timeoutMillis, TimeUnit.MILLISECONDS)

		private fun respond(connection: Socket) {
			try {
				connection.use {
					it.soTimeout = 5_000
					val input = it.getInputStream().buffered()
					val requestLine = readLine(input).split(' ')
					val headers = linkedMapOf<String, String>()
					while (true) {
						val line = readLine(input)
						if (line.isEmpty()) break
						val colon = line.indexOf(':')
						require(colon > 0)
						headers[line.substring(0, colon).lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
					}
					val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
					var offset = 0
					while (offset < body.size) {
						val count = input.read(body, offset, body.size - offset)
						if (count < 0) throw EOFException("Incomplete request body")
						offset += count
					}
					val request = RecordedRequest(requestLine[0], requestLine[1], headers, body.toString(Charsets.UTF_8))
					paths += request.path
					val response = when (request.path) {
						LOGIN_PATH -> JSONObject().put("code", 0).put("data", JSONObject()
							.put("uid", uid).put("name", "integration-user").put("token", "local-test-token")
							.put("expiredTime", 3600).put("registerTime", System.currentTimeMillis()).put("accountType", 1)).toString()
						REPORT_PATH -> if (reportCount.incrementAndGet() == 1) "{\"code\":-1,\"msg\":\"retry this batch\"}" else "{\"code\":0}"
						else -> "{\"code\":0}"
					}.toByteArray(Charsets.UTF_8)
					val output = it.getOutputStream()
					output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
					output.write(response)
					output.flush()
					if (request.path == REPORT_PATH) reports.put(request)
				}
			} catch (error: Exception) {
				if (open) failure.compareAndSet(null, error)
			} finally {
				connections -= connection
			}
		}

		private fun readLine(input: InputStream): String {
			val line = ByteArrayOutputStream()
			while (true) {
				val byte = input.read()
				if (byte < 0) throw EOFException("Incomplete HTTP headers")
				if (byte == '\n'.code) return line.toString("US-ASCII").removeSuffix("\r")
				line.write(byte)
				require(line.size() <= 64 * 1024) { "HTTP header too large" }
			}
		}

		override fun close() {
			open = false
			socket.close()
			connections.forEach { runCatching { it.close() } }
			workers.shutdownNow()
			acceptor.join(1_000)
		}
	}

	private companion object {
		const val APP_ID = 900001
		const val CLIENT_KEY = "test-secret"
		const val LOGIN_PATH = "/server/user/platformLogin"
		const val REPORT_PATH = "/report/data/report"
	}
}
