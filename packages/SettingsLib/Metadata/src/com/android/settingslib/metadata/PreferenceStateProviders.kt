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
     * Called when preference is attached to UI.
     *
     * Subclass could override this API to register runtime condition listeners, and invoke
     * `onPreferenceStateChanged(this)` on the given [preferenceStateObserver] to update UI when
     * internal state (e.g. availability, enabled state, title, summary) is changed.
     */
    fun onAttach(context: Context, preferenceStateObserver: PreferenceStateObserver)

    /**
     * Called when preference is detached from UI.
     *
     * Clean up and release resource.
     */
    fun onDetach(context: Context)

    /** Observer of preference state. */
    interface PreferenceStateObserver {

        /** Callbacks when preference state is changed. */
        fun onPreferenceStateChanged(preference: PreferenceMetadata)
    }
}
