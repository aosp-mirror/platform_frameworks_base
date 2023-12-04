/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.visitors

import com.android.hoststubgen.HostStubGenErrors
import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.asm.getVisibility
import com.android.hoststubgen.asm.isStatic

/**
 * Make sure substitution from and to methods have matching definition.
 * (static-ness, visibility.)
 */
fun checkSubstitutionMethodCompatibility(
    classes: ClassNodes,
    className: String,
    fromMethodName: String, // the one with the annotation
    toMethodName: String, // the one with either a "_host" or "$ravenwood" prefix. (typically)
    descriptor: String,
    errors: HostStubGenErrors,
): Boolean {
    val from = classes.findMethod(className, fromMethodName, descriptor)
    if (from == null) {
        errors.onErrorFound(
            "Substitution-from method not found: $className.$fromMethodName$descriptor")
        return false
    }
    val to = classes.findMethod(className, toMethodName, descriptor)
    if (to == null) {
        // This shouldn't happen, because the visitor visited this method...
        errors.onErrorFound(
            "Substitution-to method not found: $className.$toMethodName$descriptor")
        return false
    }

    if (from.isStatic() != to.isStatic()) {
        errors.onErrorFound(
            "Substitution method must have matching static-ness: " +
                    "$className.$fromMethodName$descriptor")
        return false
    }
    if (from.getVisibility().ordinal > to.getVisibility().ordinal) {
        errors.onErrorFound(
            "Substitution method cannot have smaller visibility than original: " +
                    "$className.$fromMethodName$descriptor")
        return false
    }

    return true
}
