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
package com.android.systemui.statusbar.chips.ui.compose

import android.os.SystemClock
import android.text.format.DateUtils.formatElapsedTime
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.statusbar.chips.ui.compose.modifiers.neverDecreaseWidth
import kotlinx.coroutines.delay

/** Platform-optimized interface for getting current time */
fun interface TimeSource {
    fun getCurrentTime(): Long
}

/** Holds and manages the state for a Chronometer */
class ChronometerState(private val timeSource: TimeSource, private val startTimeMillis: Long) {
    private var currentTimeMillis by mutableLongStateOf(0L)
    private val elapsedTimeMillis: Long
        get() = maxOf(0L, currentTimeMillis - startTimeMillis)

    val currentTimeText: String by derivedStateOf { formatElapsedTime(elapsedTimeMillis / 1000) }

    suspend fun run() {
        while (true) {
            currentTimeMillis = timeSource.getCurrentTime()
            val delaySkewMillis = (currentTimeMillis - startTimeMillis) % 1000L
            delay(1000L - delaySkewMillis)
        }
    }
}

/** Remember and manage the ChronometerState */
@Composable
fun rememberChronometerState(timeSource: TimeSource, startTimeMillis: Long): ChronometerState {
    val state =
        remember(timeSource, startTimeMillis) { ChronometerState(timeSource, startTimeMillis) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, timeSource, startTimeMillis) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { state.run() }
    }

    return state
}

/**
 * A composable chronometer that displays elapsed time with constrained width. The width of the text
 * is only allowed to increase. This ensures there is no visual jitter when individual digits in the
 * text change due to the timer ticking.
 */
@Composable
fun ChronometerText(
    startTimeMillis: Long,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    timeSource: TimeSource = remember { TimeSource { SystemClock.elapsedRealtime() } },
) {
    val state = rememberChronometerState(timeSource, startTimeMillis)
    Text(
        text = state.currentTimeText,
        style = style,
        color = color,
        modifier = modifier.neverDecreaseWidth(),
    )
}
