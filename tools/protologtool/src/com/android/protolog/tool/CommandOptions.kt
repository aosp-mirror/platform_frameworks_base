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

import java.util.regex.Pattern

class CommandOptions(args: Array<String>) {
    companion object {
        const val TRANSFORM_CALLS_CMD = "transform-protolog-calls"
        const val GENERATE_CONFIG_CMD = "generate-viewer-config"
        const val READ_LOG_CMD = "read-log"
        private val commands = setOf(TRANSFORM_CALLS_CMD, GENERATE_CONFIG_CMD, READ_LOG_CMD)

        private const val PROTOLOG_CLASS_PARAM = "--protolog-class"
        private const val PROTOLOGIMPL_CLASS_PARAM = "--protolog-impl-class"
        private const val PROTOLOGGROUP_CLASS_PARAM = "--loggroups-class"
        private const val PROTOLOGGROUP_JAR_PARAM = "--loggroups-jar"
        private const val VIEWER_CONFIG_JSON_PARAM = "--viewer-conf"
        private const val OUTPUT_SOURCE_JAR_PARAM = "--output-srcjar"
        private val parameters = setOf(PROTOLOG_CLASS_PARAM, PROTOLOGIMPL_CLASS_PARAM,
                PROTOLOGGROUP_CLASS_PARAM, PROTOLOGGROUP_JAR_PARAM, VIEWER_CONFIG_JSON_PARAM,
                OUTPUT_SOURCE_JAR_PARAM)

        val USAGE = """
            Usage: ${Constants.NAME} <command> [<args>]
            Available commands:

            $TRANSFORM_CALLS_CMD $PROTOLOG_CLASS_PARAM <class name> $PROTOLOGIMPL_CLASS_PARAM
                <class name> $PROTOLOGGROUP_CLASS_PARAM <class name> $PROTOLOGGROUP_JAR_PARAM
                <config.jar> $OUTPUT_SOURCE_JAR_PARAM <output.srcjar> [<input.java>]
            - processes java files replacing stub calls with logging code.

            $GENERATE_CONFIG_CMD $PROTOLOG_CLASS_PARAM <class name> $PROTOLOGGROUP_CLASS_PARAM
                <class name> $PROTOLOGGROUP_JAR_PARAM <config.jar> $VIEWER_CONFIG_JSON_PARAM
                <viewer.json> [<input.java>]
            - creates viewer config file from given java files.

            $READ_LOG_CMD $VIEWER_CONFIG_JSON_PARAM <viewer.json> <wm_log.pb>
            - translates a binary log to a readable format.
        """.trimIndent()

        private fun validateClassName(name: String): String {
            if (!Pattern.matches("^([a-z]+[A-Za-z0-9]*\\.)+([A-Za-z0-9]+)$", name)) {
                throw InvalidCommandException("Invalid class name $name")
            }
            return name
        }

        private fun getParam(paramName: String, params: Map<String, String>): String {
            if (!params.containsKey(paramName)) {
                throw InvalidCommandException("Param $paramName required")
            }
            return params.getValue(paramName)
        }

        private fun validateNotSpecified(paramName: String, params: Map<String, String>): String {
            if (params.containsKey(paramName)) {
                throw InvalidCommandException("Unsupported param $paramName")
            }
            return ""
        }

        private fun validateJarName(name: String): String {
            if (!name.endsWith(".jar")) {
                throw InvalidCommandException("Jar file required, got $name instead")
            }
            return name
        }

        private fun validateSrcJarName(name: String): String {
            if (!name.endsWith(".srcjar")) {
                throw InvalidCommandException("Source jar file required, got $name instead")
            }
            return name
        }

        private fun validateJSONName(name: String): String {
            if (!name.endsWith(".json")) {
                throw InvalidCommandException("Json file required, got $name instead")
            }
            return name
        }

        private fun validateJavaInputList(list: List<String>): List<String> {
            if (list.isEmpty()) {
                throw InvalidCommandException("No java source input files")
            }
            list.forEach { name ->
                if (!name.endsWith(".java")) {
                    throw InvalidCommandException("Not a java source file $name")
                }
            }
            return list
        }

        private fun validateLogInputList(list: List<String>): String {
            if (list.isEmpty()) {
                throw InvalidCommandException("No log input file")
            }
            if (list.size > 1) {
                throw InvalidCommandException("Only one log input file allowed")
            }
            return list[0]
        }
    }

