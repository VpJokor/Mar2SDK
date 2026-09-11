#!/usr/bin/env kotlin

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 根据 app 和 impl 模块中内容页已有代码，生成 impl 模块中对应的 AreaKeys.kt 文件。
 * app 模块为业务代码，页面随时会增加和减少。
 *
 * 内容页以 contentComposable(route) 为准。每个静态 route 会生成 start、back、leave
 * 三个广告点位；应用从后台回到前台使用的 app_foreground_open 点位也会固定生成。
 * 直接字符串和字符串 const val 均可作为 route。
 *
 * 用法：
 *   kotlin debug/scripts/codegen/genAreaKey.kts [项目根目录] [--dry-run|--check]
 *
 * 在项目根目录的 PowerShell 终端中执行（kotlin 命令需在 PATH 中）：
 *
 * 生成或更新 AreaKeys.kt：
 *   kotlin .\debug\scripts\codegen\genAreaKey.kts
 *
 * 只检查生成结果是否为最新，不写入文件：
 *   kotlin .\debug\scripts\codegen\genAreaKey.kts --check
 *
 * 预览生成内容，不写入文件：
 *   kotlin .\debug\scripts\codegen\genAreaKey.kts --dry-run
 */

private val AREA_KEY_SUFFIXES = listOf("start", "back", "leave")
private val APP_FOREGROUND_OPEN_AREA_KEY = "app_foreground_open"
private val CONTENT_COMPOSABLE = Regex("""\bcontentComposable\s*\(""")
private val STRING_CONST = Regex(
	"""\bconst\s+val\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*:\s*(?:kotlin\s*\.\s*)?String)?\s*="""
)
private val PACKAGE_DECLARATION = Regex(
	"""\bpackage\s+([A-Za-z_][A-Za-z0-9_]*(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*)"""
)
private val TYPE_DECLARATION = Regex(
	"""\b(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)"""
)
private val AREA_KEY_REFERENCE = Regex("""\bAreaKeys\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)""")
private val CONST_REFERENCE = Regex(
	"""[A-Za-z_][A-Za-z0-9_]*(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*"""
)

private data class Options(
	val projectRoot: Path?,
	val dryRun: Boolean,
	val check: Boolean
)

private data class SourceFile(
	val path: Path,
	val text: String,
	val codeMask: String
)

private data class StringConstant(
	val name: String,
	val value: String,
	val qualifiedName: String,
	val source: SourceFile,
	val offset: Int
)

private data class ParsedString(val value: String, val endExclusive: Int)

private class GenerationException(message: String) : RuntimeException(message)

private fun fail(message: String): Nothing = throw GenerationException(message)

private fun parseOptions(arguments: Array<String>): Options {
	var projectRoot: Path? = null
	var dryRun = false
	var check = false

	arguments.forEach { argument ->
		when (argument) {
			"--dry-run" -> dryRun = true
			"--check" -> check = true
			else -> {
				if (argument.startsWith("--")) fail("未知参数：$argument")
				if (projectRoot != null) fail("只能指定一个项目根目录")
				projectRoot = Paths.get(argument).toAbsolutePath().normalize()
			}
		}
	}

	if (dryRun && check) fail("--dry-run 和 --check 不能同时使用")
	return Options(projectRoot, dryRun, check)
}

private fun isProjectRoot(path: Path): Boolean =
	Files.isDirectory(path.resolve("app/src/main")) &&
		Files.isDirectory(path.resolve("impl/src/main"))

private fun findProjectRoot(start: Path): Path? {
	var current: Path? = if (Files.isDirectory(start)) start else start.parent
	while (current != null) {
		if (isProjectRoot(current)) return current.toAbsolutePath().normalize()
		current = current.parent
	}
	return null
}

private fun processScriptPath(): Path? {
	val arguments = ProcessHandle.current().info().arguments().orElse(emptyArray())
	arguments.asSequence()
		.filter { it.endsWith("genAreaKey.kts") }
		.map { Paths.get(it).toAbsolutePath().normalize() }
		.firstOrNull { Files.isRegularFile(it) }
		?.let { return it }

	val command = System.getProperty("sun.java.command").orEmpty()
	val scriptName = "genAreaKey.kts"
	val scriptNameStart = command.indexOf(scriptName)
	if (scriptNameStart < 0) return null
	val scriptNameEnd = scriptNameStart + scriptName.length
	for (start in scriptNameStart downTo 0) {
		val candidate = command.substring(start, scriptNameEnd).trim().trim('\"', '\'')
		val path = runCatching { Paths.get(candidate).toAbsolutePath().normalize() }.getOrNull()
		if (path != null && Files.isRegularFile(path)) return path
	}
	return null
}

