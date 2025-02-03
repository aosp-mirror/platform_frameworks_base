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
import android.content.Intent
import android.os.Bundle
import androidx.annotation.AnyThread
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Interface provides preference metadata (title, summary, icon, etc.).
 *
 * Besides the existing APIs, subclass could integrate with following interface to provide more
 * information:
 * - [PreferenceTitleProvider]: provide dynamic title content
 * - [PreferenceSummaryProvider]: provide dynamic summary content (e.g. based on preference value)
 * - [PreferenceIconProvider]: provide dynamic icon content (e.g. based on flag)
 * - [PreferenceAvailabilityProvider]: provide preference availability (e.g. based on flag)
 * - [PreferenceLifecycleProvider]: provide the lifecycle callbacks and notify state change
 *
 * Notes:
 * - UI framework support:
 *     - This class does not involve any UI logic, it is the data layer.
 *     - Subclass could integrate with datastore and UI widget to provide UI layer. For instance,
 *       `PreferenceBinding` supports Jetpack Preference binding.
 * - Datastore:
 *     - Subclass should implement the [PersistentPreference] to note that current preference is
 *       persistent in datastore.
 *     - It is always recommended to support back up preference value changed by user. Typically,
 *       the back up and restore happen within datastore, the [allowBackup] API is to mark if
 *       current preference value should be backed up (backup allowed by default).
 * - Preference indexing for search:
 *     - Override [isIndexable] API to mark if preference is indexable (enabled by default).
 *     - If [isIndexable] returns true, preference title and summary will be indexed with cache.
 *       More indexing data could be provided through [keywords].
 *     - Settings search will cache the preference title/summary/keywords for indexing. The cache is
 *       invalidated when system locale changed, app upgraded, etc.
 *     - Dynamic content is not suitable to be cached for indexing. Subclass that implements
 *       [PreferenceTitleProvider] / [PreferenceSummaryProvider] will not have its title / summary
 *       indexed.
 */
@AnyThread
interface PreferenceMetadata {

    /** Preference key. */
    val key: String

    /**
     * Preference title resource id.
     *
     * Implement [PreferenceTitleProvider] if title is generated dynamically.
     */
    val title: Int
        @StringRes get() = 0

    /**
     * Preference summary resource id.
     *
     * Implement [PreferenceSummaryProvider] if summary is generated dynamically (e.g. summary is
     * provided per preference value)
     */
    val summary: Int
        @StringRes get() = 0

    /** Icon of the preference. */
    val icon: Int
        @DrawableRes get() = 0

    /** Additional keywords for indexing. */
    val keywords: Int
        @StringRes get() = 0

    /**
     * Return the extras Bundle object associated with this preference.
     *
     * It is used to provide more *internal* information for metadata. External app is not expected
     * to use this information as it could be changed in future. Consider [tags] for external usage.
     */
    fun extras(context: Context): Bundle? = null

    /**
     * Returns the tags associated with this preference.
     *
     * Unlike [extras], tags are exposed for external usage. The returned tag list must be constants
     * and **append only**. Do not edit/delete existing tag strings as it can cause backward
     * compatibility issue.
     *
     * Use cases:
     * - identify a specific preference
     * - identify a group of preferences related to network settings
     */
    fun tags(context: Context): Array<String> = arrayOf()

    /**
     * Returns if preference is indexable, default value is `true`.
     *
     * Return `false` only when the preference is always unavailable on current device. If it is
     * conditional available, override [PreferenceAvailabilityProvider].
     */
    fun isIndexable(context: Context): Boolean = true

    /**
     * Returns if preference is enabled.
     *
     * UI framework normally does not allow user to interact with the preference widget when it is
     * disabled.
     */
    fun isEnabled(context: Context): Boolean = true

    /** Returns the keys of depended preferences. */
    fun dependencies(context: Context): Array<String> = arrayOf()

    /** Returns if the preference is persistent in datastore. */
    fun isPersistent(context: Context): Boolean = this is PersistentPreference<*>

    /**
     * Returns if preference value backup is allowed (by default returns `true` if preference is
     * persistent).
     */
    fun allowBackup(context: Context): Boolean = isPersistent(context)

    /** Returns preference intent. */
    fun intent(context: Context): Intent? = null

    /**
     * Returns the preference title.
     *
     * Implement [PreferenceTitleProvider] interface if title content is generated dynamically.
     */
    fun getPreferenceTitle(context: Context): CharSequence? =
        when {
            title != 0 -> context.getText(title)
            this is PreferenceTitleProvider -> getTitle(context)
            else -> null
        }

    /**
     * Returns the preference summary.
     *
     * Implement [PreferenceSummaryProvider] interface if summary content is generated dynamically
     * (e.g. summary is provided per preference value).
     */
    fun getPreferenceSummary(context: Context): CharSequence? =
        when {
            summary != 0 -> context.getText(summary)
            this is PreferenceSummaryProvider -> getSummary(context)
            else -> null
        }

    /**
     * Returns the preference icon.
     *
     * Implement [PreferenceIconProvider] interface if icon is provided dynamically (e.g. icon is
     * provided based on flag value).
     */
    fun getPreferenceIcon(context: Context): Int =
        when {
            icon != 0 -> icon
            this is PreferenceIconProvider -> getIcon(context)
            else -> 0
        }
}

/** Metadata of preference group. */
@AnyThread interface PreferenceGroup : PreferenceMetadata

/** Metadata of preference category. */
@AnyThread
open class PreferenceCategory(override val key: String, override val title: Int) : PreferenceGroup
