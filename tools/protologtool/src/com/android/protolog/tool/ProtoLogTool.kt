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

import com.android.protolog.tool.CommandOptions.Companion.USAGE
import com.github.javaparser.ParseProblemException
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.system.exitProcess

object ProtoLogTool {
    private fun showHelpAndExit() {
        println(USAGE)
        exitProcess(-1)
    }

    private fun containsProtoLogText(source: String, protoLogClassName: String): Boolean {
        val protoLogSimpleClassName = protoLogClassName.substringAfterLast('.')
        return source.contains(protoLogSimpleClassName)
    }

    private fun processClasses(command: CommandOptions) {
        val groups = injector.readLogGroups(
                command.protoLogGroupsJarArg,
                command.protoLogGroupsClassNameArg)
        val out = injector.fileOutputStream(command.outputSourceJarArg)
        val outJar = JarOutputStream(out)
        val processor = ProtoLogCallProcessor(command.protoLogClassNameArg,
                command.protoLogGroupsClassNameArg, groups)

        val executor = newThreadPool()

        try {
            command.javaSourceArgs.map { path ->
                executor.submitCallable {
                    val transformer = SourceTransformer(command.protoLogImplClassNameArg,
                            command.protoLogCacheClassNameArg, processor)
                    val file = File(path)
                    val text = injector.readText(file)
                    val outSrc = try {
                        val code = tryParse(text, path)
                        if (containsProtoLogText(text, command.protoLogClassNameArg)) {
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
                outJar.putNextEntry(ZipEntry(path))
                outJar.write(outSrc.toByteArray())
                outJar.closeEntry()
            }
        } finally {
            executor.shutdown()
        }

        val cacheSplit = command.protoLogCacheClassNameArg.split(".")
        val cacheName = cacheSplit.last()
        val cachePackage = cacheSplit.dropLast(1).joinToString(".")
        val cachePath = "gen/${cacheSplit.joinToString("/")}.java"

        outJar.putNextEntry(ZipEntry(cachePath))
        outJar.write(generateLogGroupCache(cachePackage, cacheName, groups,
                command.protoLogImplClassNameArg, command.protoLogGroupsClassNameArg).toByteArray())

        outJar.close()
        out.close()
    }

    fun generateLogGroupCache(
        cachePackage: String,
        cacheName: String,
        groups: Map<String, LogGroup>,
        protoLogImplClassName: String,
        protoLogGroupsClassName: String
    ): String {
        val fields = groups.values.map {
            "public static boolean ${it.name}_enabled = false;"
        }.joinToString("\n")

        val updates = groups.values.map {
            "${it.name}_enabled = " +
                    "$protoLogImplClassName.isEnabled($protoLogGroupsClassName.${it.name});"
        }.joinToString("\n")

        return """
            package $cachePackage;

            public class $cacheName {
${fields.replaceIndent("                ")}

                static {
                    $protoLogImplClassName.sCacheUpdater = $cacheName::update;
                    update();
                }

                static void update() {
${updates.replaceIndent("                    ")}
                }
            }
        """.trimIndent()
    }

    private fun tryParse(code: String, fileName: String): CompilationUnit {
        try {
            return StaticJavaParser.parse(code)
        } catch (ex: ParseProblemException) {
            val problem = ex.problems.first()
            throw ParsingException("Java parsing erro" +
                    "r: ${problem.verboseMessage}",
                    ParsingContext(fileName, problem.location.orElse(null)
                            ?.begin?.range?.orElse(null)?.begin?.line
                            ?: 0))
        }
    }

    private fun viewerConf(command: CommandOptions) {
        val groups = injector.readLogGroups(
                command.protoLogGroupsJarArg,
                command.protoLogGroupsClassNameArg)
        val processor = ProtoLogCallProcessor(command.protoLogClassNameArg,
                command.protoLogGroupsClassNameArg, groups)
        val builder = ViewerConfigBuilder(processor)

        val executor = newThreadPool()

        try {
            command.javaSourceArgs.map { path ->
                executor.submitCallable {
                    val file = File(path)
                    val text = injector.readText(file)
                    if (containsProtoLogText(text, command.protoLogClassNameArg)) {
                        try {
                            val code = tryParse(text, path)
                            builder.findLogCalls(code, path, packagePath(file, code))
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
                builder.addLogCalls(future.get() ?: return@forEach)
            }
        } finally {
            executor.shutdown()
        }

        val out = injector.fileOutputStream(command.viewerConfigJsonArg)
        out.write(builder.build().toByteArray())
        out.close()
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
                        FileInputStream(command.viewerConfigJsonArg), System.out)
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
