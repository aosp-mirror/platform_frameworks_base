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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Like [Iterable.flatMap] but executes each [transform] invocation in a separate coroutine. */
suspend fun <A, B> Iterable<A>.flatMapParallel(transform: suspend (A) -> Iterable<B>): List<B> =
    mapParallel(transform).flatten()

/** Like [Iterable.mapNotNull] but executes each [transform] invocation in a separate coroutine. */
suspend fun <A, B> Iterable<A>.mapNotNullParallel(transform: suspend (A) -> B?): List<B> =
    mapParallel(transform).filterNotNull()

/** Like [Iterable.map] but executes each [transform] invocation in a separate coroutine. */
suspend fun <A, B> Iterable<A>.mapParallel(transform: suspend (A) -> B): List<B> = coroutineScope {
    map { async(start = CoroutineStart.LAZY) { transform(it) } }.awaitAll()
}

/** Like [mapValues] but executes each [transform] invocation in a separate coroutine. */
suspend fun <K, A, B> Map<K, A>.mapValuesParallel(
    transform: suspend (Map.Entry<K, A>) -> B
): Map<K, B> = entries.mapParallel { it.key to transform(it) }.toMap()
