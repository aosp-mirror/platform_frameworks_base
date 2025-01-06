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

package com.android.settingslib.spa.testutils

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Collects the first element emitted by this flow within a given timeout.
 *
 * If the flow emits a value within the given timeout, this function returns that value. If the
 * timeout expires before the flow emits any values, this function returns null.
 *
 * This function is similar to [kotlinx.coroutines.flow.firstOrNull], but it adds a timeout to
 * prevent potentially infinite waiting.
 *
 * @param timeMillis The timeout in milliseconds. Defaults to 500 milliseconds.
 * @return The first element emitted by the flow within the timeout, or null if the timeout expires.
 */
suspend fun <T> Flow<T>.firstWithTimeoutOrNull(timeMillis: Long = 500): T? =
    withTimeoutOrNull(timeMillis) { firstOrNull() }

/**
 * Collects elements from this flow for a given time and returns the last emitted element, or null
 * if the flow did not emit any elements.
 *
 * This function is useful when you need to retrieve the last value emitted by a flow within a
 * specific timeframe, but the flow might complete without emitting anything or might not emit a
 * value within the given timeout.
 *
 * @param timeMillis The timeout in milliseconds. Defaults to 500ms.
 * @return The last emitted element, or null if the flow did not emit any elements.
 */
suspend fun <T> Flow<T>.lastWithTimeoutOrNull(timeMillis: Long = 500): T? =
    toListWithTimeout(timeMillis).lastOrNull()

/**
 * Collects elements from this flow into a list with a timeout.
 *
 * This function attempts to collect all elements from the flow and store them in a list. If the
 * collection process takes longer than the specified timeout, the collection is canceled and the
 * function returns the elements collected up to that point.
 *
 * @param timeMillis The timeout duration in milliseconds. Defaults to 500 milliseconds.
 * @return A list containing the collected elements, or an empty list if the timeout was reached
 *   before any elements were collected.
 */
suspend fun <T> Flow<T>.toListWithTimeout(timeMillis: Long = 500): List<T> = buildList {
    withTimeoutOrNull(timeMillis) { toList(this@buildList) }
}
