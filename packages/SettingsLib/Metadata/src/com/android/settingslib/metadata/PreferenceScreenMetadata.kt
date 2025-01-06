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
import androidx.annotation.AnyThread
import androidx.fragment.app.Fragment

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

    /**
     * Returns the [Intent] to show current preference screen.
     *
     * @param metadata the preference to locate when show the screen
     */
    fun getLaunchIntent(context: Context, metadata: PreferenceMetadata?): Intent? = null
}

/** Factory of [PreferenceScreenMetadata]. */
fun interface PreferenceScreenMetadataFactory {

    /**
     * Creates a new [PreferenceScreenMetadata].
     *
     * @param context application context to create the PreferenceScreenMetadata
     */
    fun create(context: Context): PreferenceScreenMetadata
}
