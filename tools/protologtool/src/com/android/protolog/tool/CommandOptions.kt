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

        // TODO: This is always the same. I don't think it's required
        private const val PROTOLOG_CLASS_PARAM = "--protolog-class"
        private const val PROTOLOGGROUP_CLASS_PARAM = "--loggroups-class"
        private const val PROTOLOGGROUP_JAR_PARAM = "--loggroups-jar"
        private const val VIEWER_CONFIG_PARAM = "--viewer-config"
        private const val VIEWER_CONFIG_TYPE_PARAM = "--viewer-config-type"
        private const val OUTPUT_SOURCE_JAR_PARAM = "--output-srcjar"
        private const val VIEWER_CONFIG_FILE_PATH_PARAM = "--viewer-config-file-path"
        // TODO(b/324128613): Remove these legacy options once we fully flip the Perfetto protolog flag
        private const val LEGACY_VIEWER_CONFIG_FILE_PATH_PARAM = "--legacy-viewer-config-file-path"
        private const val LEGACY_OUTPUT_FILE_PATH = "--legacy-output-file-path"
        private val parameters = setOf(PROTOLOG_CLASS_PARAM, PROTOLOGGROUP_CLASS_PARAM,
            PROTOLOGGROUP_JAR_PARAM, VIEWER_CONFIG_PARAM, VIEWER_CONFIG_TYPE_PARAM,
            OUTPUT_SOURCE_JAR_PARAM, VIEWER_CONFIG_FILE_PATH_PARAM,
            LEGACY_VIEWER_CONFIG_FILE_PATH_PARAM, LEGACY_OUTPUT_FILE_PATH)

        val USAGE = """
            Usage: ${Constants.NAME} <command> [<args>]
            Available commands:

            $TRANSFORM_CALLS_CMD $PROTOLOG_CLASS_PARAM <class name>
                $PROTOLOGGROUP_CLASS_PARAM <class name> $PROTOLOGGROUP_JAR_PARAM <config.jar>
                $OUTPUT_SOURCE_JAR_PARAM <output.srcjar> [<input.java>]
            - processes java files replacing stub calls with logging code.

            $GENERATE_CONFIG_CMD $PROTOLOG_CLASS_PARAM <class name>
                $PROTOLOGGROUP_CLASS_PARAM <class name> $PROTOLOGGROUP_JAR_PARAM <config.jar>
                $VIEWER_CONFIG_PARAM <viewer.json|viewer.pb> [<input.java>]
            - creates viewer config file from given java files.

            $READ_LOG_CMD $VIEWER_CONFIG_PARAM <viewer.json|viewer.pb> <wm_log.pb>
            - translates a binary log to a readable format.
        """.trimIndent()

        private fun validateClassName(name: String): String {
            if (!Pattern.matches("^([a-z]+[A-Za-z0-9]*\\.)+([A-Za-z0-9$]+)$", name)) {
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

        private fun getOptionalParam(paramName: String, params: Map<String, String>): String? {
            if (!params.containsKey(paramName)) {
                return null
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

        private fun validateViewerConfigFilePath(name: String): String {
            if (!name.endsWith(".pb")) {
                throw InvalidCommandException("Proto file (ending with .pb) required, " +
                        "got $name instead")
            }
            return name
        }

        private fun validateLegacyViewerConfigFilePath(name: String): String {
            if (!name.endsWith(".json.gz")) {
                throw InvalidCommandException("GZiped Json file (ending with .json.gz) required, " +
                        "got $name instead")
            }
            return name
        }

        private fun validateOutputFilePath(name: String): String {
            if (!name.endsWith(".winscope")) {
                throw InvalidCommandException("Winscope file (ending with .winscope) required, " +
                        "got $name instead")
            }
            return name
        }

        private fun validateConfigFileName(name: String): String {
            if (!name.endsWith(".json") && !name.endsWith(".pb")) {
                throw InvalidCommandException("Json file (ending with .json) or proto file " +
                        "(ending with .pb) required, got $name instead")
            }
            return name
        }

        private fun validateConfigType(name: String): String {
            val validType = listOf("json", "proto")
            if (!validType.contains(name)) {
                throw InvalidCommandException("Unexpected config file type. " +
                        "Expected on of [${validType.joinToString()}], but got $name")
            }
            return name
        }

        private fun validateJavaInputList(list: List<String>): List<String> {
            if (list.isEmpty()) {
                throw InvalidCommandException("No java source input files")
            }
            list.forEach { name ->
                if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                    throw InvalidCommandException("Not a java or kotlin source file $name")
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
    val protoLogGroupsJarArg: String
    val viewerConfigFileNameArg: String
    val viewerConfigTypeArg: String
    val outputSourceJarArg: String
    val logProtofileArg: String
    val viewerConfigFilePathArg: String
    val legacyViewerConfigFilePathArg: String?
    val legacyOutputFilePath: String?
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
                protoLogGroupsClassNameArg =
                    validateClassName(getParam(PROTOLOGGROUP_CLASS_PARAM, params))
                protoLogGroupsJarArg = validateJarName(getParam(PROTOLOGGROUP_JAR_PARAM, params))
                viewerConfigFileNameArg = validateNotSpecified(VIEWER_CONFIG_PARAM, params)
                viewerConfigTypeArg = validateNotSpecified(VIEWER_CONFIG_TYPE_PARAM, params)
                outputSourceJarArg = validateSrcJarName(getParam(OUTPUT_SOURCE_JAR_PARAM, params))
                viewerConfigFilePathArg = validateViewerConfigFilePath(
                    getParam(VIEWER_CONFIG_FILE_PATH_PARAM, params))
                legacyViewerConfigFilePathArg =
                    getOptionalParam(LEGACY_VIEWER_CONFIG_FILE_PATH_PARAM, params)?.let {
                        validateLegacyViewerConfigFilePath(it)
                    }
                legacyOutputFilePath =
                    getOptionalParam(LEGACY_OUTPUT_FILE_PATH, params)?.let {
                        validateOutputFilePath(it)
                    }
                javaSourceArgs = validateJavaInputList(inputFiles)
                logProtofileArg = ""
            }
            GENERATE_CONFIG_CMD -> {
                protoLogClassNameArg = validateClassName(getParam(PROTOLOG_CLASS_PARAM, params))
                protoLogGroupsClassNameArg =
                    validateClassName(getParam(PROTOLOGGROUP_CLASS_PARAM, params))
                protoLogGroupsJarArg = validateJarName(getParam(PROTOLOGGROUP_JAR_PARAM, params))
                viewerConfigFileNameArg =
                    validateConfigFileName(getParam(VIEWER_CONFIG_PARAM, params))
                viewerConfigTypeArg = validateConfigType(getParam(VIEWER_CONFIG_TYPE_PARAM, params))
                outputSourceJarArg = validateNotSpecified(OUTPUT_SOURCE_JAR_PARAM, params)
                viewerConfigFilePathArg =
                    validateNotSpecified(VIEWER_CONFIG_FILE_PATH_PARAM, params)
                legacyViewerConfigFilePathArg =
                    validateNotSpecified(LEGACY_VIEWER_CONFIG_FILE_PATH_PARAM, params)
                legacyOutputFilePath = validateNotSpecified(LEGACY_OUTPUT_FILE_PATH, params)
                javaSourceArgs = validateJavaInputList(inputFiles)
                logProtofileArg = ""
            }
            READ_LOG_CMD -> {
                protoLogClassNameArg = validateNotSpecified(PROTOLOG_CLASS_PARAM, params)
                protoLogGroupsClassNameArg = validateNotSpecified(PROTOLOGGROUP_CLASS_PARAM, params)
                protoLogGroupsJarArg = validateNotSpecified(PROTOLOGGROUP_JAR_PARAM, params)
                viewerConfigFileNameArg =
                    validateConfigFileName(getParam(VIEWER_CONFIG_PARAM, params))
                viewerConfigTypeArg = validateNotSpecified(VIEWER_CONFIG_TYPE_PARAM, params)
                outputSourceJarArg = validateNotSpecified(OUTPUT_SOURCE_JAR_PARAM, params)
                viewerConfigFilePathArg =
                    validateNotSpecified(VIEWER_CONFIG_FILE_PATH_PARAM, params)
                legacyViewerConfigFilePathArg =
                    validateNotSpecified(LEGACY_VIEWER_CONFIG_FILE_PATH_PARAM, params)
                legacyOutputFilePath = validateNotSpecified(LEGACY_OUTPUT_FILE_PATH, params)
                javaSourceArgs = listOf()
                logProtofileArg = validateLogInputList(inputFiles)
            }
            else -> {
                throw InvalidCommandException("Unknown command.")
            }
        }
    }
}
