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
import com.android.hoststubgen.asm.startsWithAny
import com.android.hoststubgen.asm.toHumanReadableClassName
import com.android.hoststubgen.log
import org.objectweb.asm.tree.ClassNode

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

    // See if there's any class that extends a legacy base class.
    // But ignore the base classes in android.test.
    if (!cn.name.startsWithAny("android/test/")) {
        allOk = checkSuperClass(cn, cn, classes) && allOk
    }
    return allOk
}

fun checkSuperClass(targetClass: ClassNode, currentClass: ClassNode, classes: ClassNodes): Boolean {
    if (currentClass.superName == null || currentClass.superName == "java/lang/Object") {
        return true // No parent class
    }
    if (currentClass.superName.isLegacyTestBaseClass()) {
        log.e("Error: Class ${targetClass.name.toHumanReadableClassName()} extends"
                + " a legacy test class ${currentClass.superName.toHumanReadableClassName()}.")
        return false
    }
    classes.findClass(currentClass.superName)?.let {
        return checkSuperClass(targetClass, it, classes)
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

        // In case the test doesn't statically include JUnit, we need
        "android/test/AndroidTestCase",
        "android/test/InstrumentationTestCase",
        "android/test/InstrumentationTestSuite",
    )
}
