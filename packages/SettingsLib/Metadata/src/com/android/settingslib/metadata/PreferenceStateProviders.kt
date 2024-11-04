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
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle

/**
 * Interface to provide dynamic preference title.
 *
 * Implement this interface implies that the preference title should not be cached for indexing.
 */
interface PreferenceTitleProvider {

    /** Provides preference title. */
    fun getTitle(context: Context): CharSequence?
}

/**
 * Interface to provide dynamic preference summary.
 *
 * Implement this interface implies that the preference summary should not be cached for indexing.
 */
interface PreferenceSummaryProvider {

    /** Provides preference summary. */
    fun getSummary(context: Context): CharSequence?
}

/**
 * Interface to provide dynamic preference icon.
 *
 * Implement this interface implies that the preference icon should not be cached for indexing.
 */
interface PreferenceIconProvider {

    /** Provides preference icon. */
    fun getIcon(context: Context): Int
}

/**
 * Interface to provide the state of preference availability.
 *
 * UI framework normally does not show the preference widget if it is unavailable.
 */
interface PreferenceAvailabilityProvider {

    /** Returns if the preference is available. */
    fun isAvailable(context: Context): Boolean
}

/**
 * Interface to provide the managed configuration state of the preference.
 *
 * See [Managed configurations](https://developer.android.com/work/managed-configurations) for the
 * Android Enterprise support.
 */
interface PreferenceRestrictionProvider {

    /** Returns if preference is restricted by managed configs. */
    fun isRestricted(context: Context): Boolean
}

/**
 * Preference lifecycle to deal with preference state.
 *
 * Implement this interface when preference depends on runtime conditions.
 */
interface PreferenceLifecycleProvider {

    /**
     * Callbacks of preference fragment `onCreate`.
     *
     * Invoke [PreferenceLifecycleContext.notifyPreferenceChange] to update UI when any internal
     * state (e.g. availability, enabled state, title, summary) is changed.
     */
    fun onCreate(context: PreferenceLifecycleContext) {}

    /**
     * Callbacks of preference fragment `onStart`.
     *
     * Invoke [PreferenceLifecycleContext.notifyPreferenceChange] to update UI when any internal
     * state (e.g. availability, enabled state, title, summary) is changed.
     */
    fun onStart(context: PreferenceLifecycleContext) {}

    /**
     * Callbacks of preference fragment `onResume`.
     *
     * Invoke [PreferenceLifecycleContext.notifyPreferenceChange] to update UI when any internal
     * state (e.g. availability, enabled state, title, summary) is changed.
     */
    fun onResume(context: PreferenceLifecycleContext) {}

    /** Callbacks of preference fragment `onPause`. */
    fun onPause(context: PreferenceLifecycleContext) {}

    /** Callbacks of preference fragment `onStop`. */
    fun onStop(context: PreferenceLifecycleContext) {}

    /** Callbacks of preference fragment `onDestroy`. */
    fun onDestroy(context: PreferenceLifecycleContext) {}

    /**
     * Receives the result from a previous call of
     * [PreferenceLifecycleContext.startActivityForResult].
     *
     * @return true if the result is handled
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean = false
}

/**
 * [Context] for preference lifecycle.
 *
 * A preference fragment is associated with a [PreferenceLifecycleContext] only.
 */
abstract class PreferenceLifecycleContext(context: Context) : ContextWrapper(context) {

    /** Notifies that preference state is changed and update preference widget UI. */
    abstract fun notifyPreferenceChange(preference: PreferenceMetadata)

    /**
     * Starts activity for result, see [android.app.Activity.startActivityForResult].
     *
     * This API can be invoked by any preference, the caller must ensure the request code is unique
     * on the preference screen.
     */
    abstract fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?)
}
