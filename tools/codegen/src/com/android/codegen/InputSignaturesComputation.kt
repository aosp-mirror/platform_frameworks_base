package com.android.codegen

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type


fun ClassPrinter.getInputSignatures(): List<String> {
    return generateInputSignaturesForClass(classAst) +
            annotationToString(classAst.annotations.find { it.nameAsString == DataClass }) +
            generateInputSignaturesForClass(customBaseBuilderAst)
}

private fun ClassPrinter.generateInputSignaturesForClass(classAst: ClassOrInterfaceDeclaration?): List<String> {
    if (classAst == null) return emptyList()

    return classAst.fields.map { fieldAst ->
        buildString {
            append(fieldAst.modifiers.joinToString(" ") { it.keyword.asString() })
            append(" ")
            append(annotationsToString(fieldAst))
            append(" ")
            append(getFullClassName(fieldAst.commonType))
            append(" ")
            append(fieldAst.variables.joinToString(", ") { it.nameAsString })
        }
    } + classAst.methods.map { methodAst ->
        buildString {
            append(methodAst.modifiers.joinToString(" ") { it.keyword.asString() })
            append(" ")
            append(annotationsToString(methodAst))
            append(" ")
            append(getFullClassName(methodAst.type))
            append(" ")
            append(methodAst.nameAsString)
            append("(")
            append(methodAst.parameters.joinToString(",") { getFullClassName(it.type) })
            append(")")
        }
    } + ("class ${classAst.nameAsString}" +
            " extends ${classAst.extendedTypes.map { getFullClassName(it) }.ifEmpty { listOf("java.lang.Object") }.joinToString(", ")}" +
            " implements [${classAst.implementedTypes.joinToString(", ") { getFullClassName(it) }}]")
}

private fun ClassPrinter.annotationsToString(annotatedAst: NodeWithAnnotations<*>): String {
    return annotatedAst
            .annotations
            .groupBy { it.nameAsString } // dedupe annotations by name (javaparser bug?)
            .values
            .joinToString(" ") {
                annotationToString(it[0])
            }
}

private fun ClassPrinter.annotationToString(ann: AnnotationExpr?): String {
    if (ann == null) return ""
    return buildString {
        append("@")
        append(getFullClassName(ann.nameAsString))
        if (ann is MarkerAnnotationExpr) return@buildString

        append("(")

        when (ann) {
            is SingleMemberAnnotationExpr -> {
                appendExpr(this, ann.memberValue)
            }
            is NormalAnnotationExpr -> {
                ann.pairs.forEachLastAware { pair, isLast ->
                    append(pair.nameAsString)
                    append("=")
                    appendExpr(this, pair.value)
                    if (!isLast) append(", ")
                }
            }
        }

        append(")")
    }.replace("\"", "\\\"")
}

private fun ClassPrinter.appendExpr(sb: StringBuilder, ex: Expression?) {
    when (ex) {
        is ClassExpr -> sb.append(getFullClassName(ex.typeAsString)).append(".class")
        is IntegerLiteralExpr -> sb.append(ex.asInt()).append("L")
        is LongLiteralExpr -> sb.append(ex.asLong()).append("L")
        is DoubleLiteralExpr -> sb.append(ex.asDouble())
        is ArrayInitializerExpr -> {
            sb.append("{")
            ex.values.forEachLastAware { arrayElem, isLast ->
                appendExpr(sb, arrayElem)
                if (!isLast) sb.append(", ")
            }
            sb.append("}")
        }
        else -> sb.append(ex)
    }
}

private fun ClassPrinter.getFullClassName(type: Type): String {
    return if (type is ClassOrInterfaceType) {

        getFullClassName(buildString {
            type.scope.ifPresent { append(it).append(".") }
            append(type.nameAsString)
        }) + (type.typeArguments.orElse(null)?.let { args -> args.joinToString(",") {getFullClassName(it)}}?.let { "<$it>" } ?: "")
    } else getFullClassName(type.asString())
}

private fun ClassPrinter.getFullClassName(className: String): String {
    if (className.endsWith("[]")) return getFullClassName(className.removeSuffix("[]")) + "[]"

    if (className.matches("\\.[a-z]".toRegex())) return className //qualified name

    if ("." in className) return getFullClassName(className.substringBeforeLast(".")) + "." + className.substringAfterLast(".")

    fileAst.imports.find { imp ->
        imp.nameAsString.endsWith(".$className")
    }?.nameAsString?.let { return it }

    val thisPackagePrefix = fileAst.packageDeclaration.map { it.nameAsString + "." }.orElse("")
    val thisClassPrefix = thisPackagePrefix + classAst.nameAsString + "."

    if (classAst.nameAsString == className) return thisPackagePrefix + classAst.nameAsString

    nestedClasses.find {
        it.nameAsString == className
    }?.let { return thisClassPrefix + it.nameAsString }

    if (className == CANONICAL_BUILDER_CLASS || className == BASE_BUILDER_CLASS) {
        return thisClassPrefix + className
    }

    constDefs.find { it.AnnotationName == className }?.let { return thisClassPrefix + className }

    if (tryOrNull { Class.forName("java.lang.$className") } != null) {
        return "java.lang.$className"
    }

    if (className[0].isLowerCase()) return className //primitive

    return thisPackagePrefix + className
}

private inline fun <T> tryOrNull(f: () -> T?) = try {
    f()
} catch (e: Exception) {
    null
}
