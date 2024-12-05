/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.platform.test.ravenwood.ravenizer

import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.isAbstract
import com.android.hoststubgen.asm.startsWithAny
import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.log
import org.objectweb.asm.tree.ClassNode
import java.util.regex.Pattern

fun validateClasses(classes: ClassNodes): Boolean {
    var allOk = true
    classes.forEach { allOk = checkClass(it, classes) && allOk }

    return allOk
}

/**
 * Validate a class.
 *
 * - A test class shouldn't extend
 *
 */
fun checkClass(cn: ClassNode, classes: ClassNodes): Boolean {
    if (cn.name.shouldByBypassed()) {
        // Class doesn't need to be checked.
        return true
    }
    var allOk = true

    log.v("Checking ${cn.name.toHumanReadableClassName()}")

    // See if there's any class that extends a legacy base class.
    // But ignore the base classes in android.test.
    if (!cn.isAbstract() && !cn.name.startsWith("android/test/")
        && !isAllowListedLegacyTest(cn)
        ) {
        allOk = checkSuperClassForJunit3(cn, cn, classes) && allOk
    }
    return allOk
}

fun checkSuperClassForJunit3(
    targetClass: ClassNode,
    currentClass: ClassNode,
    classes: ClassNodes,
): Boolean {
    if (currentClass.superName == null || currentClass.superName == "java/lang/Object") {
        return true // No parent class
    }
    // Make sure the class doesn't extend a junit3 TestCase class.
    if (currentClass.superName.isLegacyTestBaseClass()) {
        log.e("Error: Class ${targetClass.name.toHumanReadableClassName()} extends"
                + " a legacy test class ${currentClass.superName.toHumanReadableClassName()}"
                + ", which is not supported on Ravenwood. Please migrate to Junit4 syntax.")
        return false
    }
    classes.findClass(currentClass.superName)?.let {
        return checkSuperClassForJunit3(targetClass, it, classes)
    }
    // Super class not found.
    // log.w("Class ${currentClass.superName} not found.")
    return true
}

/**
 * Check if a class internal name is a known legacy test base class.
 */
fun String.isLegacyTestBaseClass(): Boolean {
    return this.startsWithAny(
        "junit/framework/TestCase",

        // In case the test doesn't statically include JUnit, we need the following.
        "android/test/AndroidTestCase",
        "android/test/InstrumentationTestCase",
        "android/test/InstrumentationTestSuite",
    )
}

private val allowListedLegacyTests = setOf(
// List of existing test classes that use the JUnit3 syntax. We exempt them for now, but
// will reject any more of them.
//
// Note, we want internal class names, but for convenience, we use '.'s and '%'s here
// and replace them later. (a '$' would be parsed as a string template.)
    *"""
android.util.proto.cts.DebuggingTest
android.util.proto.cts.EncodedBufferTest
android.util.proto.cts.ProtoOutputStreamBoolTest
android.util.proto.cts.ProtoOutputStreamBytesTest
android.util.proto.cts.ProtoOutputStreamDoubleTest
android.util.proto.cts.ProtoOutputStreamEnumTest
android.util.proto.cts.ProtoOutputStreamFixed32Test
android.util.proto.cts.ProtoOutputStreamFixed64Test
android.util.proto.cts.ProtoOutputStreamFloatTest
android.util.proto.cts.ProtoOutputStreamInt32Test
android.util.proto.cts.ProtoOutputStreamInt64Test
android.util.proto.cts.ProtoOutputStreamObjectTest
android.util.proto.cts.ProtoOutputStreamSFixed32Test
android.util.proto.cts.ProtoOutputStreamSFixed64Test
android.util.proto.cts.ProtoOutputStreamSInt32Test
android.util.proto.cts.ProtoOutputStreamSInt64Test
android.util.proto.cts.ProtoOutputStreamStringTest
android.util.proto.cts.ProtoOutputStreamSwitchedWriteTest
android.util.proto.cts.ProtoOutputStreamTagTest
android.util.proto.cts.ProtoOutputStreamUInt32Test
android.util.proto.cts.ProtoOutputStreamUInt64Test

android.os.cts.BadParcelableExceptionTest
android.os.cts.DeadObjectExceptionTest
android.os.cts.ParcelFormatExceptionTest
android.os.cts.PatternMatcherTest
android.os.cts.RemoteExceptionTest

android.os.storage.StorageManagerBaseTest
android.os.storage.StorageManagerIntegrationTest
android.util.LogTest%PerformanceTest

com.android.server.power.stats.BatteryStatsCounterTest
com.android.server.power.stats.BatteryStatsDualTimerTest
com.android.server.power.stats.BatteryStatsDurationTimerTest
com.android.server.power.stats.BatteryStatsSamplingTimerTest
com.android.server.power.stats.BatteryStatsStopwatchTimerTest
com.android.server.power.stats.BatteryStatsTimeBaseTest
com.android.server.power.stats.BatteryStatsTimerTest

    """.trim().replace('%', '$').replace('.', '/')
        .split(Pattern.compile("""\s+""")).toTypedArray()
)

private fun isAllowListedLegacyTest(targetClass: ClassNode): Boolean {
    return allowListedLegacyTests.contains(targetClass.name)
}