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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.coroutineScope

/**
 * Observe taps without actually consuming them, so child elements can still respond to them. Long
 * presses are excluded.
 */
suspend fun PointerInputScope.observeTapsWithoutConsuming(
    pass: PointerEventPass = PointerEventPass.Initial,
    onTap: ((Offset) -> Unit)? = null,
) = coroutineScope {
    if (onTap == null) return@coroutineScope
    awaitEachGesture {
        awaitFirstDown(pass = pass)
        val tapTimeout = viewConfiguration.longPressTimeoutMillis
        val up = withTimeoutOrNull(tapTimeout) { waitForUpOrCancellation(pass = pass) }
        if (up != null) {
            onTap(up.position)
        }
    }
}

/** Consume all gestures on the initial pass so that child elements do not receive them. */
suspend fun PointerInputScope.consumeAllGestures() = coroutineScope {
    awaitEachGesture {
        awaitPointerEvent(pass = PointerEventPass.Initial)
            .changes
            .forEach(PointerInputChange::consume)
    }
}
