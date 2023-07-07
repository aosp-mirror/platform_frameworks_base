/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take

/**
 * Returns a [Flow] whose values are a list which containing the results of applying the given
 * [transform] function to each element in the original flow's list.
 */
inline fun <T, R> Flow<List<T>>.mapItem(crossinline transform: (T) -> R): Flow<List<R>> =
    map { list -> list.map(transform) }

/**
 * Returns a [Flow] whose values are a list which containing the results of asynchronously applying
 * the given [transform] function to each element in the original flow's list.
 */
inline fun <T, R> Flow<List<T>>.asyncMapItem(crossinline transform: (T) -> R): Flow<List<R>> =
    map { list -> list.asyncMap(transform) }

/**
 * Returns a [Flow] whose values are a list containing only elements matching the given [predicate].
 */
inline fun <T> Flow<List<T>>.filterItem(crossinline predicate: (T) -> Boolean): Flow<List<T>> =
    map { list -> list.filter(predicate) }

/**
 * Delays the flow a little bit, wait the other flow's first value.
 */
fun <T1, T2> Flow<T1>.waitFirst(otherFlow: Flow<T2>): Flow<T1> =
    combine(otherFlow.take(1)) { value, _ -> value }
