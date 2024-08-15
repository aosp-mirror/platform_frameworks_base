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

import com.android.protolog.tool.ProtoLogTool.PROTOLOG_IMPL_SRC_PATH
import com.google.common.truth.Truth
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.jar.JarInputStream
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test

class EndToEndTest {

    @Test
    fun e2e_transform() {
        val output = run(
                srcs = mapOf("frameworks/base/org/example/Example.java" to """
                    package org.example;
                    import com.android.internal.protolog.ProtoLog;
                    import static com.android.internal.protolog.ProtoLogGroup.GROUP;

                    class Example {
                        void method() {
                            String argString = "hello";
                            int argInt = 123;
                            ProtoLog.d(GROUP, "Example: %s %d", argString, argInt);
                        }
                    }
                """.trimIndent()),
                logGroup = LogGroup("GROUP", true, false, "TAG_GROUP"),
                commandOptions = CommandOptions(arrayOf("transform-protolog-calls",
                        "--protolog-class", "com.android.internal.protolog.ProtoLog",
                        "--loggroups-class", "com.android.internal.protolog.ProtoLogGroup",
                        "--loggroups-jar", "not_required.jar",
                        "--viewer-config-file-path", "not_required.pb",
                        "--output-srcjar", "out.srcjar",
                        "frameworks/base/org/example/Example.java"))
        )
        val outSrcJar = assertLoadSrcJar(output, "out.srcjar")
        Truth.assertThat(outSrcJar["frameworks/base/org/example/Example.java"])
                .containsMatch(Pattern.compile("\\{ String protoLogParam0 = " +
                        "String\\.valueOf\\(argString\\); long protoLogParam1 = argInt; " +
                        "com\\.android\\.internal\\.protolog.ProtoLogImpl_.*\\.d\\(" +
                        "GROUP, -6872339441335321086L, 4, protoLogParam0, protoLogParam1" +
                        "\\); \\}"))
    }

    @Test
    fun e2e_viewerConfig() {
        val output = run(
                srcs = mapOf("frameworks/base/org/example/Example.java" to """
                    package org.example;
                    import com.android.internal.protolog.ProtoLog;
                    import static com.android.internal.protolog.ProtoLogGroup.GROUP;

                    class Example {
                        void method() {
                            String argString = "hello";
                            int argInt = 123;
                            ProtoLog.d(GROUP, "Example: %s %d", argString, argInt);
                        }
                    }
                """.trimIndent()),
                logGroup = LogGroup("GROUP", true, false, "TAG_GROUP"),
                commandOptions = CommandOptions(arrayOf("generate-viewer-config",
                        "--protolog-class", "com.android.internal.protolog.ProtoLog",
                        "--loggroups-class", "com.android.internal.protolog.ProtoLogGroup",
                        "--loggroups-jar", "not_required.jar",
                        "--viewer-config-type", "json",
                        "--viewer-config", "out.json",
                        "frameworks/base/org/example/Example.java"))
        )
        val viewerConfigJson = assertLoadText(output, "out.json")
        Truth.assertThat(viewerConfigJson).contains("""
            "messages": {
                "-6872339441335321086": {
                  "message": "Example: %s %d",
                  "level": "DEBUG",
                  "group": "GROUP",
                  "at": "org\/example\/Example.java"
                }
              }
        """.trimIndent())
    }

    private fun assertLoadSrcJar(
        outputs: Map<String, ByteArray>,
        path: String
    ): Map<String, String> {
        val out = outputs[path] ?: fail("$path not in outputs (${outputs.keys})")

        val sources = mutableMapOf<String, String>()
        JarInputStream(ByteArrayInputStream(out)).use { jarStream ->
            var entry = jarStream.nextJarEntry
            while (entry != null) {
                if (entry.name.endsWith(".java")) {
                    sources[entry.name] = jarStream.reader().readText()
                }
                entry = jarStream.nextJarEntry
            }
        }
        return sources
    }

    private fun assertLoadText(outputs: Map<String, ByteArray>, path: String): String {
        val out = outputs[path] ?: fail("$path not in outputs (${outputs.keys})")
        return out.toString(Charsets.UTF_8)
    }

    fun run(
        srcs: Map<String, String>,
        logGroup: LogGroup,
        commandOptions: CommandOptions
    ): Map<String, ByteArray> {
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()

        val srcs = srcs.toMutableMap()
        srcs[PROTOLOG_IMPL_SRC_PATH] = """
            package com.android.internal.protolog;

            import static com.android.internal.protolog.common.ProtoLogToolInjected.Value.LEGACY_OUTPUT_FILE_PATH;
            import static com.android.internal.protolog.common.ProtoLogToolInjected.Value.LEGACY_VIEWER_CONFIG_PATH;
            import static com.android.internal.protolog.common.ProtoLogToolInjected.Value.VIEWER_CONFIG_PATH;

            import com.android.internal.protolog.common.ProtoLogToolInjected;

            public class ProtoLogImpl {
                @ProtoLogToolInjected(VIEWER_CONFIG_PATH)
                private static String sViewerConfigPath;

                @ProtoLogToolInjected(LEGACY_VIEWER_CONFIG_PATH)
                private static String sLegacyViewerConfigPath;

                @ProtoLogToolInjected(LEGACY_OUTPUT_FILE_PATH)
                private static String sLegacyOutputFilePath;
            }
        """.trimIndent()

        ProtoLogTool.injector = object : ProtoLogTool.Injector {
            override fun fileOutputStream(file: String): OutputStream =
                    ByteArrayOutputStream().also { outputs[file] = it }

            override fun readText(file: File): String {
                for (src in srcs.entries) {
                    val filePath = src.key
                    if (file.path == filePath) {
                        return src.value
                    }
                }
                throw FileNotFoundException("$file not found in [${srcs.keys.joinToString()}].")
            }

            override fun readLogGroups(jarPath: String, className: String) = mapOf(
                    logGroup.name to logGroup)

            override fun reportParseError(ex: ParsingException) = throw AssertionError(ex)
        }

        ProtoLogTool.invoke(commandOptions)

        return outputs.mapValues { it.value.toByteArray() }
    }

    fun fail(message: String): Nothing = Assert.fail(message) as Nothing
}
