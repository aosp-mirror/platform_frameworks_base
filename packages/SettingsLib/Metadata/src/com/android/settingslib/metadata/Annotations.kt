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

import kotlin.reflect.KClass

/**
 * Annotation to provide preference screen.
 *
 * The annotated class must satisfy either condition:
 * - the primary constructor has no parameter
 * - the primary constructor has a single [android.content.Context] parameter
 * - it is a Kotlin object class
 *
 * @param overlay if specified, current annotated screen will overlay the given screen
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
annotation class ProvidePreferenceScreen(
    val overlay: KClass<out PreferenceScreenMetadata> = PreferenceScreenMetadata::class,
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
