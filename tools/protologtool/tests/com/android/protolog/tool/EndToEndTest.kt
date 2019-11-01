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

import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.jar.JarInputStream

class EndToEndTest {

    @Test
    fun e2e_transform() {
        val output = run(
                src = "frameworks/base/org/example/Example.java" to """
                    package org.example;
                    import com.android.server.protolog.common.ProtoLog;
                    import static com.android.server.wm.ProtoLogGroup.GROUP;

                    class Example {
                        void method() {
                            String argString = "hello";
                            int argInt = 123;
                            ProtoLog.d(GROUP, "Example: %s %d", argString, argInt);
                        }
                    }
                """.trimIndent(),
                logGroup = LogGroup("GROUP", true, false, "TAG_GROUP"),
                commandOptions = CommandOptions(arrayOf("transform-protolog-calls",
                        "--protolog-class", "com.android.server.protolog.common.ProtoLog",
                        "--protolog-impl-class", "com.android.server.protolog.ProtoLogImpl",
                        "--protolog-cache-class",
                        "com.android.server.protolog.ProtoLog${"\$\$"}Cache",
                        "--loggroups-class", "com.android.server.wm.ProtoLogGroup",
                        "--loggroups-jar", "not_required.jar",
                        "--output-srcjar", "out.srcjar",
                        "frameworks/base/org/example/Example.java"))
        )
        val outSrcJar = assertLoadSrcJar(output, "out.srcjar")
        assertTrue(" 2066303299," in outSrcJar["frameworks/base/org/example/Example.java"]!!)
    }

    @Test
    fun e2e_viewerConfig() {
        val output = run(
                src = "frameworks/base/org/example/Example.java" to """
                    package org.example;
                    import com.android.server.protolog.common.ProtoLog;
                    import static com.android.server.wm.ProtoLogGroup.GROUP;

                    class Example {
                        void method() {
                            String argString = "hello";
                            int argInt = 123;
                            ProtoLog.d(GROUP, "Example: %s %d", argString, argInt);
                        }
                    }
                """.trimIndent(),
                logGroup = LogGroup("GROUP", true, false, "TAG_GROUP"),
                commandOptions = CommandOptions(arrayOf("generate-viewer-config",
                        "--protolog-class", "com.android.server.protolog.common.ProtoLog",
                        "--loggroups-class", "com.android.server.wm.ProtoLogGroup",
                        "--loggroups-jar", "not_required.jar",
                        "--viewer-conf", "out.json",
                        "frameworks/base/org/example/Example.java"))
        )
        val viewerConfigJson = assertLoadText(output, "out.json")
        assertTrue("\"2066303299\"" in viewerConfigJson)
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
        src: Pair<String, String>,
        logGroup: LogGroup,
        commandOptions: CommandOptions
    ): Map<String, ByteArray> {
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()

        ProtoLogTool.injector = object : ProtoLogTool.Injector {
            override fun fileOutputStream(file: String): OutputStream =
                    ByteArrayOutputStream().also { outputs[file] = it }

            override fun readText(file: File): String {
                if (file.path == src.first) {
                    return src.second
                }
                throw FileNotFoundException("expected: ${src.first}, but was $file")
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