private fun resolveProjectRoot(explicitRoot: Path?): Path {
	if (explicitRoot != null) {
		if (!isProjectRoot(explicitRoot)) {
			fail("不是有效的项目根目录：$explicitRoot")
		}
		return explicitRoot
	}

	val workingDirectory = Paths.get("").toAbsolutePath().normalize()
	return processScriptPath()?.let(::findProjectRoot)
		?: findProjectRoot(workingDirectory)
		?: fail("无法定位项目根目录；请将项目根目录作为第一个参数传入")
}

private fun readSources(projectRoot: Path, outputFile: Path): List<SourceFile> {
	val sourceRoots = listOf(
		projectRoot.resolve("app/src/main"),
		projectRoot.resolve("impl/src/main")
	)
	val paths = mutableListOf<Path>()

	sourceRoots.forEach { sourceRoot ->
		Files.walk(sourceRoot).use { stream ->
			stream.filter { path ->
				Files.isRegularFile(path) &&
					(path.fileName.toString().endsWith(".kt") ||
						path.fileName.toString().endsWith(".kts") ||
						path.fileName.toString().endsWith(".java")) &&
					path.toAbsolutePath().normalize() != outputFile
			}.forEach(paths::add)
		}
	}

	return paths.sortedBy { projectRoot.relativize(it).toString().replace('\\', '/') }
		.map { path ->
			val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
			SourceFile(path, text, maskNonCode(text))
		}
}

/** 保留源码位置，只把注释、字符串和字符字面量替换为空格。 */
private fun maskNonCode(source: String): String {
	val result = source.toCharArray()
	var index = 0

	fun mask(position: Int) {
		if (result[position] != '\n' && result[position] != '\r') result[position] = ' '
	}

	while (index < source.length) {
		when {
			source.startsWith("//", index) -> {
				while (index < source.length && source[index] != '\n') {
					mask(index++)
				}
			}
			source.startsWith("/*", index) -> {
				var depth = 0
				while (index < source.length) {
					when {
						source.startsWith("/*", index) -> {
							mask(index++)
							mask(index++)
							depth++
						}
						source.startsWith("*/", index) -> {
							mask(index++)
							mask(index++)
							depth--
							if (depth == 0) break
						}
						else -> mask(index++)
					}
				}
			}
			source.startsWith("\"\"\"", index) -> {
				repeat(3) { mask(index++) }
				while (index < source.length && !source.startsWith("\"\"\"", index)) {
					mask(index++)
				}
				if (index < source.length) repeat(3) { mask(index++) }
			}
			source[index] == '\"' || source[index] == '\'' -> {
				val delimiter = source[index]
				mask(index++)
				var escaped = false
				while (index < source.length) {
					val character = source[index]
					mask(index++)
					if (escaped) {
						escaped = false
					} else if (character == '\\') {
						escaped = true
					} else if (character == delimiter) {
						break
					}
				}
			}
			else -> index++
		}
	}

	return result.concatToString()
}

private fun parseStringAt(text: String, start: Int): ParsedString? {
	var index = start
	while (index < text.length && text[index].isWhitespace()) index++

	if (text.startsWith("\"\"\"", index)) {
		val end = text.indexOf("\"\"\"", index + 3)
		if (end < 0) return null
		val value = text.substring(index + 3, end)
		if ('$' in value) return null
		return ParsedString(value, end + 3)
	}
	if (index >= text.length || text[index] != '\"') return null

	val value = StringBuilder()
	index++
	while (index < text.length) {
		when (val character = text[index++]) {
			'\"' -> return ParsedString(value.toString(), index)
			'$' -> return null
			'\\' -> {
				if (index >= text.length) return null
				when (val escaped = text[index++]) {
					't' -> value.append('\t')
					'b' -> value.append('\b')
					'n' -> value.append('\n')
					'r' -> value.append('\r')
					'\'' -> value.append('\'')
					'\"' -> value.append('\"')
					'\\' -> value.append('\\')
					'$' -> value.append('$')
					'u' -> {
						if (index + 4 > text.length) return null
						val digits = text.substring(index, index + 4)
						val codePoint = digits.toIntOrNull(16) ?: return null
						value.append(codePoint.toChar())
						index += 4
					}
					else -> return null
				}
			}
			else -> value.append(character)
		}
	}
	return null
}

