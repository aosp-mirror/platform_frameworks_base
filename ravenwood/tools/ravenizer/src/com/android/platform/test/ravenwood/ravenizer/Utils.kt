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
import com.android.hoststubgen.asm.findAnyAnnotation
import org.objectweb.asm.Type

val junitTestMethodType = Type.getType(org.junit.Test::class.java)
val junitRunWithType = Type.getType(org.junit.runner.RunWith::class.java)

val junitTestMethodDescriptor = junitTestMethodType.descriptor
val junitRunWithDescriptor = junitRunWithType.descriptor

val junitTestMethodDescriptors = setOf<String>(junitTestMethodDescriptor)
val junitRunWithDescriptors = setOf<String>(junitRunWithDescriptor)

/**
 * Returns true, if a test looks like it's a test class which needs to be processed.
 */
fun isTestLookingClass(classes: ClassNodes, className: String): Boolean {
    // Similar to  com.android.tradefed.lite.HostUtils.testLoadClass(), except it's more lenient,
    // and accept non-public and/or abstract classes.
    // HostUtils also checks "Suppress" or "SuiteClasses" but this one doesn't.
    // TODO: SuiteClasses may need to be supported.

    val cn = classes.findClass(className) ?: return false

    if (cn.findAnyAnnotation(junitRunWithDescriptors) != null) {
        return true
    }
    cn.methods?.forEach { method ->
        if (method.findAnyAnnotation(junitTestMethodDescriptors) != null) {
            return true
        }
    }
    if (cn.superName == null) {
        return false
    }
    return isTestLookingClass(classes, cn.superName)
}
