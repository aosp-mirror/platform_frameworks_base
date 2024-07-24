/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.util.kotlin

import dagger.Lazy
import java.util.Optional
import kotlin.reflect.KProperty

/**
 * Extension operator that allows developers to use [dagger.Lazy] as a property delegate:
 * ```kotlin
 *    class MyClass @Inject constructor(
 *      lazyDependency: dagger.Lazy<Foo>,
 *    ) {
 *      val dependency: Foo by lazyDependency
 *    }
 * ```
 */
operator fun <T> Lazy<T>.getValue(thisRef: Any?, property: KProperty<*>): T = get()

/**
 * Extension operator that allows developers to use [java.util.Optional] as a nullable property
 * delegate:
 * ```kotlin
 *    class MyClass @Inject constructor(
 *      optionalDependency: Optional<Foo>,
 *    ) {
 *      val dependency: Foo? by optionalDependency
 *    }
 * ```
 */
operator fun <T> Optional<T>.getValue(thisRef: Any?, property: KProperty<*>): T? = getOrNull()
