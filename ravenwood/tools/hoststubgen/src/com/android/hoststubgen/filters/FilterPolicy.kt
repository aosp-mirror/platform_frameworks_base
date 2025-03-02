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
package com.android.hoststubgen.filters

enum class FilterPolicy(val policyStringOrPrefix: String) {
    /**
     * Keep the item in the jar file.
     */
    Keep("keep"),

    /**
     * Only usable with classes. Keep the class in the jar, and also all its members.
     * Each member can have another policy to override it.
     */
    KeepClass("keepclass"),

    /**
     * Only usable with methods. Replace a method with a "substitution" method.
     */
    Substitute("@"), // @ is a prefix

    /**
     * Only usable with methods. Redirect a method to a method in the substitution class.
     */
    Redirect("redirect"),

    /**
     * Only usable with methods. The item will be kept in the impl jar file, but when called,
     * it'll throw.
     */
    Throw("throw"),

    /**
     * Only usable with methods. The item will be kept in the impl jar file, but when called,
     * it'll no-op.
     */
    Ignore("ignore"),

    /**
     * Remove the item completely.
     */
    Remove("remove"),

    /**
     * Special policy used for "partial annotation allowlisting". This policy must not be
     * used in the "main" filter chain. (which would be detected by [SanitizationFilter].)
     * It's used in a separate filter chain used by [AnnotationBasedFilter].
     */
    AnnotationAllowed("allow-annotation");

    val needsInOutput: Boolean
        get() {
            return when (this) {
                Remove -> false
                else -> true
            }
        }

    /** Returns whether a policy can be used with classes */
    val isUsableWithClasses: Boolean
        get() {
            return when (this) {
                Keep, KeepClass, Remove, AnnotationAllowed -> true
                else -> false
            }
        }

    /** Returns whether a policy can be used with fields. */
    val isUsableWithFields: Boolean
        get() {
            return when (this) {
                // AnnotationAllowed isn't supported on fields (yet). We could support it if needed.
                Keep, Remove -> true
                else -> false
            }
        }

    /** Returns whether a policy can be used with methods */
    val isUsableWithMethods: Boolean
        get() {
            return when (this) {
                KeepClass -> false
                else -> true
            }
        }

    /** Returns whether a policy can be used as default policy. */
    val isUsableWithDefault: Boolean
        get() {
            return when (this) {
                Keep, Throw, Remove -> true
                else -> false
            }
        }

    /** Returns whether a policy is considered supported. */
    val isSupported: Boolean
        get() {
            return when (this) {
                Keep, KeepClass, Substitute, Redirect, AnnotationAllowed -> true
                else -> false
            }
        }

    val isMethodRewriteBody: Boolean
        get() {
            return when (this) {
                Redirect, Throw, Ignore -> true
                else -> false
            }
        }

    val isClassWide: Boolean
        get() {
            return when (this) {
                Remove, KeepClass -> true
                else -> false
            }
        }

    /**
     * Internal policies must not be used in the main filter chain.
     */
    val isInternalPolicy: Boolean
        get() {
            return when (this) {
                AnnotationAllowed -> true
                else -> false
            }
        }

    /**
     * Convert KeepClass to Keep, or return itself.
     */
    fun resolveClassWidePolicy(): FilterPolicy {
        return when (this) {
            KeepClass -> Keep
            else -> this
        }
    }

    /**
     * Create a [FilterPolicyWithReason] with a given reason.
     */
    fun withReason(reason: String): FilterPolicyWithReason {
        return FilterPolicyWithReason(this, reason)
    }
}
