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

package com.android.systemui.notifications.ui.composable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.compose.nestedscroll.PriorityNestedScrollConnection
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun Modifier.stackVerticalOverscroll(
    coroutineScope: CoroutineScope,
    canScrollForward: () -> Boolean,
): Modifier {
    val screenHeight =
        with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val overscrollOffset = remember { Animatable(0f) }
    val stackNestedScrollConnection = remember {
        NotificationStackNestedScrollConnection(
            stackOffset = { overscrollOffset.value },
            canScrollForward = canScrollForward,
            onScroll = { offsetAvailable ->
                coroutineScope.launch {
                    val maxProgress = screenHeight * 0.2f
                    val tilt = 3f
                    var offset =
                        overscrollOffset.value +
                            maxProgress * tanh(x = offsetAvailable / (maxProgress * tilt))
                    offset = max(offset, -1f * maxProgress)
                    overscrollOffset.snapTo(offset)
                }
            },
            onStop = { velocityAvailable ->
                coroutineScope.launch {
                    overscrollOffset.animateTo(
                        targetValue = 0f,
                        initialVelocity = velocityAvailable,
                        animationSpec = tween(),
                    )
                }
            },
        )
    }

    return this.then(
        Modifier.nestedScroll(stackNestedScrollConnection).offset {
            IntOffset(x = 0, y = overscrollOffset.value.roundToInt())
        }
    )
}

fun NotificationStackNestedScrollConnection(
    stackOffset: () -> Float,
    canScrollForward: () -> Boolean,
    onStart: (Float) -> Unit = {},
    onScroll: (Float) -> Unit,
    onStop: (Float) -> Unit = {},
): PriorityNestedScrollConnection {
    return PriorityNestedScrollConnection(
        orientation = Orientation.Vertical,
        canStartPreScroll = { _, _ -> false },
        canStartPostScroll = { offsetAvailable, offsetBeforeStart ->
            offsetAvailable < 0f && offsetBeforeStart < 0f && !canScrollForward()
        },
        canStartPostFling = { velocityAvailable -> velocityAvailable < 0f && !canScrollForward() },
        canContinueScroll = { stackOffset() > 0f },
        canScrollOnFling = true,
        onStart = { offsetAvailable -> onStart(offsetAvailable) },
        onScroll = { offsetAvailable ->
            onScroll(offsetAvailable)
            offsetAvailable
        },
        onStop = { velocityAvailable ->
            onStop(velocityAvailable)
            velocityAvailable
        },
    )
}
