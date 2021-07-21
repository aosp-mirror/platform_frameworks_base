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

import org.junit.Assert.assertEquals
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
        private const val TEST_SRC_JAR = "out/soong/.temp/sbox175955373/" +
                "services.core.wm.protolog.srcjar"
        private const val TEST_VIEWER_JSON = "out/soong/.temp/sbox175955373/" +
                "services.core.wm.protolog.json"
        private const val TEST_LOG = "./test_log.pb"
    }

    @Test(expected = InvalidCommandException::class)
    fun noCommand() {
        CommandOptions(arrayOf())
    }

    @Test(expected = InvalidCommandException::class)
    fun invalidCommand() {
        val testLine = "invalid"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test
    fun transformClasses() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.TRANSFORM_CALLS_CMD, cmd.command)
        assertEquals(TEST_PROTOLOG_CLASS, cmd.protoLogClassNameArg)
        assertEquals(TEST_PROTOLOGIMPL_CLASS, cmd.protoLogImplClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_CLASS, cmd.protoLogGroupsClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_JAR, cmd.protoLogGroupsJarArg)
        assertEquals(TEST_SRC_JAR, cmd.outputSourceJarArg)
        assertEquals(TEST_JAVA_SRC, cmd.javaSourceArgs)
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_noProtoLogClass() {
        val testLine = "transform-protolog-calls " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_noProtoLogImplClass() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_noProtoLogCacheClass() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_noProtoLogGroupClass() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_noProtoLogGroupJar() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_noOutJar() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                TEST_JAVA_SRC.joinToString(" ")
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_noJavaInput() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_invalidProtoLogClass() {
        val testLine = "transform-protolog-calls --protolog-class invalid " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_invalidProtoLogImplClass() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class invalid " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_invalidProtoLogCacheClass() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class invalid " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_invalidProtoLogGroupClass() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class invalid " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_invalidProtoLogGroupJar() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar invalid.txt " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_invalidOutJar() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar invalid.db ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_invalidJavaInput() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR invalid.py"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_unknownParam() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--unknown test --protolog-impl-class $TEST_PROTOLOGIMPL_CLASS " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun transformClasses_noValue() {
        val testLine = "transform-protolog-calls --protolog-class $TEST_PROTOLOG_CLASS " +
                "--protolog-impl-class " +
                "--protolog-cache-class $TEST_PROTOLOGCACHE_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--output-srcjar $TEST_SRC_JAR ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test
    fun generateConfig() {
        val testLine = "generate-viewer-config --protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-conf $TEST_VIEWER_JSON ${TEST_JAVA_SRC.joinToString(" ")}"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.GENERATE_CONFIG_CMD, cmd.command)
        assertEquals(TEST_PROTOLOG_CLASS, cmd.protoLogClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_CLASS, cmd.protoLogGroupsClassNameArg)
        assertEquals(TEST_PROTOLOGGROUP_JAR, cmd.protoLogGroupsJarArg)
        assertEquals(TEST_VIEWER_JSON, cmd.viewerConfigJsonArg)
        assertEquals(TEST_JAVA_SRC, cmd.javaSourceArgs)
    }

    @Test(expected = InvalidCommandException::class)
    fun generateConfig_noViewerConfig() {
        val testLine = "generate-viewer-config --protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                TEST_JAVA_SRC.joinToString(" ")
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test(expected = InvalidCommandException::class)
    fun generateConfig_invalidViewerConfig() {
        val testLine = "generate-viewer-config --protolog-class $TEST_PROTOLOG_CLASS " +
                "--loggroups-class $TEST_PROTOLOGGROUP_CLASS " +
                "--loggroups-jar $TEST_PROTOLOGGROUP_JAR " +
                "--viewer-conf invalid.yaml ${TEST_JAVA_SRC.joinToString(" ")}"
        CommandOptions(testLine.split(' ').toTypedArray())
    }

    @Test
    fun readLog() {
        val testLine = "read-log --viewer-conf $TEST_VIEWER_JSON $TEST_LOG"
        val cmd = CommandOptions(testLine.split(' ').toTypedArray())
        assertEquals(CommandOptions.READ_LOG_CMD, cmd.command)
        assertEquals(TEST_VIEWER_JSON, cmd.viewerConfigJsonArg)
        assertEquals(TEST_LOG, cmd.logProtofileArg)
    }
}
