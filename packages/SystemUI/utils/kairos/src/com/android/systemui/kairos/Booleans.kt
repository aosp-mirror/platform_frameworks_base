/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.kairos

/** Returns a [State] that is `true` only when all of [states] are `true`. */
@ExperimentalKairosApi
fun allOf(vararg states: State<Boolean>): State<Boolean> = combine(*states) { it.allTrue() }

/** Returns a [State] that is `true` when any of [states] are `true`. */
@ExperimentalKairosApi
fun anyOf(vararg states: State<Boolean>): State<Boolean> = combine(*states) { it.anyTrue() }

/** Returns a [State] containing the inverse of the Boolean held by the original [State]. */
@ExperimentalKairosApi fun not(state: State<Boolean>): State<Boolean> = state.mapCheapUnsafe { !it }

private fun Iterable<Boolean>.allTrue() = all { it }

private fun Iterable<Boolean>.anyTrue() = any { it }
