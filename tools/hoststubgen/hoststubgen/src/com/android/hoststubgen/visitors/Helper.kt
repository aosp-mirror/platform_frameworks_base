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
import com.android.hoststubgen.asm.clearVisibility
import com.android.hoststubgen.asm.getVisibility
import com.android.hoststubgen.asm.isStatic

const val NOT_COMPATIBLE: Int = -1

/**
 * Make sure substitution from and to methods have matching definition.
 * (static-ness, etc)
 *
 * If the methods are compatible, return the "merged" [access] of the new method.
 *
 * If they are not compatible, returns [NOT_COMPATIBLE]
 */
fun checkSubstitutionMethodCompatibility(
    classes: ClassNodes,
    className: String,
    fromMethodName: String, // the one with the annotation
    toMethodName: String, // the one with either a "_host" or "$ravenwood" prefix. (typically)
    descriptor: String,
    errors: HostStubGenErrors,
): Int {
    val from = classes.findMethod(className, fromMethodName, descriptor)
    if (from == null) {
        errors.onErrorFound(
            "Substitution-from method not found: $className.$fromMethodName$descriptor"
        )
        return NOT_COMPATIBLE
    }
    val to = classes.findMethod(className, toMethodName, descriptor)
    if (to == null) {
        // This shouldn't happen, because the visitor visited this method...
        errors.onErrorFound(
            "Substitution-to method not found: $className.$toMethodName$descriptor"
        )
        return NOT_COMPATIBLE
    }

    if (from.isStatic() != to.isStatic()) {
        errors.onErrorFound(
            "Substitution method must have matching static-ness: " +
                    "$className.$fromMethodName$descriptor"
        )
        return NOT_COMPATIBLE
    }

    // Return the substitution's access flag but with the original method's visibility.
    return clearVisibility (to.access) or getVisibility(from.access)
}
