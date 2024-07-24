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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandOptionsTest {
    companion object {
        val TEST_JAVA_SRC = listOf(
                "frameworks/base/services/core/java/com/android/server/wm/" +
                        "AccessibilityController.java",
                "frameworks/base/services/core/java/com/android/server/wm/ActivityDisplay.java",
                "frameworks/base/services/core/java/com/android/server/wm/" +
                        "ActivityMetricsLaunchObserver.java"
        )
        private const val TEST_PROTOLOG_CLASS = "com.android.server.wm.ProtoLog"
        private const val TEST_PROTOLOGIMPL_CLASS = "com.android.server.wm.ProtoLogImpl"
        private const val TEST_PROTOLOGCACHE_CLASS = "com.android.server.wm.ProtoLog\$Cache"
        private const val TEST_PROTOLOGGROUP_CLASS = "com.android.internal.protolog.ProtoLogGroup"
        private const val TEST_PROTOLOGGROUP_JAR = "out/soong/.intermediates/frameworks/base/" +
                "services/core/services.core.wm.protologgroups/android_common/javac/" +
                "services.core.wm.protologgroups.jar"
        private const val TEST_VIEWER_CONFIG_FILE_PATH = "/some/viewer/config/file/path.pb"
        private const val TEST_LEGACY_VIEWER_CONFIG_FILE_PATH =
            "/some/viewer/config/file/path.json.gz"
        private const val TEST_LEGACY_OUTPUT_FILE_PATH = "/some/output/file/path.winscope"
        private const val TEST_SRC_JAR = "out/soong/.temp/sbox175955373/" +
                "services.core.wm.protolog.srcjar"
        private const val TEST_VIEWER_JSON = "out/soong/.temp/sbox175955373/" +
                "services.core.wm.protolog.json"
        private const val TEST_LOG = "./test_log.pb"
    }

    @Test
    fun noCommand() {
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(arrayOf())
        }
        assertThat(exception).hasMessageThat().contains("No command specified")
    }

    @Test
    fun invalidCommand() {
        val testLine = "invalid"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("Unknown command")
    }

    @Test
    fun transformClasses() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.TRANSFORM_CALLS_CMD, cmd.command)
        assertEquals(TEST_PROTOLOG_CLASS, cmd.protoLogClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_CLASS, cmd.protoLogGroupsClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_JAR, cmd.protoLogGroupsJarArg)
        assertEquals(TEST_VIEWER_CONFIG_FILE_PATH, cmd.viewerConfigFilePathArg)
        assertEquals(TEST_LEGACY_VIEWER_CONFIG_FILE_PATH, cmd.legacyViewerConfigFilePathArg)
        assertEquals(TEST_LEGACY_OUTPUT_FILE_PATH, cmd.legacyOutputFilePath)
        assertEquals(TEST_SRC_JAR, cmd.outputSourceJarArg)
        assertEquals(TEST_JAVA_SRC, cmd.javaSourceArgs)
    }

    @Test
    fun transformClasses_noViewerConfigFile() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("--viewer-config-file-path")
    }

    @Test
    fun transformClasses_noLegacyViewerConfigFile() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.TRANSFORM_CALLS_CMD, cmd.command)
        assertEquals(TEST_PROTOLOG_CLASS, cmd.protoLogClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_CLASS, cmd.protoLogGroupsClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_JAR, cmd.protoLogGroupsJarArg)
        assertEquals(TEST_VIEWER_CONFIG_FILE_PATH, cmd.viewerConfigFilePathArg)
        assertEquals(null, cmd.legacyViewerConfigFilePathArg)
        assertEquals(TEST_LEGACY_OUTPUT_FILE_PATH, cmd.legacyOutputFilePath)
        assertEquals(TEST_SRC_JAR, cmd.outputSourceJarArg)
        assertEquals(TEST_JAVA_SRC, cmd.javaSourceArgs)
    }

    @Test
    fun transformClasses_noLegacyOutputFile() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.TRANSFORM_CALLS_CMD, cmd.command)
        assertEquals(TEST_PROTOLOG_CLASS, cmd.protoLogClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_CLASS, cmd.protoLogGroupsClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_JAR, cmd.protoLogGroupsJarArg)
        assertEquals(TEST_VIEWER_CONFIG_FILE_PATH, cmd.viewerConfigFilePathArg)
        assertEquals(TEST_LEGACY_VIEWER_CONFIG_FILE_PATH, cmd.legacyViewerConfigFilePathArg)
        assertEquals(null, cmd.legacyOutputFilePath)
        assertEquals(TEST_SRC_JAR, cmd.outputSourceJarArg)
        assertEquals(TEST_JAVA_SRC, cmd.javaSourceArgs)
    }

    @Test
    fun transformClasses_noProtoLogClass() {
        val testLine = "transform-protolog-calls " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("--protolog-class")
    }

    @Test
    fun transformClasses_noProtoLogGroupClass() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("--loggroups-class")
    }

    @Test
    fun transformClasses_noProtoLogGroupJar() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("--loggroups-jar")
    }

    @Test
    fun transformClasses_noOutJar() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("--output-srcjar")
    }

    @Test
    fun transformClasses_noJavaInput() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("No java source input files")
    }

    @Test
    fun transformClasses_invalidProtoLogClass() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class invalid " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("class name invalid")
    }

    @Test
    fun transformClasses_invalidProtoLogGroupClass() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class invalid " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("class name invalid")
    }

    @Test
    fun transformClasses_invalidProtoLogGroupJar() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar invalid.txt " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat()
                .contains("Jar file required, got invalid.txt instead")
    }

    @Test
    fun transformClasses_invalidOutJar() {
        val testLine = "transform-protolog-calls " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                "--output-srcjar invalid.pb ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat()
                .contains("Source jar file required, got invalid.pb instead")
    }

    @Test
    fun transformClasses_invalidJavaInput() {
            val testLine = "transform-protolog-calls " +
                    "--protolog-class $TEST_PROTOLOG_CLASS " +
                    "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                    "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                    "--viewer-config-file-path $TEST_VIEWER_CONFIG_FILE_PATH " +
                    "--legacy-viewer-config-file-path $TEST_LEGACY_VIEWER_CONFIG_FILE_PATH " +
                    "--legacy-output-file-path $TEST_LEGACY_OUTPUT_FILE_PATH " +
                    "--output-srcjar $TEST_SRC_JAR invalid.py"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat()
                .contains("Not a java or kotlin source file invalid.py")
    }

    @Test
    fun transformClasses_unknownParam() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--unknown test --protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("--unknown")
    }

    @Test
    fun transformClasses_noValue() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("No value for --loggroups-class")
    }

    @Test
    fun generateConfig_json() {
        val testLine = "generate-viewer-config " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-type json " +
                "--viewer-config $TEST_VIEWER_JSON ${TEST_JAVA_SRC.joinToString(" ")}"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.GENERATE_CONFIG_CMD, cmd.command)
        assertEquals(TEST_PROTOLOG_CLASS, cmd.protoLogClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_CLASS, cmd.protoLogGroupsClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_JAR, cmd.protoLogGroupsJarArg)
        assertEquals(TEST_VIEWER_JSON, cmd.viewerConfigFileNameArg)
        assertEquals(TEST_JAVA_SRC, cmd.javaSourceArgs)
    }

    @Test
    fun generateConfig_proto() {
        val testLine = "generate-viewer-config " +
                "--protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config-type proto " +
                "--viewer-config $TEST_VIEWER_JSON ${TEST_JAVA_SRC.joinToString(" ")}"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.GENERATE_CONFIG_CMD, cmd.command)
        assertEquals(TEST_PROTOLOG_CLASS, cmd.protoLogClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_CLASS, cmd.protoLogGroupsClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_JAR, cmd.protoLogGroupsJarArg)
        assertEquals(TEST_VIEWER_JSON, cmd.viewerConfigFileNameArg)
        assertEquals(TEST_JAVA_SRC, cmd.javaSourceArgs)
    }

    @Test
    fun generateConfig_noViewerConfig() {
        val testLine = "generate-viewer-config --protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                TEST_JAVA_SRC.joinToString(" ")
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("--viewer-config required")
    }

    @Test
    fun generateConfig_invalidViewerConfig() {
        val testLine = "generate-viewer-config --protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-config invalid.yaml ${TEST_JAVA_SRC.joinToString(" ")}"
        val exception = assertThrows<InvalidCommandException>(InvalidCommandException::class.java) {
            CommandOptions(testLine.split(' ').toTypedArray())
        }
        assertThat(exception).hasMessageThat().contains("required, got invalid.yaml instead")
    }

    @Test
    fun readLog() {
        val testLine = "read-log --viewer-config $TEST_VIEWER_JSON $TEST_LOG"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.READ_LOG_CMD, cmd.command)
        assertEquals(TEST_VIEWER_JSON, cmd.viewerConfigFileNameArg)
        assertEquals(TEST_LOG, cmd.logProtofileArg)
    }
}
