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

package com.android.settingslib.preference

import androidx.preference.Preference
import androidx.preference.PreferenceGroup

/** Traversals preference hierarchy recursively and applies an action. */
fun PreferenceGroup.forEachRecursively(action: (Preference) -> Unit) {
    action.invoke(this)
    for (index in 0 until preferenceCount) {
        val preference = getPreference(index)
        if (preference is PreferenceGroup) {
            preference.forEachRecursively(action)
        } else {
            action.invoke(preference)
        }
    }
}
