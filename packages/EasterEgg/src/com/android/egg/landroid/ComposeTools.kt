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

package com.android.egg.landroid

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.random.Random

@Composable fun Dp.toLocalPx() = with(LocalDensity.current) { this@toLocalPx.toPx() }

operator fun Easing.times(next: Easing) = { x: Float -> next.transform(transform(x)) }

fun flickerFadeEasing(rng: Random) = Easing { frac -> if (rng.nextFloat() < frac) 1f else 0f }

val flickerFadeIn =
    fadeIn(
        animationSpec =
            tween(
                durationMillis = 1000,
                easing = CubicBezierEasing(0f, 1f, 1f, 0f) * flickerFadeEasing(Random)
            )
    )
