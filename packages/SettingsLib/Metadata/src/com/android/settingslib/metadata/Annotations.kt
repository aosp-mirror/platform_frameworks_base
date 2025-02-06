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

/**
 * Annotation to provide preference screen.
 *
 * The annotated class must satisfy either condition:
 * - the primary constructor has no parameter
 * - the primary constructor has a single [android.content.Context] parameter
 * - (parameterized) the primary constructor has a single [android.os.Bundle] parameter to override
 *   [PreferenceScreenMetadata.arguments]
 * - (parameterized) the primary constructor has a [android.content.Context] and a
 *   [android.os.Bundle] parameter to override [PreferenceScreenMetadata.arguments]
 *
 * @param value unique preference screen key
 * @param overlay if true, current annotated screen will overlay the screen that has identical key
 * @param parameterized if true, the screen relies on additional arguments to build its content
 * @param parameterizedMigration whether the parameterized screen was a normal screen, in which case
 *   `Bundle.EMPTY` will be passed as arguments to take care of backward compatibility
 * @see PreferenceScreenMetadata
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class ProvidePreferenceScreen(
    val value: String,
    val overlay: Boolean = false,
    val parameterized: Boolean = false,
    val parameterizedMigration: Boolean = false, // effective only when parameterized is true
)

/**
 * Provides options for [ProvidePreferenceScreen] annotation processor.
 *
 * @param codegenCollector generated collector class (format: "pkg/class/method"), an empty string
 *   means do not generate code
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class ProvidePreferenceScreenOptions(
    val codegenCollector: String = "com.android.settingslib.metadata/PreferenceScreenCollector/get",
)
