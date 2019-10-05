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
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
        val groups = ProtoLogGroupReader()
                .loadFromJar(command.protoLogGroupsJarArg, command.protoLogGroupsClassNameArg)
        val out = FileOutputStream(command.outputSourceJarArg)
        val outJar = JarOutputStream(out)
        val processor = ProtoLogCallProcessor(command.protoLogClassNameArg,
                command.protoLogGroupsClassNameArg, groups)
        val transformer = SourceTransformer(command.protoLogImplClassNameArg, processor)

        command.javaSourceArgs.forEach { path ->
            val file = File(path)
            val text = file.readText()
            val newPath = path
            val outSrc = try {
                val code = tryParse(text, path)
                if (containsProtoLogText(text, command.protoLogClassNameArg)) {
                    transformer.processClass(text, newPath, code)
                } else {
                    text
                }
            } catch (ex: ParsingException) {
                // If we cannot parse this file, skip it (and log why). Compilation will fail
                // in a subsequent build step.
                println("\n${ex.message}\n")
                text
            }
            outJar.putNextEntry(ZipEntry(newPath))
            outJar.write(outSrc.toByteArray())
            outJar.closeEntry()
        }

        outJar.close()
        out.close()
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
        val groups = ProtoLogGroupReader()
                .loadFromJar(command.protoLogGroupsJarArg, command.protoLogGroupsClassNameArg)
        val processor = ProtoLogCallProcessor(command.protoLogClassNameArg,
                command.protoLogGroupsClassNameArg, groups)
        val builder = ViewerConfigBuilder(processor)
        command.javaSourceArgs.forEach { path ->
            val file = File(path)
            val text = file.readText()
            if (containsProtoLogText(text, command.protoLogClassNameArg)) {
                try {
                    val code = tryParse(text, path)
                    val pack = if (code.packageDeclaration.isPresent) code.packageDeclaration
                            .get().nameAsString else ""
                    val newPath = pack.replace('.', '/') + '/' + file.name
                    builder.processClass(code, newPath)
                } catch (ex: ParsingException) {
                    // If we cannot parse this file, skip it (and log why). Compilation will fail
                    // in a subsequent build step.
                    println("\n${ex.message}\n")
                }
            }
        }
        val out = FileOutputStream(command.viewerConfigJsonArg)
        out.write(builder.build().toByteArray())
        out.close()
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
            when (command.command) {
                CommandOptions.TRANSFORM_CALLS_CMD -> processClasses(command)
                CommandOptions.GENERATE_CONFIG_CMD -> viewerConf(command)
                CommandOptions.READ_LOG_CMD -> read(command)
            }
        } catch (ex: InvalidCommandException) {
            println("\n${ex.message}\n")
            showHelpAndExit()
        } catch (ex: CodeProcessingException) {
            println("\n${ex.message}\n")
            exitProcess(1)
        }
    }
}
