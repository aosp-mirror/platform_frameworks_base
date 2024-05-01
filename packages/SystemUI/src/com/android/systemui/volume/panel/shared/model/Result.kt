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

package com.android.systemui.volume.panel.shared.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** Models a loadable result */
sealed interface Result<T> {

    /** The data is still loading */
    class Loading<T> : Result<T>

    /** The data is loaded successfully */
    data class Data<T>(val data: T) : Result<T>
}

/** Wraps flow into [Result]. */
fun <T> Flow<T>.wrapInResult(): Flow<Result<T>> = map { Result.Data(it) }

/** Filters only [Result.Data] from the flow. */
fun <T> Flow<Result<T>>.filterData(): Flow<T> = mapNotNull { it as? Result.Data<T> }.map { it.data }
