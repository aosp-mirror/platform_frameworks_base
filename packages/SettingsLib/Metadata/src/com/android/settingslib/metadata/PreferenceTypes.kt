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

package com.android.settingslib.metadata

import androidx.annotation.StringRes

/** A persistent preference that has a boolean value. */
interface BooleanValuePreference : PersistentPreference<Boolean> {
    override val valueType: Class<Boolean>
        get() = Boolean::class.javaObjectType
}

/** A persistent preference that has a float value. */
interface FloatValuePreference : PersistentPreference<Float> {
    override val valueType: Class<Float>
        get() = Float::class.javaObjectType
}

/** Common base class for preferences that have two selectable states and save a boolean value. */
interface TwoStatePreference : PreferenceMetadata, BooleanValuePreference

/** A preference that provides a two-state toggleable option. */
open class SwitchPreference
@JvmOverloads
constructor(
    override val key: String,
    @StringRes override val title: Int = 0,
    @StringRes override val summary: Int = 0,
) : TwoStatePreference

/** A preference that provides a two-state toggleable option that can be used as a main switch. */
open class MainSwitchPreference
@JvmOverloads
constructor(override val key: String, @StringRes override val title: Int = 0) : TwoStatePreference
