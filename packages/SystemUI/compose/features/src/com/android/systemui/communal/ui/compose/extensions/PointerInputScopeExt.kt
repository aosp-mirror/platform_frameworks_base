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

package com.android.systemui.communal.ui.compose.extensions

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.coroutineScope

/**
 * Observe taps without consuming them by default, so child elements can still respond to them. Long
 * presses are excluded.
 */
suspend fun PointerInputScope.observeTaps(
    pass: PointerEventPass = PointerEventPass.Initial,
    shouldConsume: Boolean = false,
    onTap: ((Offset) -> Unit)? = null,
) = coroutineScope {
    if (onTap == null) return@coroutineScope
    awaitEachGesture {
        val down = awaitFirstDown(pass = pass)
        if (shouldConsume) down.consume()
        val tapTimeout = viewConfiguration.longPressTimeoutMillis
        val up = withTimeoutOrNull(tapTimeout) { waitForUpOrCancellation(pass = pass) }
        if (up != null) {
            onTap(up.position)
        }
    }
}

/**
 * Detect long press gesture and calls onLongPress when detected. The callback parameter receives an
 * Offset representing the position relative to the containing element.
 */
suspend fun PointerInputScope.detectLongPressGesture(
    pass: PointerEventPass = PointerEventPass.Initial,
    onLongPress: ((Offset) -> Unit),
) = coroutineScope {
    awaitEachGesture {
        val down = awaitFirstDown(pass = pass)
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        // wait for first tap up or long press
        try {
            withTimeout(longPressTimeout) { waitForUpOrCancellation(pass = pass) }
        } catch (_: PointerEventTimeoutCancellationException) {
            // withTimeout throws exception if timeout has passed before block completes
            onLongPress.invoke(down.position)
            consumeUntilUp(pass)
        }
    }
}

/**
 * Consumes all pointer events until nothing is pressed and then returns. This method assumes that
 * something is currently pressed.
 */
private suspend fun AwaitPointerEventScope.consumeUntilUp(
    pass: PointerEventPass = PointerEventPass.Initial
) {
    do {
        val event = awaitPointerEvent(pass = pass)
        event.changes.fastForEach { it.consume() }
    } while (event.changes.fastAny { it.pressed })
}

/** Consume all gestures on the initial pass so that child elements do not receive them. */
suspend fun PointerInputScope.consumeAllGestures() = coroutineScope {
    awaitEachGesture {
        awaitPointerEvent(pass = PointerEventPass.Initial)
            .changes
            .forEach(PointerInputChange::consume)
    }
}
