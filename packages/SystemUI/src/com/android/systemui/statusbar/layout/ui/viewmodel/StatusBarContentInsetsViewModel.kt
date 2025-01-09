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

package com.android.systemui.statusbar.layout.ui.viewmodel

import android.graphics.Rect
import com.android.systemui.statusbar.layout.StatusBarContentInsetsChangedListener
import com.android.systemui.statusbar.layout.StatusBarContentInsetsProvider
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/** A recommended architecture version of [StatusBarContentInsetsProvider]. */
class StatusBarContentInsetsViewModel(
    private val statusBarContentInsetsProvider: StatusBarContentInsetsProvider
) {
    /** Emits the status bar content area for the given rotation in absolute bounds. */
    val contentArea: Flow<Rect> =
        conflatedCallbackFlow {
                val listener =
                    object : StatusBarContentInsetsChangedListener {
                        override fun onStatusBarContentInsetsChanged() {
                            trySend(
                                statusBarContentInsetsProvider
                                    .getStatusBarContentAreaForCurrentRotation()
                            )
                        }
                    }
                statusBarContentInsetsProvider.addCallback(listener)
                awaitClose { statusBarContentInsetsProvider.removeCallback(listener) }
            }
            .onStart {
                emit(statusBarContentInsetsProvider.getStatusBarContentAreaForCurrentRotation())
            }
            .distinctUntilChanged()
}
