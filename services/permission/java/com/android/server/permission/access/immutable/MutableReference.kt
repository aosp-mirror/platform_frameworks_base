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

class MutableReference<I : Immutable<M>, M : I> private constructor(
    private var immutable: I,
    private var mutable: M?
) {
    constructor(mutable: M) : this(mutable, mutable)

    fun get(): I = immutable

    fun mutate(): M {
        mutable?.let { return it }
        return immutable.toMutable().also {
            immutable = it
            mutable = it
        }
    }

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
