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
import androidx.fragment.app.Fragment
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
     * It is used to provide more information for metadata.
     */
    fun extras(context: Context): Bundle? = null

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
     *
     * [dependencyOfEnabledState] is provided to support dependency, the [shouldDisableDependents]
     * value of dependent preference is used to decide enabled state.
     */
    fun isEnabled(context: Context): Boolean {
        val dependency = dependencyOfEnabledState(context) ?: return true
        return !dependency.shouldDisableDependents(context)
    }

    /** Returns the key of depended preference to decide the enabled state. */
    fun dependencyOfEnabledState(context: Context): PreferenceMetadata? = null

    /** Returns whether this preference's dependents should be disabled. */
    fun shouldDisableDependents(context: Context): Boolean = !isEnabled(context)

    /** Returns if the preference is persistent in datastore. */
    fun isPersistent(context: Context): Boolean = this is PersistentPreference<*>

    /**
     * Returns if preference value backup is allowed (by default returns `true` if preference is
     * persistent).
     */
    fun allowBackup(context: Context): Boolean = isPersistent(context)

    /** Returns preference intent. */
    fun intent(context: Context): Intent? = null

    /** Returns preference order. */
    fun order(context: Context): Int? = null

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
}

/** Metadata of preference group. */
@AnyThread
open class PreferenceGroup(override val key: String, override val title: Int) : PreferenceMetadata

/** Metadata of preference screen. */
@AnyThread
interface PreferenceScreenMetadata : PreferenceMetadata {

    /**
     * The screen title resource, which precedes [getScreenTitle] if provided.
     *
     * By default, screen title is same with [title].
     */
    val screenTitle: Int
        get() = title

    /** Returns dynamic screen title, use [screenTitle] whenever possible. */
    fun getScreenTitle(context: Context): CharSequence? = null

    /** Returns the fragment class to show the preference screen. */
    fun fragmentClass(): Class<out Fragment>?

    /**
     * Indicates if [getPreferenceHierarchy] returns a complete hierarchy of the preference screen.
     *
     * If `true`, the result of [getPreferenceHierarchy] will be used to inflate preference screen.
     * Otherwise, it is an intermediate state called hybrid mode, preference hierarchy is
     * represented by other ways (e.g. XML resource) and [PreferenceMetadata]s in
     * [getPreferenceHierarchy] will only be used to bind UI widgets.
     */
    fun hasCompleteHierarchy(): Boolean = true

    /**
     * Returns the hierarchy of preference screen.
     *
     * The implementation MUST include all preferences into the hierarchy regardless of the runtime
     * conditions. DO NOT check any condition (except compile time flag) before adding a preference.
     */
    fun getPreferenceHierarchy(context: Context): PreferenceHierarchy
}
