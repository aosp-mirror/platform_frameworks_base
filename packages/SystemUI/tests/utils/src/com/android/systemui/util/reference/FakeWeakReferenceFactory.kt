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

package com.android.systemui.util.reference

import java.lang.ref.WeakReference

class FakeWeakReferenceFactory : WeakReferenceFactory {
    private val managedReferents = mutableListOf<WeakReference<*>>()

    override fun <T> create(referent: T): WeakReference<T> {
        val weakRef = WeakReference(referent)
        managedReferents.add(weakRef)
        return weakRef
    }

    /**
     * Clears any [WeakReference] objects pointing to this object. If argument is null, clears all
     * references.
     */
    fun <T> clear(referent: T) {
        managedReferents.filter { it.get() == referent }.forEach { it.clear() }
    }
}
