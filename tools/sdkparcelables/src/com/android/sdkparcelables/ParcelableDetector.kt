/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.sdkparcelables

/** A class that uses an ancestor map to find all classes that
 * implement android.os.Parcelable, including indirectly through
 * super classes or super interfaces.
 */
class ParcelableDetector {
    companion object {
        fun ancestorsToParcelables(ancestors: Map<String, Ancestors>): List<String> {
            val impl = Impl(ancestors)
            impl.build()
            return impl.parcelables
        }

        const val PARCELABLE_CLASS = "android/os/Parcelable"
    }

    private class Impl(val ancestors: Map<String, Ancestors>) {
        val isParcelableCache = HashMap<String, Boolean>()
        val parcelables = ArrayList<String>()

        fun build() {
            val classList = ancestors.keys
            classList.filterTo(parcelables, { (it != PARCELABLE_CLASS) && isParcelable(it) })
            parcelables.sort()
        }

        private fun isParcelable(c: String?): Boolean {
            if (c == null) {
                return false
            }

            if (c == PARCELABLE_CLASS) {
                return true
            }

            val old = isParcelableCache[c]
            if (old != null) {
                return old
            }

            val cAncestors = ancestors[c] ?:
                    throw RuntimeException("class $c missing ancestor information")

            val seq = (cAncestors.interfaces?.asSequence() ?: emptySequence()) +
                    cAncestors.superName

            val ancestorIsParcelable = seq.any(this::isParcelable)

            isParcelableCache[c] = ancestorIsParcelable
            return ancestorIsParcelable
        }
    }
}
