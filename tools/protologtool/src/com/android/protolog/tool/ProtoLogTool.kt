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

package com.android.protolog.tool

import com.android.internal.protolog.common.LogLevel
import com.android.internal.protolog.common.ProtoLogToolInjected
import com.android.protolog.tool.CommandOptions.Companion.USAGE
import com.github.javaparser.ParseProblemException
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.ArrayCreationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.BlockStmt
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.math.absoluteValue
import kotlin.system.exitProcess

object ProtoLogTool {
    const val PROTOLOG_IMPL_SRC_PATH =
        "frameworks/base/core/java/com/android/internal/protolog/ProtoLogImpl.java"

    private const val PROTOLOG_CLASS_NAME = "ProtoLog"; // ProtoLog::class.java.simpleName

    data class LogCall(
        val messageString: String,
        val logLevel: LogLevel,
        val logGroup: LogGroup,
        val position: String
    )

    private fun showHelpAndExit() {
        println(USAGE)
        exitProcess(-1)
    }

    private fun containsProtoLogText(source: String, protoLogClassName: String): Boolean {
        val protoLogSimpleClassName = protoLogClassName.substringAfterLast('.')
        return source.contains(protoLogSimpleClassName)
    }

    private fun zipEntry(path: String): ZipEntry {
        val entry = ZipEntry(path)
        // Use a constant time to improve the cachability of build actions.
        entry.timeLocal = LocalDateTime.of(2008, 1, 1, 0, 0, 0)
        return entry
    }

    private fun processClasses(command: CommandOptions) {
        // A deterministic hash based on the group jar path and the source files we are processing.
        // The hash is required to make sure different ProtoLogImpls don't conflict.
        val generationHash = (command.javaSourceArgs.toTypedArray() + command.protoLogGroupsJarArg)
                .contentHashCode().absoluteValue

        // Need to generate a new impl class to inject static constants into the class.
        val generatedProtoLogImplClass =
            "com.android.internal.protolog.ProtoLogImpl_$generationHash"

        val groups = injector.readLogGroups(
                command.protoLogGroupsJarArg,
                command.protoLogGroupsClassNameArg)
        val out = injector.fileOutputStream(command.outputSourceJarArg)
        val outJar = JarOutputStream(out)
        val processor = ProtoLogCallProcessorImpl(
            command.protoLogClassNameArg,
            command.protoLogGroupsClassNameArg,
            groups)

        val protologImplName = generatedProtoLogImplClass.split(".").last()
        val protologImplPath = "gen/${generatedProtoLogImplClass.split(".")
                .joinToString("/")}.java"
        outJar.putNextEntry(zipEntry(protologImplPath))

        outJar.write(generateProtoLogImpl(protologImplName, command.viewerConfigFilePathArg,
            command.legacyViewerConfigFilePathArg, command.legacyOutputFilePath,
            groups, command.protoLogGroupsClassNameArg).toByteArray())

        val executor = newThreadPool()

        try {
            command.javaSourceArgs.map { path ->
                executor.submitCallable {
                    val transformer = SourceTransformer(generatedProtoLogImplClass, processor)
                    val file = File(path)
                    val text = injector.readText(file)
                    val outSrc = try {
                        val code = tryParse(text, path)
                        if (containsProtoLogText(text, PROTOLOG_CLASS_NAME)) {
                            transformer.processClass(text, path, packagePath(file, code), code)
                        } else {
                            text
                        }
                    } catch (ex: ParsingException) {
                        // If we cannot parse this file, skip it (and log why). Compilation will
                        // fail in a subsequent build step.
                        injector.reportParseError(ex)
                        text
                    }
                    path to outSrc
                }
            }.map { future ->
                val (path, outSrc) = future.get()
                outJar.putNextEntry(zipEntry(path))
                outJar.write(outSrc.toByteArray())
                outJar.closeEntry()
            }
        } finally {
            executor.shutdown()
        }