private fun parseExactString(expression: String): String? {
	val parsed = parseStringAt(expression, 0) ?: return null
	val remainder = expression.substring(parsed.endExclusive).trim().removeSuffix(";").trim()
	return parsed.value.takeIf { remainder.isEmpty() }
}

private fun collectStringConstants(sources: List<SourceFile>): Map<String, List<StringConstant>> {
	val constants = mutableMapOf<String, MutableList<StringConstant>>()
	sources.forEach { source ->
		STRING_CONST.findAll(source.codeMask).forEach { match ->
			val parsed = parseStringAt(source.text, match.range.last + 1) ?: return@forEach
			val lineEnd = source.codeMask.indexOf('\n', parsed.endExclusive)
				.let { if (it < 0) source.codeMask.length else it }
			val remainder = source.codeMask.substring(parsed.endExclusive, lineEnd).trimStart()
			if (remainder.isNotEmpty() && !remainder.startsWith(';')) return@forEach

			val name = match.groupValues[1]
			val packageName = PACKAGE_DECLARATION.find(source.codeMask)
				?.groupValues?.get(1)?.replace(Regex("\\s+"), "")
			val owners = TYPE_DECLARATION.findAll(source.codeMask.substring(0, match.range.first))
				.mapNotNull { declaration ->
					val openingBrace = source.codeMask.indexOf('{', declaration.range.last + 1)
					if (openingBrace < 0 || openingBrace >= match.range.first) return@mapNotNull null
					val closingBrace = findClosingBrace(source.codeMask, openingBrace)
					if (closingBrace == null || closingBrace > match.range.first) {
						declaration.groupValues[1] to openingBrace
					} else {
						null
					}
				}
				.sortedBy { it.second }
				.map { it.first }
			val qualifiedName = (listOfNotNull(packageName) + owners + name).joinToString(".")
			constants.getOrPut(name) { mutableListOf() }
				.add(StringConstant(name, parsed.value, qualifiedName, source, match.range.first))
		}
	}
	return constants
}

private fun findClosingBrace(mask: String, openingIndex: Int): Int? {
	var depth = 0
	for (index in openingIndex until mask.length) {
		when (mask[index]) {
			'{' -> depth++
			'}' -> {
				depth--
				if (depth == 0) return index
			}
		}
	}
	return null
}

private fun findClosingParenthesis(mask: String, openingIndex: Int): Int? {
	var depth = 0
	for (index in openingIndex until mask.length) {
		when (mask[index]) {
			'(' -> depth++
			')' -> {
				depth--
				if (depth == 0) return index
			}
		}
	}
	return null
}

private fun splitTopLevelArguments(arguments: String): List<String> {
	val mask = maskNonCode(arguments)
	val result = mutableListOf<String>()
	var start = 0
	var parentheses = 0
	var brackets = 0
	var braces = 0

	mask.forEachIndexed { index, character ->
		when (character) {
			'(' -> parentheses++
			')' -> parentheses--
			'[' -> brackets++
			']' -> brackets--
			'{' -> braces++
			'}' -> braces--
			',' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
				result += arguments.substring(start, index)
				start = index + 1
			}
		}
	}
	result += arguments.substring(start)
	return result.filter { it.isNotBlank() }
}

private fun namedRouteExpression(argument: String): String? {
	val mask = maskNonCode(argument)
	var parentheses = 0
	var brackets = 0
	var braces = 0

	mask.forEachIndexed { index, character ->
		when (character) {
			'(' -> parentheses++
			')' -> parentheses--
			'[' -> brackets++
			']' -> brackets--
			'{' -> braces++
			'}' -> braces--
			'=' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
				if (argument.substring(0, index).trim() == "route") {
					return argument.substring(index + 1).trim()
				}
				return null
			}
		}
	}
	return null
}

private fun isContentComposableDeclaration(source: SourceFile, offset: Int): Boolean {
	val prefixStart = listOf('{', '}', ';', '=')
		.maxOf { delimiter -> source.codeMask.lastIndexOf(delimiter, offset - 1) }
		.let { it + 1 }
	return Regex("""\bfun\b""").containsMatchIn(source.codeMask.substring(prefixStart, offset))
}

