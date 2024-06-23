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
package com.android.systemui.startable

import com.android.systemui.CoreStartable
import kotlin.reflect.KClass

/**
 * Allows a [CoreStartable] to declare that it must be started after its dependencies.
 *
 * This creates a partial, topological ordering. See [com.android.systemui.SystemUIApplication] for
 * how this ordering is enforced at runtime.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Dependencies(vararg val value: KClass<out CoreStartable> = [])
