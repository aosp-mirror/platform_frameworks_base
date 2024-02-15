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
     * Keep the item in the stub jar file, so tests can use it.
     */
    Stub,

    /**
     * Keep the item in the impl jar file, but not in the stub file. Tests cannot use it directly,
     * but indirectly.
     */
    Keep,

    /**
     * Only used for types. Keep the class in the stub, and also all its members.
     * But each member can have another annotations to override it.
     */
    StubClass,

    /**
     * Only used for types. Keep the class in the impl, not in the stub, and also all its members.
     * But each member can have another annotations to override it.
     */
    KeepClass,

    /**
     * Same as [Stub], but replace it with a "substitution" method. Only usable with methods.
     */
    SubstituteAndStub,

    /**
     * Same as [Keep], but replace it with a "substitution" method. Only usable with methods.
     */
    SubstituteAndKeep,

    /**
     * Only usable with methods. The item will be kept in the impl jar file, but when called,
     * it'll throw.
     */
    Throw,

    /**
     * Only usable with methods. The item will be kept in the impl jar file, but when called,
     * it'll no-op.  Currently only supported for methods returning `void`.
     */
    Ignore,

    /**
     * Remove the item completely.
     */
    Remove;

    val isSubstitute: Boolean
        get() = this == SubstituteAndStub || this == SubstituteAndKeep

    val needsInStub: Boolean
        get() = this == Stub || this == StubClass || this == SubstituteAndStub

    val needsInImpl: Boolean
        get() = this != Remove

    /** Returns whether a policy can be used with classes */
    val isUsableWithClasses: Boolean
        get() {
            return when (this) {
                Stub, StubClass, Keep, KeepClass, Remove -> true
                else -> false
            }
        }

    /** Returns whether a policy can be used with fields. */
    val isUsableWithFields: Boolean
        get() {
            return when (this) {
                Stub, Keep, Remove -> true
                else -> false
            }
        }

    /** Returns whether a policy can be used with methods */
    val isUsableWithMethods: Boolean
        get() {
            return when (this) {
                StubClass, KeepClass -> false
                else -> true
            }
        }

    /** Returns whether a policy is a class-wide one. */
    val isClassWidePolicy: Boolean
        get() {
            return when (this) {
                StubClass, KeepClass -> true
                else -> false
            }
        }

    /** Returns whether a policy is considered supported. */
    val isSupported: Boolean
        get() {
            return when (this) {
                // TODO: handle native method with no substitution as being unsupported
                Stub, StubClass, Keep, KeepClass, SubstituteAndStub, SubstituteAndKeep -> true
                else -> false
            }
        }

    fun getSubstitutionBasePolicy(): FilterPolicy {
        return when (this) {
            SubstituteAndKeep -> Keep
            SubstituteAndStub -> Stub
            else -> this
        }
    }

    /**
     * Convert {Stub,Keep}Class to the corresponding Stub or Keep.
     */
    fun resolveClassWidePolicy(): FilterPolicy {
        return when (this) {
            StubClass -> Stub
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
