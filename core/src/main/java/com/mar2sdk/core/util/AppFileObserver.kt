package com.mar2sdk.core.util

import android.os.FileObserver
import com.mar2sdk.core.AppObs
import java.io.File
import kotlin.collections.forEach

internal class AppFileObserver(
	roots: Collection<File>,
	private val onChanged: (File, AppObs.FileChange) -> Unit,
) {
	private val roots = roots.filter(File::exists)
	private val observers = mutableMapOf<String, FileObserver>()
	private var watching = false

	@Synchronized
	fun startWatching() {
		watching = true
		roots.forEach(::watchTree)
	}

	@Synchronized
	fun stopWatching() {
		watching = false
		observers.values.forEach(FileObserver::stopWatching)
		observers.clear()
	}

	private fun watchTree(root: File) {
		root.walkTopDown().filter(File::isDirectory).forEach(::watchDirectory)
	}

	@Synchronized
	private fun watchDirectory(directory: File) {
		if (!watching || observers.containsKey(directory.absolutePath)) return
		val observer = object : FileObserver(directory, FILE_EVENTS) {
			override fun onEvent(event: Int, path: String?) {
				val baseEvent = event and FileObserver.ALL_EVENTS
				if (baseEvent == FileObserver.DELETE_SELF || baseEvent == FileObserver.MOVE_SELF) {
					stopTree(directory)
					return
				}
				path ?: return
				val file = File(directory, path)
				if (event and IS_DIRECTORY != 0) {
					when (baseEvent) {
						FileObserver.CREATE, FileObserver.MOVED_TO -> watchTree(file)
						FileObserver.DELETE, FileObserver.MOVED_FROM -> stopTree(file)
					}
				}
				val change = when (baseEvent) {
					FileObserver.CREATE -> AppObs.FileChange.CREATED
					FileObserver.MODIFY, FileObserver.CLOSE_WRITE -> AppObs.FileChange.MODIFIED
					FileObserver.DELETE -> AppObs.FileChange.DELETED
					FileObserver.MOVED_FROM -> AppObs.FileChange.MOVED_FROM
					FileObserver.MOVED_TO -> AppObs.FileChange.MOVED_TO
					else -> return
				}
				onChanged(file, change)
			}
		}
		observers[directory.absolutePath] = observer
		observer.startWatching()
	}

	@Synchronized
	private fun stopTree(directory: File) {
		val prefix = directory.absolutePath + File.separator
		observers.keys
			.filter { it == directory.absolutePath || it.startsWith(prefix) }
			.forEach { path -> observers.remove(path)?.stopWatching() }
	}

	private companion object {
		const val IS_DIRECTORY = 0x40000000
		const val FILE_EVENTS =
			FileObserver.CREATE or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.DELETE or
				FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
	}
}