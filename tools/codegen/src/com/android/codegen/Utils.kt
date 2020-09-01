package com.android.codegen

import com.github.javaparser.JavaParser
import com.github.javaparser.ParseProblemException
import com.github.javaparser.ParseResult
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * [Iterable.forEach] + [Any.apply]
 */
inline fun <T> Iterable<T>.forEachApply(block: T.() -> Unit) = forEach(block)

inline fun String.mapLines(f: String.() -> String?) = lines().mapNotNull(f).joinToString("\n")
inline fun <T> Iterable<T>.trim(f: T.() -> Boolean) = dropWhile(f).dropLastWhile(f)
fun String.trimBlankLines() = lines().trim { isBlank() }.joinToString("\n")

fun Char.isNewline() = this == '\n' || this == '\r'
fun Char.isWhitespaceNonNewline() = isWhitespace() && !isNewline()

fun if_(cond: Boolean, then: String) = if (cond) then else ""

fun <T> Any?.as_(): T = this as T

inline infix fun Int.times(action: () -> Unit) {
    for (i in 1..this) action()
}

/**
 * a bbb
 * cccc dd
 *
 * ->
 *
 * a    bbb
 * cccc dd
 */
fun Iterable<Pair<String, String>>.columnize(separator: String = " | "): String {
    val col1w = map { (a, _) -> a.length }.max()!!
    val col2w = map { (_, b) -> b.length }.max()!!
    return map { it.first.padEnd(col1w) + separator + it.second.padEnd(col2w) }.joinToString("\n")
}

fun String.hasUnbalancedCurlyBrace(): Boolean {
    var braces = 0
    forEach {
        if (it == '{') braces++
        if (it == '}') braces--
        if (braces < 0) return true
    }
    return false
}

fun String.toLowerCamel(): String {
    if (length >= 2 && this[0] == 'm' && this[1].isUpperCase()) return substring(1).capitalize()
    if (all { it.isLetterOrDigit() }) return decapitalize()
    return split("[^a-zA-Z0-9]".toRegex())
            .map { it.toLowerCase().capitalize() }
            .joinToString("")
            .decapitalize()
}

inline fun <T> List<T>.forEachLastAware(f: (T, Boolean) -> Unit) {
    forEachIndexed { index, t -> f(t, index == size - 1) }
}

@Suppress("UNCHECKED_CAST")
fun <T : Expression> AnnotationExpr.singleArgAs()
        = ((this as SingleMemberAnnotationExpr).memberValue as T)

inline operator fun <reified T> Array<T>.minus(item: T) = toList().minus(item).toTypedArray()

fun currentTimestamp() = DateTimeFormatter
        .ofLocalizedDateTime(/* date */ FormatStyle.MEDIUM, /* time */ FormatStyle.LONG)
        .withZone(ZoneId.systemDefault())
        .format(Instant.now())

val NodeWithModifiers<*>.visibility get() = accessSpecifier

fun abort(msg: String): Nothing {
    System.err.println("ERROR: $msg")
    System.exit(1)
    throw InternalError() // can't get here
}

fun bitAtExpr(bitIndex: Int) = "0x${java.lang.Long.toHexString(1L shl bitIndex)}"

val AnnotationExpr.args: Map<String, Expression> get() = when (this) {
    is MarkerAnnotationExpr -> emptyMap()
    is SingleMemberAnnotationExpr -> mapOf("value" to memberValue)
    is NormalAnnotationExpr -> pairs.map { it.name.asString() to it.value }.toMap()
    else -> throw IllegalArgumentException("Unknown annotation expression: $this")
}

val TypeDeclaration<*>.nestedTypes get() = childNodes.filterIsInstance<TypeDeclaration<*>>()
val TypeDeclaration<*>.nestedDataClasses get()
        = nestedTypes.filterIsInstance<ClassOrInterfaceDeclaration>()
            .filter { it.annotations.any { it.nameAsString.endsWith("DataClass") } }
val TypeDeclaration<*>.startLine get() = range.get()!!.begin.line

inline fun <T> List<T>.forEachSequentialPair(action: (T, T?) -> Unit) {
    forEachIndexed { index, t ->
        action(t, getOrNull(index + 1))
    }
}

fun <T: Node> parseJava(fn: JavaParser.(String) -> ParseResult<T>, source: String): T = try {
    val parse = JAVA_PARSER.fn(source)
    if (parse.problems.isNotEmpty()) {
        throw parseFailed(
                source,
                desc = parse.problems.joinToString("\n"),
                cause = parse.problems.mapNotNull { it.cause.orElse(null) }.firstOrNull())
    }
    parse.result.get()
} catch (e: ParseProblemException) {
    throw parseFailed(source, cause = e)
}

private fun parseFailed(source: String, cause: Throwable? = null, desc: String = ""): RuntimeException {
    return RuntimeException("Failed to parse code:\n" +
            source
                    .lines()
                    .mapIndexed { lnNum, ln -> "/*$lnNum*/$ln" }
                    .joinToString("\n") + "\n$desc",
            cause)
}

var <T> MutableList<T>.last
    get() = last()
    set(value) {
        if (isEmpty()) {
            add(value)
        } else {
            this[size - 1] = value
        }
    }

inline fun <T> buildList(init: MutableList<T>.() -> Unit) = mutableListOf<T>().apply(init)