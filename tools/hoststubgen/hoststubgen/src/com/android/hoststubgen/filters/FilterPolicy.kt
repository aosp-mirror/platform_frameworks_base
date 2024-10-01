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

enum class FilterPolicy {
    /**
     * Keep the item in the jar file.
     */
    Keep,

    /**
     * Only usable with classes. Keep the class in the jar, and also all its members.
     * Each member can have another policy to override it.
     */
    KeepClass,

    /**
     * Only usable with methods. Replace a method with a "substitution" method.
     */
    Substitute,

    /**
     * Only usable with methods. Redirect a method to a method in the substitution class.
     */
    Redirect,

    /**
     * Only usable with methods. The item will be kept in the impl jar file, but when called,
     * it'll throw.
     */
    Throw,

    /**
     * Only usable with methods. The item will be kept in the impl jar file, but when called,
     * it'll no-op.
     */
    Ignore,

    /**
     * Remove the item completely.
     */
    Remove;

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
                Keep, KeepClass, Remove -> true
                else -> false
            }
        }

    /** Returns whether a policy can be used with fields. */
    val isUsableWithFields: Boolean
        get() {
            return when (this) {
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
                Keep, KeepClass, Substitute, Redirect -> true
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
