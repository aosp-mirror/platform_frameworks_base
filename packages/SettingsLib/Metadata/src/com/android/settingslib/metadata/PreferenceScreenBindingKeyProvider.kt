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

import android.content.Context
import android.os.Bundle

/** Provides the associated preference screen key for binding. */
interface PreferenceScreenBindingKeyProvider {

    /** Returns the associated preference screen key. */
    fun getPreferenceScreenBindingKey(context: Context): String?

    /** Returns the arguments to build preference screen. */
    fun getPreferenceScreenBindingArgs(context: Context): Bundle?
}

/** Extra key to provide the preference screen key for binding. */
const val EXTRA_BINDING_SCREEN_KEY = "settingslib:binding_screen_key"

/** Extra key to provide arguments for preference screen binding. */
const val EXTRA_BINDING_SCREEN_ARGS = "settingslib:binding_screen_args"