private fun sourceLocation(projectRoot: Path, source: SourceFile, offset: Int): String {
	val line = source.text.take(offset).count { it == '\n' } + 1
	val relativePath = projectRoot.relativize(source.path).toString().replace('\\', '/')
	return "$relativePath:$line"
}

private fun resolveRoute(
	projectRoot: Path,
	source: SourceFile,
	expression: String,
	constants: Map<String, List<StringConstant>>,
	location: String
): String {
	parseExactString(expression)?.let { return it }

	val reference = expression.trim().removeSurrounding("(", ")").trim()
	if (!CONST_REFERENCE.matches(reference)) {
		fail("$location 的 contentComposable route 不是可静态解析的字符串：$expression")
	}

	val compactReference = reference.replace(Regex("\\s*\\.\\s*"), ".")
	val name = compactReference.substringAfterLast('.')
	val namedDefinitions = constants[name].orEmpty()
	val definitions = if ('.' in compactReference) {
		namedDefinitions.filter { definition ->
			definition.qualifiedName == compactReference ||
				definition.qualifiedName.endsWith(".$compactReference")
		}
	} else {
		namedDefinitions.filter { it.source.path == source.path }.ifEmpty { namedDefinitions }
	}
	if (definitions.isEmpty()) {
		fail("$location 无法解析 route 常量：$compactReference")
	}
	val values = definitions.map { it.value }.distinct()
	if (values.size != 1) {
		val definitionLocations = definitions.joinToString { definition ->
			sourceLocation(projectRoot, definition.source, definition.offset)
		}
		fail("$location 的 route 常量 $compactReference 存在多个不同定义：$definitionLocations")
	}
	return values.single()
}

private fun collectRoutes(
	projectRoot: Path,
	sources: List<SourceFile>,
	constants: Map<String, List<StringConstant>>
): List<String> {
	val routes = linkedSetOf<String>()

	sources.forEach { source ->
		CONTENT_COMPOSABLE.findAll(source.codeMask).forEach { match ->
			if (isContentComposableDeclaration(source, match.range.first)) return@forEach

			val openingIndex = source.codeMask.indexOf('(', match.range.first)
			val closingIndex = findClosingParenthesis(source.codeMask, openingIndex)
				?: fail("${sourceLocation(projectRoot, source, match.range.first)} 的 contentComposable 调用缺少右括号")
			val arguments = splitTopLevelArguments(
				source.text.substring(openingIndex + 1, closingIndex)
			)
			if (arguments.isEmpty()) {
				fail("${sourceLocation(projectRoot, source, match.range.first)} 的 contentComposable 调用缺少 route")
			}
			val routeExpression = arguments.asSequence()
				.mapNotNull(::namedRouteExpression)
				.firstOrNull()
				?: arguments.first()
			val location = sourceLocation(projectRoot, source, match.range.first)
			val route = resolveRoute(projectRoot, source, routeExpression, constants, location)
			if (route.isBlank()) fail("$location 的 contentComposable route 不能为空")
			routes += route
		}
	}

	return routes.sorted()
}

private fun constantName(areaKey: String): String {
	val normalized = areaKey.uppercase(Locale.ROOT)
		.replace(Regex("[^A-Z0-9]+"), "_")
		.trim('_')
	if (normalized.isEmpty()) fail("无法为广告点位生成 Kotlin 常量名：$areaKey")
	return "KEY_$normalized"
}

private fun escapeKotlinString(value: String): String = buildString {
	value.forEach { character ->
		when (character) {
			'\\' -> append("\\\\")
			'\"' -> append("\\\"")
			'$' -> append("\\$")
			'\t' -> append("\\t")
			'\b' -> append("\\b")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			else -> if (character.code < 0x20) {
				append("\\u")
				append(character.code.toString(16).padStart(4, '0'))
			} else {
				append(character)
			}
		}
	}
}

private fun collectReferencedAreaKeys(sources: List<SourceFile>): Set<String> = buildSet {
	sources.forEach { source ->
		AREA_KEY_REFERENCE.findAll(source.codeMask).forEach { match ->
			add(match.groupValues[1])
		}
	}
}

