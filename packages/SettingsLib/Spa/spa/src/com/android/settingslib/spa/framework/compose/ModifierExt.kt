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

package com.android.settingslib.spa.framework.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Sets the content description of this node. */
fun Modifier.contentDescription(contentDescription: String?) =
    if (contentDescription != null) this.semantics {
        this.contentDescription = contentDescription
    } else this

/**
 * Concatenates this modifier with another if `condition` is true.
 *
 * This method allows inline conditional addition of modifiers to a modifier chain. Instead of
 * writing
 *
 * ```
 * val aModifier = Modifier.a()
 * val bModifier = if(condition) aModifier.b() else aModifier
 * Composable(modifier = bModifier)
 * ```
 *
 * You can instead write
 *
 * ```
 * Composable(modifier = Modifier.a().thenIf(condition){
 *   Modifier.b()
 * }
 * ```
 *
 * This makes the modifier chain easier to read.
 *
 * Note that unlike the non-factory version, the conditional modifier is recreated each time, and
 * may never be created at all.
 *
 * @param condition Whether or not to apply the modifiers.
 * @param factory Creates the modifier to concatenate with the current one.
 * @return a Modifier representing this modifier followed by other in sequence.
 * @see Modifier.then
 */
inline fun Modifier.thenIf(condition: Boolean, crossinline factory: () -> Modifier): Modifier =
    if (condition) this.then(factory()) else this
