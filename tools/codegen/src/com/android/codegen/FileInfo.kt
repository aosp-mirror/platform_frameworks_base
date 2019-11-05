/*
 * Copyright (C) 2019 The Android Open Source Project
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


package com.android.codegen

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import java.io.File

/**
 * File-level parsing & printing logic
 *
 * @see [main] entrypoint
 */
class FileInfo(
        val sourceLines: List<String>,
        val cliArgs: Array<String>,
        val file: File)
    : Printer<FileInfo>, ImportsProvider {

    override val fileAst: CompilationUnit
            = parseJava(JavaParser::parse, sourceLines.joinToString("\n"))

    override val stringBuilder = StringBuilder()
    override var currentIndent = INDENT_SINGLE


    val generatedWarning = run {
        val fileEscaped = file.absolutePath.replace(
                System.getenv("ANDROID_BUILD_TOP"), "\$ANDROID_BUILD_TOP")

        """


        // $GENERATED_WARNING_PREFIX v$CODEGEN_VERSION.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ $THIS_SCRIPT_LOCATION$CODEGEN_NAME ${cliArgs.dropLast(1).joinToString("") { "$it " }}$fileEscaped
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off

        """
    }
    private val generatedWarningNumPrecedingEmptyLines
            = generatedWarning.lines().takeWhile { it.isBlank() }.size

    val classes = fileAst.types
            .filterIsInstance<ClassOrInterfaceDeclaration>()
            .flatMap { it.plusNested() }
            .filterNot { it.isInterface }

    val mainClass = classes.find { it.nameAsString == file.nameWithoutExtension }!!

    // Parse stage 1
    val classBounds: List<ClassBounds> = classes.map { ast ->
        ClassBounds(ast, fileInfo = this)
    }.apply {
        forEachApply {
            if (ast.isNestedType) {
                val parent = find {
                    it.name == (ast.parentNode.get()!! as TypeDeclaration<*>).nameAsString
                }!!
                parent.nested.add(this)
                nestedIn = parent
            }
        }
    }

    // Parse Stage 2
    var codeChunks = buildList<CodeChunk> {
        val mainClassBounds = classBounds.find { it.nestedIn == null }!!
        add(CodeChunk.FileHeader(
                mainClassBounds.fileInfo.sourceLines.subList(0, mainClassBounds.range.start)))
        add(CodeChunk.DataClass.parse(mainClassBounds))
    }

    // Output stage
    fun main() {
        codeChunks.forEach { print(it) }
    }

    fun print(chunk: CodeChunk) {
        when(chunk) {
            is CodeChunk.GeneratedCode -> {
                // Re-parse class code, discarding generated code and nested dataclasses
                val ast = chunk.owner.chunks
                        .filter {
                            it.javaClass == CodeChunk.Code::class.java
                                    || it.javaClass == CodeChunk.ClosingBrace::class.java
                        }
                        .flatMap { (it as CodeChunk.Code).lines }
                        .joinToString("\n")
                        .let {
                            parseJava(JavaParser::parseTypeDeclaration, it)
                                    as ClassOrInterfaceDeclaration
                        }

                // Write new generated code
                ClassPrinter(ast, fileInfo = this).print()
            }
            is CodeChunk.ClosingBrace -> {
                // Special case - print closing brace with -1 indent
                rmEmptyLine()
                popIndent()
                +"\n}"
            }
            // Print general code as-is
            is CodeChunk.Code -> chunk.lines.forEach { stringBuilder.appendln(it) }
            // Recursively render data classes
            is CodeChunk.DataClass -> chunk.chunks.forEach { print(it) }
        }
    }

    /**
     * Output of stage 1 of parsing a file:
     * Recursively nested ranges of code line numbers containing nested classes
     */
    data class ClassBounds(
            val ast: ClassOrInterfaceDeclaration,
            val fileInfo: FileInfo,
            val name: String = ast.nameAsString,
            val range: ClosedRange<Int> = ast.range.get()!!.let { rng -> rng.begin.line-1..rng.end.line-1 },
            val nested: MutableList<ClassBounds> = mutableListOf(),
            var nestedIn: ClassBounds? = null) {

        val nestedDataClasses: List<ClassBounds> by lazy {
            nested.filter { it.isDataclass }.sortedBy { it.range.start }
        }
        val isDataclass = ast.annotations.any { it.nameAsString.endsWith("DataClass") }

        val baseIndentLength = fileInfo.sourceLines.find { "class $name" in it }!!.takeWhile { it == ' ' }.length
        val baseIndent = buildString { repeat(baseIndentLength) { append(' ') } }

        val sourceNoPrefix = fileInfo.sourceLines.drop(range.start)
        val generatedCodeRange = sourceNoPrefix
                .indexOfFirst { it.startsWith("$baseIndent$INDENT_SINGLE// $GENERATED_WARNING_PREFIX") }
                .let { start ->
                    if (start < 0) {
                        null
                    } else {
                        var endInclusive = sourceNoPrefix.indexOfFirst {
                            it.startsWith("$baseIndent$INDENT_SINGLE$GENERATED_END")
                        }
                        if (endInclusive == -1) {
                            // Legacy generated code doesn't have end markers
                            endInclusive = sourceNoPrefix.size - 2
                        }
                        IntRange(
                                range.start + start - fileInfo.generatedWarningNumPrecedingEmptyLines,
                                range.start + endInclusive)
                    }
                }

        /** Debug info */
        override fun toString(): String {
            return buildString {
                appendln("class $name $range")
                nested.forEach {
                    appendln(it)
                }
                appendln("end $name")
            }
        }
    }

    /**
     * Output of stage 2 of parsing a file
     */
    sealed class CodeChunk {
        /** General code */
        open class Code(val lines: List<String>): CodeChunk() {}

        /** Copyright + package + imports + main javadoc */
        class FileHeader(lines: List<String>): Code(lines)

        /** Code to be discarded and refreshed */
        open class GeneratedCode(lines: List<String>): Code(lines) {
            lateinit var owner: DataClass

            class Placeholder: GeneratedCode(emptyList())
        }

        object ClosingBrace: Code(listOf("}"))

        data class DataClass(
                val ast: ClassOrInterfaceDeclaration,
                val chunks: List<CodeChunk>,
                val generatedCode: GeneratedCode?): CodeChunk() {

            companion object {
                fun parse(classBounds: ClassBounds): DataClass {
                    val initial = Code(lines = classBounds.fileInfo.sourceLines.subList(
                            fromIndex = classBounds.range.start,
                            toIndex = findLowerBound(
                                    thisClass = classBounds,
                                    nextNestedClass = classBounds.nestedDataClasses.getOrNull(0))))

                    val chunks = mutableListOf<CodeChunk>(initial)

                    classBounds.nestedDataClasses.forEachSequentialPair {
                            nestedDataClass, nextNestedDataClass ->
                        chunks += DataClass.parse(nestedDataClass)
                        chunks += Code(lines = classBounds.fileInfo.sourceLines.subList(
                                fromIndex = nestedDataClass.range.endInclusive + 1,
                                toIndex = findLowerBound(
                                        thisClass = classBounds,
                                        nextNestedClass = nextNestedDataClass)))
                    }

                    var generatedCode = classBounds.generatedCodeRange?.let { rng ->
                        GeneratedCode(classBounds.fileInfo.sourceLines.subList(
                                rng.start, rng.endInclusive+1))
                    }
                    if (generatedCode != null) {
                        chunks += generatedCode
                        chunks += ClosingBrace
                    } else if (classBounds.isDataclass) {

                        // Insert placeholder for generated code to be inserted for the 1st time
                        chunks.last = (chunks.last as Code)
                                .lines
                                .dropLastWhile { it.isBlank() }
                                .run {
                                    if (last().dropWhile { it.isWhitespace() }.startsWith("}")) {
                                        dropLast(1)
                                    } else {
                                        this
                                    }
                                }.let { Code(it) }
                        generatedCode = GeneratedCode.Placeholder()
                        chunks += generatedCode
                        chunks += ClosingBrace
                    } else {
                        // Outer class may be not a @DataClass but contain ones
                        // so just skip generated code for them
                    }

                    return DataClass(classBounds.ast, chunks, generatedCode).also {
                        generatedCode?.owner = it
                    }
                }

                private fun findLowerBound(thisClass: ClassBounds, nextNestedClass: ClassBounds?): Int {
                    return nextNestedClass?.range?.start
                            ?: thisClass.generatedCodeRange?.start
                            ?: thisClass.range.endInclusive + 1
                }
            }
        }

        /** Debug info */
        fun summary(): String = when(this) {
            is Code -> "${javaClass.simpleName}(${lines.size} lines): ${lines.getOrNull(0)?.take(70) ?: ""}..."
            is DataClass -> "DataClass ${ast.nameAsString}:\n" +
                    chunks.joinToString("\n") { it.summary() } +
                    "\n//end ${ast.nameAsString}"
        }
    }

    private fun ClassOrInterfaceDeclaration.plusNested(): List<ClassOrInterfaceDeclaration> {
        return mutableListOf<ClassOrInterfaceDeclaration>().apply {
            add(this@plusNested)
            childNodes.filterIsInstance<ClassOrInterfaceDeclaration>()
                    .flatMap { it.plusNested() }
                    .let { addAll(it) }
        }
    }
}