        outJar.close()
        out.close()
    }

    private fun generateProtoLogImpl(
        protoLogImplGenName: String,
        viewerConfigFilePath: String,
        legacyViewerConfigFilePath: String?,
        legacyOutputFilePath: String?,
        groups: Map<String, LogGroup>,
        protoLogGroupsClassName: String,
    ): String {
        val file = File(PROTOLOG_IMPL_SRC_PATH)

        val text = try {
            injector.readText(file)
        } catch (e: FileNotFoundException) {
            throw RuntimeException("Expected to find '$PROTOLOG_IMPL_SRC_PATH' but file was not " +
                    "included in source for the ProtoLog Tool to process.")
        }

        val code = tryParse(text, PROTOLOG_IMPL_SRC_PATH)

        val classDeclarations = code.findAll(ClassOrInterfaceDeclaration::class.java)
        require(classDeclarations.size == 1) { "Expected exactly one class declaration" }
        val classDeclaration = classDeclarations[0]

        val classNameNode = classDeclaration.findFirst(SimpleName::class.java).get()
        classNameNode.setId(protoLogImplGenName)

        injectCacheClass(classDeclaration, groups, protoLogGroupsClassName)

        injectConstants(classDeclaration,
            viewerConfigFilePath, legacyViewerConfigFilePath, legacyOutputFilePath, groups,
            protoLogGroupsClassName)

        return code.toString()
    }

    private fun injectConstants(
        classDeclaration: ClassOrInterfaceDeclaration,
        viewerConfigFilePath: String,
        legacyViewerConfigFilePath: String?,
        legacyOutputFilePath: String?,
        groups: Map<String, LogGroup>,
        protoLogGroupsClassName: String
    ) {
        classDeclaration.fields.forEach { field ->
            field.getAnnotationByClass(ProtoLogToolInjected::class.java)
                    .ifPresent { annotationExpr ->
                        if (annotationExpr.isSingleMemberAnnotationExpr) {
                            val valueName = annotationExpr.asSingleMemberAnnotationExpr()
                                    .memberValue.asNameExpr().name.asString()
                            when (valueName) {
                                ProtoLogToolInjected.Value.VIEWER_CONFIG_PATH.name -> {
                                    field.setFinal(true)
                                    field.variables.first()
                                            .setInitializer(StringLiteralExpr(viewerConfigFilePath))
                                }
                                ProtoLogToolInjected.Value.LEGACY_OUTPUT_FILE_PATH.name -> {
                                    field.setFinal(true)
                                    field.variables.first()
                                            .setInitializer(legacyOutputFilePath?.let {
                                                StringLiteralExpr(it)
                                            } ?: NullLiteralExpr())
                                }
                                ProtoLogToolInjected.Value.LEGACY_VIEWER_CONFIG_PATH.name -> {
                                    field.setFinal(true)
                                    field.variables.first()
                                            .setInitializer(legacyViewerConfigFilePath?.let {
                                                StringLiteralExpr(it)
                                            } ?: NullLiteralExpr())
                                }
                                ProtoLogToolInjected.Value.LOG_GROUPS.name -> {
                                    val initializerBlockStmt = BlockStmt()
                                    for (group in groups) {
                                        initializerBlockStmt.addStatement(
                                            MethodCallExpr()
                                                    .setName("put")
                                                    .setArguments(
                                                        NodeList(StringLiteralExpr(group.key),
                                                            FieldAccessExpr()
                                                                    .setScope(
                                                                        NameExpr(
                                                                            protoLogGroupsClassName
                                                                        ))
                                                                    .setName(group.value.name)))
                                        )
                                        group.key
                                    }

                                    val treeMapCreation = ObjectCreationExpr()
                                            .setType("TreeMap<String, IProtoLogGroup>")
                                            .setAnonymousClassBody(NodeList(
                                                InitializerDeclaration().setBody(
                                                    initializerBlockStmt
                                                )
                                            ))

                                    field.setFinal(true)
                                    field.variables.first().setInitializer(treeMapCreation)
                                }
                                ProtoLogToolInjected.Value.CACHE_UPDATER.name -> {
                                    field.setFinal(true)
                                    field.variables.first().setInitializer(MethodReferenceExpr()
                                            .setScope(NameExpr("Cache"))
                                            .setIdentifier("update"))
                                }
                                else -> error("Unhandled ProtoLogToolInjected value: $valueName.")
                            }
                        }
                    }
        }
    }

    private fun injectCacheClass(
        classDeclaration: ClassOrInterfaceDeclaration,
        groups: Map<String, LogGroup>,
        protoLogGroupsClassName: String,
    ) {
        val cacheClass = ClassOrInterfaceDeclaration()
            .setName("Cache")
            .setPublic(true)
            .setStatic(true)
        for (group in groups) {
            val nodeList = NodeList<Expression>()
            val defaultVal = BooleanLiteralExpr(group.value.textEnabled || group.value.enabled)
            repeat(LogLevel.entries.size) { nodeList.add(defaultVal) }
            cacheClass.addFieldWithInitializer(
                "boolean[]",
                "${group.key}_enabled",
                ArrayCreationExpr().setElementType("boolean[]").setInitializer(
                    ArrayInitializerExpr().setValues(nodeList)
                ),
                Modifier.Keyword.PUBLIC,
                Modifier.Keyword.STATIC
            )
        }

        val updateBlockStmt = BlockStmt()
        for (group in groups) {
            for (level in LogLevel.entries) {
                updateBlockStmt.addStatement(
                    AssignExpr()
                        .setTarget(
                            ArrayAccessExpr()
                                .setName(NameExpr("${group.key}_enabled"))
                                .setIndex(IntegerLiteralExpr(level.ordinal))
                        ).setValue(
                            MethodCallExpr()
                                .setName("isEnabled")
                                .setArguments(NodeList(
                                    FieldAccessExpr()
                                        .setScope(NameExpr(protoLogGroupsClassName))
                                        .setName(group.value.name),
                                    FieldAccessExpr()
                                        .setScope(NameExpr("LogLevel"))
                                        .setName(level.toString()),
                                ))
                        )
                )
            }
        }

        cacheClass.addMethod("update").setPrivate(true).setStatic(true)
            .setBody(updateBlockStmt)

        classDeclaration.addMember(cacheClass)
    }

    private fun tryParse(code: String, fileName: String): CompilationUnit {
        try {
            return StaticJavaParser.parse(code)
        } catch (ex: ParseProblemException) {
            val problem = ex.problems.first()
            throw ParsingException("Java parsing error: ${problem.verboseMessage}",
                    ParsingContext(fileName, problem.location.orElse(null)
                            ?.begin?.range?.orElse(null)?.begin?.line
                            ?: 0))
        }
    }

    class LogCallRegistry {
        private val statements = mutableMapOf<LogCall, Long>()

        fun addLogCalls(calls: List<LogCall>) {
            calls.forEach { logCall ->
                if (logCall.logGroup.enabled) {
                    statements.putIfAbsent(logCall,
                        CodeUtils.hash(logCall.position, logCall.messageString,
                            logCall.logLevel, logCall.logGroup))
                }
            }
        }

        fun getStatements(): Map<LogCall, Long> {
            return statements
        }
    }

    interface ProtologViewerConfigBuilder {
        fun build(statements: Map<LogCall, Long>): ByteArray
    }

    private fun viewerConf(command: CommandOptions) {
        val groups = injector.readLogGroups(
                command.protoLogGroupsJarArg,
                command.protoLogGroupsClassNameArg)
        val processor = ProtoLogCallProcessorImpl(command.protoLogClassNameArg,
                command.protoLogGroupsClassNameArg, groups)
        val outputType = command.viewerConfigTypeArg

        val configBuilder: ProtologViewerConfigBuilder = when (outputType.lowercase()) {
            "json" -> ViewerConfigJsonBuilder()
            "proto" -> ViewerConfigProtoBuilder()
            else -> error("Invalid output type provide. Provided '$outputType'.")
        }

        val executor = newThreadPool()

        val logCallRegistry = LogCallRegistry()

        try {
            command.javaSourceArgs.map { path ->
                executor.submitCallable {
                    val file = File(path)
                    val text = injector.readText(file)
                    if (containsProtoLogText(text, command.protoLogClassNameArg)) {
                        try {
                            val code = tryParse(text, path)
                            findLogCalls(code, path, packagePath(file, code), processor)
                        } catch (ex: ParsingException) {
                            // If we cannot parse this file, skip it (and log why). Compilation will
                            // fail in a subsequent build step.
                            injector.reportParseError(ex)
                            null
                        }
                    } else {
                        null
                    }
                }
            }.forEach { future ->
                logCallRegistry.addLogCalls(future.get() ?: return@forEach)
            }
        } finally {
            executor.shutdown()
        }

        val outFile = injector.fileOutputStream(command.viewerConfigFileNameArg)
        outFile.write(configBuilder.build(logCallRegistry.getStatements()))
        outFile.close()
    }

    private fun findLogCalls(
        unit: CompilationUnit,
        path: String,
        packagePath: String,
        processor: ProtoLogCallProcessorImpl
    ): List<LogCall> {
        val calls = mutableListOf<LogCall>()
        val logCallVisitor = object : ProtoLogCallVisitor {
            override fun processCall(
                call: MethodCallExpr,
                messageString: String,
                level: LogLevel,
                group: LogGroup
            ) {
                val logCall = LogCall(messageString, level, group, packagePath)
                calls.add(logCall)
            }
        }
        processor.process(unit, logCallVisitor, path)

        return calls
    }

    private fun packagePath(file: File, code: CompilationUnit): String {
        val pack = if (code.packageDeclaration.isPresent) code.packageDeclaration
                .get().nameAsString else ""
        val packagePath = pack.replace('.', '/') + '/' + file.name
        return packagePath
    }

    private fun read(command: CommandOptions) {
        LogParser(ViewerConfigParser())
                .parse(FileInputStream(command.logProtofileArg),
                        FileInputStream(command.viewerConfigFileNameArg), System.out)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val command = CommandOptions(args)
            invoke(command)
        } catch (ex: InvalidCommandException) {
            println("\n${ex.message}\n")
            showHelpAndExit()
        } catch (ex: CodeProcessingException) {
            println("\n${ex.message}\n")
            exitProcess(1)
        }
    }

    fun invoke(command: CommandOptions) {
        StaticJavaParser.setConfiguration(ParserConfiguration().apply {
            setLanguageLevel(ParserConfiguration.LanguageLevel.RAW)
            setAttributeComments(false)
        })

        when (command.command) {
            CommandOptions.TRANSFORM_CALLS_CMD -> processClasses(command)
            CommandOptions.GENERATE_CONFIG_CMD -> viewerConf(command)
            CommandOptions.READ_LOG_CMD -> read(command)
        }
    }

    var injector = object : Injector {
        override fun fileOutputStream(file: String) = FileOutputStream(file)
        override fun readText(file: File) = file.readText()
        override fun readLogGroups(jarPath: String, className: String) =
                ProtoLogGroupReader().loadFromJar(jarPath, className)
        override fun reportParseError(ex: ParsingException) {
            println("\n${ex.message}\n")
        }
    }

    interface Injector {
        fun fileOutputStream(file: String): OutputStream
        fun readText(file: File): String
        fun readLogGroups(jarPath: String, className: String): Map<String, LogGroup>
        fun reportParseError(ex: ParsingException)
    }
}

private fun <T> ExecutorService.submitCallable(f: () -> T) = submit(f)

private fun newThreadPool() = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors())
