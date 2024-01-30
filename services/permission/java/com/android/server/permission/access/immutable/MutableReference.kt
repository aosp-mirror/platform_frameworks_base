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

package com.android.server.permission.access.immutable

/**
 * Wrapper class for reference to a mutable data structure instance.
 *
 * This class encapsulates the logic to mutate/copy a mutable data structure instance and update the
 * reference to the new mutated instance. It also remembers the mutated instance so that it can be
 * reused during further mutations.
 *
 * Instances of this class should be kept private within a data structure, with the [get] method
 * exposed on the immutable interface of the data structure as a `getFoo` method, and the [mutate]
 * method exposed on the mutable interface of the data structure as a `mutateFoo` method. When the
 * data structure is mutated/copied, a new instance of this class should be obtained with
 * [toImmutable], which makes the wrapped reference immutable-only again and thus prevents further
 * modifications to a data structure accessed with its immutable interface.
 *
 * @see MutableIndexedReferenceMap
 * @see MutableIntReferenceMap
 */
class MutableReference<I : Immutable<M>, M : I>
private constructor(private var immutable: I, private var mutable: M?) {
    constructor(mutable: M) : this(mutable, mutable)

    /** Return an immutable reference to the wrapped mutable data structure. */
    fun get(): I = immutable

    /**
     * Make the wrapped mutable data structure mutable, by either calling [Immutable.toMutable] and
     * replacing the wrapped reference with its result, or reusing the existing reference if it's
     * already mutable.
     */
    fun mutate(): M {
        mutable?.let {
            return it
        }
        return immutable.toMutable().also {
            immutable = it
            mutable = it
        }
    }

    /**
     * Create a new [MutableReference] instance with the wrapped mutable data structure being
     * immutable-only again.
     */
    fun toImmutable(): MutableReference<I, M> = MutableReference(immutable, null)

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as MutableReference<*, *>
        return immutable == other.immutable
    }

    override fun hashCode(): Int = immutable.hashCode()
}
