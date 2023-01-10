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

package com.android.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.MovableContent
import androidx.compose.runtime.currentComposer

/**
 * An overload of [androidx.compose.runtime.movableContentOf] with 5 parameters.
 *
 * @see androidx.compose.runtime.movableContentOf
 */
@OptIn(InternalComposeApi::class)
fun <P1, P2, P3, P4, P5> movableContentOf(
    content: @Composable (P1, P2, P3, P4, P5) -> Unit
): @Composable (P1, P2, P3, P4, P5) -> Unit {
    val movableContent =
        MovableContent<Pair<Triple<P1, P2, P3>, Pair<P4, P5>>> {
            content(
                it.first.first,
                it.first.second,
                it.first.third,
                it.second.first,
                it.second.second,
            )
        }
    return { p1, p2, p3, p4, p5 ->
        currentComposer.insertMovableContent(movableContent, Triple(p1, p2, p3) to (p4 to p5))
    }
}
