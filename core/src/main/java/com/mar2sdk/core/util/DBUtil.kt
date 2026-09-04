package com.mar2sdk.core.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.sqlite.transaction
import com.mar2sdk.core.Core
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors

/**
 * 处理sqlite相关代码逻辑
 */
object DBUtil {
	private const val TAG = "DBUtil"
	private const val DATABASE_NAME = "mar2sdk_logs.db"
	private const val DATABASE_VERSION = 1

	private const val TABLE_LOG = "local_log"
	private const val COLUMN_ID = "id"
	private const val COLUMN_EVENT_NAME = "event_name"
	private const val COLUMN_PARAMS_JSON = "params_json"
	private const val COLUMN_EVENT_TIME_MS = "event_time_ms"

	private const val DEFAULT_QUERY_LIMIT = 100
	private const val MAX_QUERY_LIMIT = 500
	private const val MAX_LOG_COUNT = 10_000
	private const val MAX_EVENT_NAME_LENGTH = 128
	private const val MAX_PARAMS_SIZE_BYTES = 64 * 1024
	private const val MAX_PENDING_COMMANDS = 64
	private const val CLEANUP_INTERVAL = 100
	private const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
	private const val DATABASE_OPERATION_TIMEOUT_MILLIS = 5_000L

	private val databaseDispatcher = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "Mar2SDK-LogDatabase").apply { isDaemon = true }
	}.asCoroutineDispatcher()
	private val databaseScope = CoroutineScope(SupervisorJob() + databaseDispatcher)
	private val databaseCommands = Channel<DatabaseCommand>(MAX_PENDING_COMMANDS)

	@Volatile
	private var databaseHelper: LogDatabaseHelper? = null
	private var successfulInsertsSinceCleanup = 0

	data class LocalLog(
		val id: Long,
		val eventName: String,
		val paramsJson: String,
		val eventTimeMillis: Long,
	)

	private sealed interface DatabaseCommand {
		data class Insert(
			val eventName: String,
			val paramsJson: String,
			val eventTimeMillis: Long,
		) : DatabaseCommand

		data class Query(
			val limit: Int,
			val beforeId: Long,
			val result: CompletableDeferred<List<LocalLog>>,
		) : DatabaseCommand

		data class DeleteBefore(
			val eventTimeMillis: Long,
			val result: CompletableDeferred<Int>,
		) : DatabaseCommand

		data class Clear(val result: CompletableDeferred<Int>) : DatabaseCommand
	}

	fun init() {
		init(Core.app.applicationContext)
	}

	@Synchronized
	internal fun init(context: Context) {
		if (databaseHelper != null) return

		val helper = LogDatabaseHelper(context.applicationContext).apply {
			setWriteAheadLoggingEnabled(true)
		}
		databaseHelper = helper
		databaseScope.launch {
			processDatabaseCommands(helper)
		}
	}

	/** 将日志放入后台单线程队列，避免阻塞调用线程。 */
	fun insertLog(
		eventName: String,
		paramsJson: String,
		eventTimeMillis: Long = System.currentTimeMillis(),
	) {
		if (eventName.isBlank()) {
			Log.w(TAG, "insertLog: eventName is blank")
			return
		}
		if (eventName.length > MAX_EVENT_NAME_LENGTH) {
			Log.w(TAG, "insertLog: eventName is too long")
			return
		}
		if (
			paramsJson.length > MAX_PARAMS_SIZE_BYTES ||
			paramsJson.toByteArray(Charsets.UTF_8).size > MAX_PARAMS_SIZE_BYTES
		) {
			Log.w(TAG, "insertLog: params are too large, eventName=$eventName")
			return
		}
		if (databaseHelper == null) {
			Log.w(TAG, "insertLog: database is not initialized")
			return
		}

		val result = databaseCommands.trySend(
			DatabaseCommand.Insert(eventName, paramsJson, eventTimeMillis),
		)
		if (result.isFailure) {
			Log.w(TAG, "insertLog: database queue is full, eventName=$eventName")
		}
	}

	/** 按 id 倒序分页读取日志；排队失败、超时或数据库错误时返回空列表。 */
	suspend fun queryLogs(
		limit: Int = DEFAULT_QUERY_LIMIT,
		beforeId: Long = Long.MAX_VALUE,
	): List<LocalLog> {
		if (limit <= 0 || beforeId <= 0 || databaseHelper == null) return emptyList()

		val result = CompletableDeferred<List<LocalLog>>()
		return awaitCommand(
			command = DatabaseCommand.Query(limit.coerceAtMost(MAX_QUERY_LIMIT), beforeId, result),
			result = result,
			fallback = emptyList(),
			operationName = "queryLogs",
		)
	}

	/** 删除指定时间之前的日志；排队失败、超时或数据库错误时返回 0。 */
	suspend fun deleteLogsBefore(eventTimeMillis: Long): Int {
		if (databaseHelper == null) return 0

		val result = CompletableDeferred<Int>()
		return awaitCommand(
			command = DatabaseCommand.DeleteBefore(eventTimeMillis, result),
			result = result,
			fallback = 0,
			operationName = "deleteLogsBefore",
		)
	}

	/** 清空全部本地日志；排队失败、超时或数据库错误时返回 0。 */
	suspend fun clearLogs(): Int {
		if (databaseHelper == null) return 0

		val result = CompletableDeferred<Int>()
		return awaitCommand(
			command = DatabaseCommand.Clear(result),
			result = result,
			fallback = 0,
			operationName = "clearLogs",
		)
	}

	private suspend fun <T : Any> awaitCommand(
		command: DatabaseCommand,
		result: CompletableDeferred<T>,
		fallback: T,
		operationName: String,
	): T {
		if (databaseCommands.trySend(command).isFailure) {
			Log.w(TAG, "$operationName: database queue is full")
			result.cancel()
			return fallback
		}

		return try {
			val completedResult = withTimeoutOrNull(DATABASE_OPERATION_TIMEOUT_MILLIS) {
				result.await()
			}
			if (completedResult == null) {
				Log.w(TAG, "$operationName: database operation timed out")
				fallback
			} else {
				completedResult
			}
		} finally {
			if (!result.isCompleted) result.cancel()
		}
	}

	private suspend fun processDatabaseCommands(helper: LogDatabaseHelper) {
		try {
			cleanupLogs(helper.writableDatabase)
		} catch (exception: Exception) {
			Log.e(TAG, "init database error", exception)
		}

		for (command in databaseCommands) {
			when (command) {
				is DatabaseCommand.Insert -> handleInsert(helper, command)
				is DatabaseCommand.Query -> handleQuery(helper, command)
				is DatabaseCommand.DeleteBefore -> handleDeleteBefore(helper, command)
				is DatabaseCommand.Clear -> handleClear(helper, command)
			}
		}
	}

	private fun handleInsert(helper: LogDatabaseHelper, command: DatabaseCommand.Insert) {
		try {
			val database = helper.writableDatabase
			val values = ContentValues().apply {
				put(COLUMN_EVENT_NAME, command.eventName)
				put(COLUMN_PARAMS_JSON, command.paramsJson)
				put(COLUMN_EVENT_TIME_MS, command.eventTimeMillis)
			}
			database.transaction {
				val insertedId = insertOrThrow(TABLE_LOG, null, values)
				if (insertedId > MAX_LOG_COUNT) {
					delete(
						TABLE_LOG,
						"$COLUMN_ID <= ?",
						arrayOf((insertedId - MAX_LOG_COUNT).toString()),
					)
				}
			}

			successfulInsertsSinceCleanup++
			if (successfulInsertsSinceCleanup >= CLEANUP_INTERVAL) {
				cleanupLogs(database)
				successfulInsertsSinceCleanup = 0
			}
		} catch (exception: Exception) {
			Log.e(TAG, "insertLog error", exception)
		}
	}

	private fun handleQuery(helper: LogDatabaseHelper, command: DatabaseCommand.Query) {
		if (!command.result.isActive) return

		try {
			cleanupLogs(helper.writableDatabase)
			successfulInsertsSinceCleanup = 0
		} catch (exception: Exception) {
			Log.e(TAG, "cleanupLogs error", exception)
		}
		if (!command.result.isActive) return

		val logs = try {
			val result = mutableListOf<LocalLog>()
			helper.readableDatabase.query(
				TABLE_LOG,
				arrayOf(COLUMN_ID, COLUMN_EVENT_NAME, COLUMN_PARAMS_JSON, COLUMN_EVENT_TIME_MS),
				"$COLUMN_ID < ?",
				arrayOf(command.beforeId.toString()),
				null,
				null,
				"$COLUMN_ID DESC",
				command.limit.toString(),
			).use { cursor ->
				val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
				val eventNameIndex = cursor.getColumnIndexOrThrow(COLUMN_EVENT_NAME)
				val paramsJsonIndex = cursor.getColumnIndexOrThrow(COLUMN_PARAMS_JSON)
				val eventTimeIndex = cursor.getColumnIndexOrThrow(COLUMN_EVENT_TIME_MS)
				while (cursor.moveToNext()) {
					result += LocalLog(
						id = cursor.getLong(idIndex),
						eventName = cursor.getString(eventNameIndex),
						paramsJson = cursor.getString(paramsJsonIndex),
						eventTimeMillis = cursor.getLong(eventTimeIndex),
					)
				}
			}
			result
		} catch (exception: Exception) {
			Log.e(TAG, "queryLogs error", exception)
			emptyList()
		}
		command.result.complete(logs)
	}

	private fun handleDeleteBefore(
		helper: LogDatabaseHelper,
		command: DatabaseCommand.DeleteBefore,
	) {
		if (!command.result.isActive) return

		val deletedCount = try {
			helper.writableDatabase.delete(
				TABLE_LOG,
				"$COLUMN_EVENT_TIME_MS < ?",
				arrayOf(command.eventTimeMillis.toString()),
			)
		} catch (exception: Exception) {
			Log.e(TAG, "deleteLogsBefore error", exception)
			0
		}
		command.result.complete(deletedCount)
	}

	private fun handleClear(helper: LogDatabaseHelper, command: DatabaseCommand.Clear) {
		if (!command.result.isActive) return

		val deletedCount = try {
			val count = helper.writableDatabase.delete(TABLE_LOG, null, null)
			successfulInsertsSinceCleanup = 0
			count
		} catch (exception: Exception) {
			Log.e(TAG, "clearLogs error", exception)
			0
		}
		command.result.complete(deletedCount)
	}

	private fun cleanupLogs(database: SQLiteDatabase) {
		val expiredTime = System.currentTimeMillis() - RETENTION_MILLIS
		database.delete(
			TABLE_LOG,
			"$COLUMN_EVENT_TIME_MS < ?",
			arrayOf(expiredTime.toString()),
		)
	}

	private class LogDatabaseHelper(context: Context) : SQLiteOpenHelper(
		context,
		File(context.noBackupFilesDir, DATABASE_NAME).absolutePath,
		null,
		DATABASE_VERSION,
	) {

		override fun onCreate(database: SQLiteDatabase) {
			database.execSQL(
				"""
				CREATE TABLE $TABLE_LOG (
					$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
					$COLUMN_EVENT_NAME TEXT NOT NULL,
					$COLUMN_PARAMS_JSON TEXT NOT NULL,
					$COLUMN_EVENT_TIME_MS INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			database.execSQL(
				"CREATE INDEX index_${TABLE_LOG}_event_time ON $TABLE_LOG ($COLUMN_EVENT_TIME_MS)",
			)
		}

		override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
			// 增加数据库版本前，在这里按版本顺序补充迁移逻辑。
		}
	}
}
