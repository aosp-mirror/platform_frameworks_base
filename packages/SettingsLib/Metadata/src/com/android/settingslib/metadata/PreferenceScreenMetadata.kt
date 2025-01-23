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
import androidx.fragment.app.Fragment
import kotlinx.coroutines.flow.Flow

/**
 * Metadata of preference screen.
 *
 * For parameterized preference screen that relies on additional information (e.g. package name,
 * language code) to build its content, the subclass must:
 * - override [arguments] in constructor
 * - add a static method `fun parameters(context: Context): List<Bundle>` (context is optional) to
 *   provide all possible arguments
 */
@AnyThread
interface PreferenceScreenMetadata : PreferenceMetadata {
    /** Arguments to build the screen content. */
    val arguments: Bundle?
        get() = null

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

/**
 * Factory of [PreferenceScreenMetadata].
 *
 * Annotation processor generates implementation of this interface based on
 * [ProvidePreferenceScreen] when [ProvidePreferenceScreen.parameterized] is `false`.
 */
fun interface PreferenceScreenMetadataFactory {

    /**
     * Creates a new [PreferenceScreenMetadata].
     *
     * @param context application context to create the PreferenceScreenMetadata
     */
    fun create(context: Context): PreferenceScreenMetadata
}

/**
 * Parameterized factory of [PreferenceScreenMetadata].
 *
 * Annotation processor generates implementation of this interface based on
 * [ProvidePreferenceScreen] when [ProvidePreferenceScreen.parameterized] is `true`.
 */
interface PreferenceScreenMetadataParameterizedFactory : PreferenceScreenMetadataFactory {
    override fun create(context: Context) = create(context, Bundle.EMPTY)

    /**
     * Creates a new [PreferenceScreenMetadata] with given arguments.
     *
     * @param context application context to create the PreferenceScreenMetadata
     * @param args arguments to create the screen metadata, [Bundle.EMPTY] is reserved for the
     *   default case when screen is migrated from normal to parameterized
     */
    fun create(context: Context, args: Bundle): PreferenceScreenMetadata

    /**
     * Returns all possible arguments to create [PreferenceScreenMetadata].
     *
     * Note that [Bundle.EMPTY] is a special arguments reserved for backward compatibility when a
     * preference screen was a normal screen but migrated to parameterized screen later:
     * 1. Set [ProvidePreferenceScreen.parameterizedMigration] to `true`, so that the generated
     *    [acceptEmptyArguments] will be `true`.
     * 1. In the original [parameters] implementation, produce a [Bundle.EMPTY] for the default
     *    case.
     *
     * Do not use [Bundle.EMPTY] for other purpose.
     */
    fun parameters(context: Context): Flow<Bundle>

    /**
     * Returns true when the parameterized screen was a normal screen.
     *
     * The [PreferenceScreenMetadata] is expected to accept an empty arguments ([Bundle.EMPTY]) and
     * take care of backward compatibility.
     */
    fun acceptEmptyArguments(): Boolean = false
}
