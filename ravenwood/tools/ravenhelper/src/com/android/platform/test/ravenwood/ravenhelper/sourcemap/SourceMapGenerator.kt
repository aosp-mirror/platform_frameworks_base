/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.platform.test.ravenwood.ravenhelper.sourcemap

/*
 * This file contains classes used to parse Java source files to build "source map" which
 * basically tells you what classes/methods/fields are declared in what line of what file.
 */

import com.android.hoststubgen.GeneralUserErrorException
import com.android.hoststubgen.log
import com.android.tools.lint.UastEnvironment
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SyntheticElement
import java.io.File


/**
 * Represents the location of an item. (class, field or method)
 */
data class Location (
    /** Full path filename. */
    val file: String,

    /** 1-based line number */
    val line: Int,

    /** Indent of the line */
    val indent: Int,
) {

    fun getIndent(): String {
        return " ".repeat(indent)
    }

    fun dump() {
        log.i("Location: $file:$line (indent: $indent)")
    }
}

/**
 * Represents the type of item.
 */
enum class ItemType {
    Class,
    Field,
    Method,
}

/** Holds a field's location. */
data class FieldInfo (
    val name: String,
    val location: Location,
) {
    fun dump() {
        log.i("Field: $name")
        log.withIndent {
            location.dump()
        }
    }
}

/** Holds a method's location. */
data class MethodInfo (
    val name: String,
    /** "Simplified" description. */
    val simpleDesc: String,
    val location: Location,
) {
    fun dump() {
        log.i("Method: $name$simpleDesc")
        log.withIndent {
            location.dump()
        }
    }
}

/** Holds a class's location and members. */
data class ClassInfo (
    val fullName: String,
    val location: Location,
    val fields: MutableMap<String, FieldInfo> = mutableMapOf(),
    val methods: MutableMap<String, MutableList<MethodInfo>> = mutableMapOf(),
) {
    fun add(fi: FieldInfo) {
        fields.put(fi.name, fi)
    }

    fun add(mi: MethodInfo) {
        val list = methods.get(mi.name)
        if (list != null) {
            list.add(mi)
        } else {
            methods.put(mi.name, mutableListOf(mi))
        }
    }

    fun dump() {
        log.i("Class: $fullName")
        log.withIndent {
            location.dump()

            // Sort and print fields and methods.
            methods.toSortedMap().forEach { entry ->
                entry.value.sortedBy { method -> method.simpleDesc }.forEach {
                    it.dump()
                }
            }
        }
    }

    /** Find a field by name */
    fun findField(fieldName: String): FieldInfo? {
        return fields[fieldName]
    }

    /**
     * Find a field by name and descriptor.
     *
     * If [descriptor] is "*", then all methods with the name will be returned.
     */
    fun findMethods(methodName: String, methodDesc: String): List<MethodInfo>? {
        val list = methods[methodName] ?: return null

        // Wildcard method policy.
        if (methodDesc == "*") {
            return list
        }

        val simpleDesc = simplifyMethodDesc(methodDesc)
        list.forEach { mi ->
            if (simpleDesc == mi.simpleDesc) {
                return listOf(mi)
            }
        }
        log.w("Method $fullName.$methodName found, but none match description '$methodDesc'")
        return null
    }
}

/**
 * Stores all classes
 */
data class AllClassInfo (
    val classes: MutableMap<String, ClassInfo> = mutableMapOf(),
) {
    fun add(ci: ClassInfo) {
        classes.put(ci.fullName, ci)
    }

    fun dump() {
        classes.toSortedMap { a, b -> a.compareTo(b) }.forEach {
            it.value.dump()
        }
    }

    fun findClass(name: String): ClassInfo? {
        return classes.get(name)
    }
}

fun typeToSimpleDesc(origType: String): String {
    var type = origType

    // Detect arrays.
    var arrayPrefix = ""
    while (type.endsWith("[]")) {
        arrayPrefix += "["
        type = type.substring(0, type.length - 2)
    }

    // Delete generic parameters. (delete everything after '<')
    type.indexOf('<').let { pos ->
        if (pos >= 0) {
            type = type.substring(0, pos)
        }
    }

    // Handle builtins.
    val builtinType = when (type) {
        "byte" -> "B"
        "short" -> "S"
        "int" -> "I"
        "long" -> "J"
        "float" -> "F"
        "double" -> "D"
        "boolean" -> "Z"
        "char" -> "C"
        "void" -> "V"
        else -> null
    }

    builtinType?.let {
        return arrayPrefix + builtinType
    }

    return arrayPrefix + "L" + type + ";"
}

/**
 * Get a "simple" description of a method.
 *
 * "Simple" descriptions are similar to "real" ones, except:
 * - No return type.
 * - No package names in type names.
 */
fun getSimpleDesc(method: PsiMethod): String {
    val sb = StringBuilder()

    sb.append("(")

    val params = method.parameterList
    for (i in 0..<params.parametersCount) {
        val param = params.getParameter(i)

        val type = param?.type?.presentableText

        if (type == null) {
            throw RuntimeException(
                "Unable to decode parameter list from method from ${params.parent}")
        }

        sb.append(typeToSimpleDesc(type))
    }

    sb.append(")")

    return sb.toString()
}

