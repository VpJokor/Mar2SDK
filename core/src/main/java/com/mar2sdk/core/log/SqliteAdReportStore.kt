package com.mar2sdk.core.log

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.io.File

/** 按采集时的产品和账号隔离存储持久化上报事件。 */
internal class SqliteAdReportStore(
	context: Context,
	databaseFile: File = File(context.noBackupFilesDir, "mar2sdk_reports.db"),
) : AdReportStorage, Closeable {
	private val helper = ReportDatabaseHelper(context.applicationContext, databaseFile)

	override fun insert(event: PendingAdReport) {
		require(event.id.isNotBlank()) { "Report event ID is required" }
		require(event.identity.appID > 0 && event.identity.uid > 0) { "Report identity is required" }
		require(event.data.toByteArray(Charsets.UTF_8).size <= AD_REPORT_MAX_EVENT_BYTES) {
			"Report event exceeds $AD_REPORT_MAX_EVENT_BYTES bytes"
		}
		val values = ContentValues().apply {
			put("uuid", event.id)
			put("app_id", event.identity.appID)
			put("uid", event.identity.uid)
			put("data", event.data)
		}
		// 同一事件重复入队时，保留原始数据和入队顺序。
		helper.writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
	}

	override fun peek(identity: AdReportIdentity, limit: Int, maxBytes: Int): List<PendingAdReport> {
		if (limit <= 0 || maxBytes < 2) return emptyList()
		val events = mutableListOf<PendingAdReport>()
		var batchBytes = 2L // JSON 数组的左右括号各占一个字节。
		helper.readableDatabase.query(
			TABLE,
			arrayOf("uuid", "data"),
			"app_id = ? AND uid = ?",
			arrayOf(identity.appID.toString(), identity.uid.toString()),
			null,
			null,
			"sequence ASC",
			limit.toString(),
		).use { cursor ->
			while (cursor.moveToNext()) {
				val data = cursor.getString(1)
				val eventBytes = data.toByteArray(Charsets.UTF_8).size.toLong()
				val separatorBytes = if (events.isEmpty()) 0 else 1
				if (batchBytes + separatorBytes + eventBytes > maxBytes) break
				events += PendingAdReport(cursor.getString(0), identity, data)
				batchBytes += separatorBytes + eventBytes
			}
		}
		return events
	}

	override fun remove(ids: List<String>) {
		if (ids.isEmpty()) return
		val database = helper.writableDatabase
		database.beginTransaction()
		try {
			database.compileStatement("DELETE FROM $TABLE WHERE uuid = ?").use { statement ->
				for (id in ids) {
					statement.bindString(1, id)
					statement.executeUpdateDelete()
				}
			}
			database.setTransactionSuccessful()
		} finally {
			database.endTransaction()
		}
	}

	override fun close() = helper.close()

	private class ReportDatabaseHelper(context: Context, databaseFile: File) : SQLiteOpenHelper(
		context,
		databaseFile.absolutePath,
		null,
		1,
	) {
		override fun onCreate(database: SQLiteDatabase) {
			database.execSQL(
				"""
				CREATE TABLE $TABLE (
					sequence INTEGER PRIMARY KEY AUTOINCREMENT,
					uuid TEXT NOT NULL UNIQUE,
					app_id INTEGER NOT NULL,
					uid INTEGER NOT NULL,
					data TEXT NOT NULL
				)
				""".trimIndent(),
			)
			database.execSQL("CREATE INDEX report_identity_sequence ON $TABLE (app_id, uid, sequence)")
		}

		override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
			error("Missing report database migration from $oldVersion to $newVersion")
		}
	}

	private companion object {
		const val TABLE = "pending_ad_reports"
	}
}