private fun existingAreaKeys(outputFile: Path): Map<String, String> {
	if (!Files.isRegularFile(outputFile)) return emptyMap()
	val text = String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
	val source = SourceFile(outputFile, text, maskNonCode(text))
	return collectStringConstants(listOf(source)).mapValues { (name, definitions) ->
		val values = definitions.map { it.value }.distinct()
		if (values.size != 1) fail("AreaKeys.kt 中的常量 $name 存在多个不同定义")
		values.single()
	}
}

private fun generatedAreaKeys(routes: List<String>): LinkedHashMap<String, String> {
	val result = linkedMapOf<String, String>()
	result[constantName(APP_FOREGROUND_OPEN_AREA_KEY)] = APP_FOREGROUND_OPEN_AREA_KEY
	routes.forEach { route ->
		AREA_KEY_SUFFIXES.forEach { suffix ->
			val areaKey = "${route}_$suffix"
			val name = constantName(areaKey)
			val previous = result.put(name, areaKey)
			if (previous != null && previous != areaKey) {
				fail("广告点位 $previous 和 $areaKey 会生成相同的常量名 $name")
			}
		}
	}
	return result
}

private fun renderAreaKeys(
	manualKeys: Map<String, String>,
	generatedKeys: Map<String, String>
): String = buildString {
	append("package com.mar2sdk.impl\n\n")
	append("/**\n")
	append(" * 广告点位表\n")
	append(" */\n")
	append("object AreaKeys {\n")

	val groups = listOf(manualKeys.entries.sortedBy { it.key }, generatedKeys.entries.toList())
	groups.forEachIndexed { index, entries ->
		if (entries.isEmpty()) return@forEachIndexed
		if (index > 0 && groups.take(index).any { it.isNotEmpty() }) append('\n')
		entries.forEach { (name, value) ->
			append("\tconst val ")
			append(name)
			append(" = \"")
			append(escapeKotlinString(value))
			append("\"\n")
		}
	}

	append("}\n")
}

private fun writeAtomically(outputFile: Path, content: String) {
	Files.createDirectories(outputFile.parent)
	val temporaryFile = Files.createTempFile(outputFile.parent, ".AreaKeys.", ".tmp")
	try {
		Files.write(temporaryFile, content.toByteArray(StandardCharsets.UTF_8))
		try {
			Files.move(
				temporaryFile,
				outputFile,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING
			)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(temporaryFile, outputFile, StandardCopyOption.REPLACE_EXISTING)
		}
	} finally {
		Files.deleteIfExists(temporaryFile)
	}
}

private fun generate(arguments: Array<String>) {
	val options = parseOptions(arguments)
	val projectRoot = resolveProjectRoot(options.projectRoot)
	val outputFile = projectRoot.resolve(
		"impl/src/main/java/com/mar2sdk/impl/AreaKeys.kt"
	).toAbsolutePath().normalize()
	val sources = readSources(projectRoot, outputFile)
	val constants = collectStringConstants(sources)
	val routes = collectRoutes(projectRoot, sources, constants)
	val generatedKeys = generatedAreaKeys(routes)
	val oldKeys = existingAreaKeys(outputFile)
	val referencedKeys = collectReferencedAreaKeys(sources)
	val manualKeys = linkedMapOf<String, String>()

	referencedKeys.sorted().forEach { name ->
		val generatedValue = generatedKeys[name]
		val oldValue = oldKeys[name]
		if (generatedValue != null) {
			if (oldValue != null && oldValue != generatedValue) {
				fail("AreaKeys.$name 的现有值 $oldValue 与生成广告点位 $generatedValue 冲突")
			}
		} else {
			manualKeys[name] = oldValue
				?: fail("源码引用了 AreaKeys.$name，但 AreaKeys.kt 中没有对应的字符串常量")
		}
	}

	val content = renderAreaKeys(manualKeys, generatedKeys)
	val oldContent = if (Files.isRegularFile(outputFile)) {
		String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
	} else {
		null
	}

	when {
		options.dryRun -> print(content)
		options.check && oldContent != content -> fail("AreaKeys.kt 不是最新生成结果")
		options.check -> println("AreaKeys.kt 已是最新")
		oldContent == content -> println("AreaKeys.kt 无需更新")
		else -> {
			writeAtomically(outputFile, content)
			println("已生成 ${projectRoot.relativize(outputFile).toString().replace('\\', '/')}")
		}
	}
}

try {
	generate(args)
} catch (exception: GenerationException) {
	System.err.println("genAreaKey: ${exception.message}")
	exitProcess(1)
}
