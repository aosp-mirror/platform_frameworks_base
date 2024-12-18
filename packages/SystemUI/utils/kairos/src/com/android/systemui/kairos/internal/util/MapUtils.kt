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

package com.android.systemui.kairos.internal.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield

// TODO: It's possible that this is less efficient than having each coroutine directly insert into a
//  ConcurrentHashMap, but then we would lose ordering
internal suspend inline fun <K, A, B : Any, M : MutableMap<K, B>> Map<K, A>
    .mapValuesNotNullParallelTo(
    destination: M,
    crossinline block: suspend (Map.Entry<K, A>) -> B?,
): M =
    destination.also {
        coroutineScope {
                mapValues {
                    async {
                        yield()
                        block(it)
                    }
                }
            }
            .mapValuesNotNullTo(it) { (_, deferred) -> deferred.await() }
    }

internal inline fun <K, A, B : Any, M : MutableMap<K, B>> Map<K, A>.mapValuesNotNullTo(
    destination: M,
    block: (Map.Entry<K, A>) -> B?,
): M =
    destination.also {
        for (entry in this@mapValuesNotNullTo) {
            block(entry)?.let { destination.put(entry.key, it) }
        }
    }

internal suspend fun <A, B> Iterable<A>.mapParallel(transform: suspend (A) -> B): List<B> =
    coroutineScope {
        map { async(start = CoroutineStart.LAZY) { transform(it) } }.awaitAll()
    }

internal suspend fun <K, A, B, M : MutableMap<K, B>> Map<K, A>.mapValuesParallelTo(
    destination: M,
    transform: suspend (Map.Entry<K, A>) -> B,
): Map<K, B> = entries.mapParallel { it.key to transform(it) }.toMap(destination)

internal suspend fun <K, A, B> Map<K, A>.mapValuesParallel(
    transform: suspend (Map.Entry<K, A>) -> B
): Map<K, B> = mapValuesParallelTo(mutableMapOf(), transform)