    val protoLogClassNameArg: String
    val protoLogGroupsClassNameArg: String
    val protoLogImplClassNameArg: String
    val protoLogGroupsJarArg: String
    val viewerConfigJsonArg: String
    val outputSourceJarArg: String
    val logProtofileArg: String
    val javaSourceArgs: List<String>
    val command: String

    init {
        if (args.isEmpty()) {
            throw InvalidCommandException("No command specified.")
        }
        command = args[0]
        if (command !in commands) {
            throw InvalidCommandException("Unknown command.")
        }

        val params: MutableMap<String, String> = mutableMapOf()
        val inputFiles: MutableList<String> = mutableListOf()

        var idx = 1
        while (idx < args.size) {
            if (args[idx].startsWith("--")) {
                if (idx + 1 >= args.size) {
                    throw InvalidCommandException("No value for ${args[idx]}")
                }
                if (args[idx] !in parameters) {
                    throw InvalidCommandException("Unknown parameter ${args[idx]}")
                }
                if (args[idx + 1].startsWith("--")) {
                    throw InvalidCommandException("No value for ${args[idx]}")
                }
                if (params.containsKey(args[idx])) {
                    throw InvalidCommandException("Duplicated parameter ${args[idx]}")
                }
                params[args[idx]] = args[idx + 1]
                idx += 2
            } else {
                inputFiles.add(args[idx])
                idx += 1
            }
        }

        when (command) {
            TRANSFORM_CALLS_CMD -> {
                protoLogClassNameArg = validateClassName(getParam(PROTOLOG_CLASS_PARAM, params))
                protoLogGroupsClassNameArg = validateClassName(getParam(PROTOLOGGROUP_CLASS_PARAM,
                        params))
                protoLogImplClassNameArg = validateClassName(getParam(PROTOLOGIMPL_CLASS_PARAM,
                        params))
                protoLogGroupsJarArg = validateJarName(getParam(PROTOLOGGROUP_JAR_PARAM, params))
                viewerConfigJsonArg = validateNotSpecified(VIEWER_CONFIG_JSON_PARAM, params)
                outputSourceJarArg = validateSrcJarName(getParam(OUTPUT_SOURCE_JAR_PARAM, params))
                javaSourceArgs = validateJavaInputList(inputFiles)
                logProtofileArg = ""
            }
            GENERATE_CONFIG_CMD -> {
                protoLogClassNameArg = validateClassName(getParam(PROTOLOG_CLASS_PARAM, params))
                protoLogGroupsClassNameArg = validateClassName(getParam(PROTOLOGGROUP_CLASS_PARAM,
                        params))
                protoLogImplClassNameArg = validateNotSpecified(PROTOLOGIMPL_CLASS_PARAM, params)
                protoLogGroupsJarArg = validateJarName(getParam(PROTOLOGGROUP_JAR_PARAM, params))
                viewerConfigJsonArg = validateJSONName(getParam(VIEWER_CONFIG_JSON_PARAM, params))
                outputSourceJarArg = validateNotSpecified(OUTPUT_SOURCE_JAR_PARAM, params)
                javaSourceArgs = validateJavaInputList(inputFiles)
                logProtofileArg = ""
            }
            READ_LOG_CMD -> {
                protoLogClassNameArg = validateNotSpecified(PROTOLOG_CLASS_PARAM, params)
                protoLogGroupsClassNameArg = validateNotSpecified(PROTOLOGGROUP_CLASS_PARAM, params)
                protoLogImplClassNameArg = validateNotSpecified(PROTOLOGIMPL_CLASS_PARAM, params)
                protoLogGroupsJarArg = validateNotSpecified(PROTOLOGGROUP_JAR_PARAM, params)
                viewerConfigJsonArg = validateJSONName(getParam(VIEWER_CONFIG_JSON_PARAM, params))
                outputSourceJarArg = validateNotSpecified(OUTPUT_SOURCE_JAR_PARAM, params)
                javaSourceArgs = listOf()
                logProtofileArg = validateLogInputList(inputFiles)
            }
            else -> {
                throw InvalidCommandException("Unknown command.")
            }
        }
    }
}