private val reTypeFinder = "L.*/".toRegex()

private fun simplifyMethodDesc(origMethodDesc: String): String {
    // We don't need the return type, so remove everything after the ')'.
    val pos = origMethodDesc.indexOf(')')
    var desc = if (pos < 0) { origMethodDesc } else { origMethodDesc.substring(0, pos + 1) }

    // Then we remove the package names from all the class names.
    // i.e. convert "Ljava/lang/String" to "LString".

    return desc.replace(reTypeFinder, "L")
}

/**
 * Class that reads and parses java source files using PSI and populate [AllClassInfo].
 */
class SourceLoader(
    val environment: UastEnvironment,
) {
    private val fileSystem = StandardFileSystems.local()
    private val manager = PsiManager.getInstance(environment.ideaProject)

    /** Classes that were parsed */
    private var numParsedClasses = 0

    /**
     * Main entry point.
     */
    fun load(filesOrDirectories: List<String>, classes: AllClassInfo) {
        val psiFiles = mutableListOf<PsiFile>()
        log.i("Loading source files...")
        log.iTime("Discovering source files") {
            load(filesOrDirectories.map { File(it) }, psiFiles)
        }

        log.i("${psiFiles.size} file(s) found.")

        if (psiFiles.size == 0) {
            throw GeneralUserErrorException("No source files found.")
        }

        log.iTime("Parsing source files") {
            log.withIndent {
                for (file in psiFiles.asSequence().distinct()) {
                    val classesInFile = (file as? PsiClassOwner)?.classes?.toList()
                    classesInFile?.forEach { clazz ->
                        loadClass(clazz)?.let { classes.add(it) }

                        clazz.innerClasses.forEach { inner ->
                            loadClass(inner)?.let { classes.add(it) }
                        }
                    }
                }
            }
        }
        log.i("$numParsedClasses class(es) found.")
    }

    private fun load(filesOrDirectories: List<File>, result: MutableList<PsiFile>) {
        filesOrDirectories.forEach {
            load(it, result)
        }
    }

    private fun load(file: File, result: MutableList<PsiFile>) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                load(child, result)
            }
            return
        }

        // It's a file
        when (file.extension) {
            "java" -> {
                // Load it.
            }
            "kt" -> {
                log.w("Kotlin not supported, not loading ${file.path}")
                return
            }
            else -> return // Silently skip
        }
        fileSystem.findFileByPath(file.path)?.let { virtualFile ->
            manager.findFile(virtualFile)?.let { psiFile ->
                result.add(psiFile)
            }
        }
    }

    private fun loadClass(clazz: PsiClass): ClassInfo? {
        if (clazz is SyntheticElement) {
            return null
        }
        log.forVerbose {
            log.v("Class found: ${clazz.qualifiedName}")
        }
        numParsedClasses++

        log.withIndent {
            val ci = ClassInfo(
                clazz.qualifiedName!!,
                getLocation(clazz) ?: return null,
            )

            // Load fields.
            clazz.fields.filter { it !is SyntheticElement }.forEach {
                val name = it.name
                log.forDebug { log.d("Field found: $name") }
                val loc = getLocation(it) ?: return@forEach
                ci.add(FieldInfo(name, loc))
            }

            // Load methods.
            clazz.methods.filter { it !is SyntheticElement }.forEach {
                val name = resolveMethodName(it)
                val simpleDesc = getSimpleDesc(it)
                log.forDebug { log.d("Method found: $name$simpleDesc") }
                val loc = getLocation(it) ?: return@forEach
                ci.add(MethodInfo(name, simpleDesc, loc))
            }
            return ci
        }
    }

    private fun resolveMethodName(method: PsiMethod): String {
        val clazz = method.containingClass!!
        if (clazz.name == method.name) {
            return "<init>" // It's a constructor.
        }
        return method.name
    }

    private fun getLocation(elem: PsiElement): Location? {
        val lineAndIndent = getLineNumberAndIndent(elem)
        if (lineAndIndent == null) {
            log.w("Unable to determine location of $elem")
            return null
        }
        return Location(
            elem.containingFile.originalFile.virtualFile.path,
            lineAndIndent.first,
            lineAndIndent.second,
        )
    }

    private fun getLineNumberAndIndent(element: PsiElement): Pair<Int, Int>? {
        val psiFile: PsiFile = element.containingFile ?: return null
        val document: Document = psiFile.viewProvider.document ?: return null

        // Actual elements such as PsiClass, PsiMethod and PsiField contains the leading
        // javadoc, etc, so use the "identifier"'s element, if available.
        // For synthesized elements, this may return null.
        val targetRange = (
                (element as PsiNameIdentifierOwner).nameIdentifier?.textRange ?: element.textRange
                ) ?: return null
        val lineNumber = document.getLineNumber(targetRange.startOffset)
        val lineStartOffset = document.getLineStartOffset(lineNumber)

        val lineLeadingText = document.getText(
            com.intellij.openapi.util.TextRange(lineStartOffset, targetRange.startOffset))

        val indent = lineLeadingText.takeWhile { it.isWhitespace() }.length

        // Line numbers are 0-based, add 1 for human-readable format
        return Pair(lineNumber + 1, indent)
    }
}