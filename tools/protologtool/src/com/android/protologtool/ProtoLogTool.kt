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

package com.android.protologtool

import com.android.protologtool.CommandOptions.Companion.USAGE
import com.github.javaparser.StaticJavaParser
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
            val code = StaticJavaParser.parse(text)
            val outSrc = transformer.processClass(text, code)
            val pack = if (code.packageDeclaration.isPresent) code.packageDeclaration
                    .get().nameAsString else ""
            val newPath = pack.replace('.', '/') + '/' + file.name
            outJar.putNextEntry(ZipEntry(newPath))
            outJar.write(outSrc.toByteArray())
            outJar.closeEntry()
        }

        outJar.close()
        out.close()
    }

    private fun viewerConf(command: CommandOptions) {
        val groups = ProtoLogGroupReader()
                .loadFromJar(command.protoLogGroupsJarArg, command.protoLogGroupsClassNameArg)
        val processor = ProtoLogCallProcessor(command.protoLogClassNameArg,
                command.protoLogGroupsClassNameArg, groups)
        val builder = ViewerConfigBuilder(processor)
        command.javaSourceArgs.forEach { path ->
            val file = File(path)
            builder.processClass(StaticJavaParser.parse(file))
        }
        val out = FileOutputStream(command.viewerConfigJsonArg)
        out.write(builder.build().toByteArray())
        out.close()
    }

    fun read(command: CommandOptions) {
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
            println(ex.message)
            showHelpAndExit()
        }
    }
}
