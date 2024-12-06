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

package com.android.settingslib.graph

/** Flags for preference getter operation. */
object PreferenceGetterFlags {
    const val VALUE = 1 shl 0
    const val VALUE_DESCRIPTOR = 1 shl 1
    const val METADATA = 1 shl 2
    const val ALL = (1 shl 3) - 1

    fun Int.includeValue() = (this and VALUE) != 0

    fun Int.includeValueDescriptor() = (this and VALUE_DESCRIPTOR) != 0

    fun Int.includeMetadata() = (this and METADATA) != 0
}
