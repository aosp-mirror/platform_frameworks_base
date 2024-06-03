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

package com.android.systemui.statusbar.notification.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A repository of currently displayed heads up notifications.
 *
 * This repository serves as a boundary between the
 * [com.android.systemui.statusbar.policy.HeadsUpManager] and the modern notifications presentation
 * codebase.
 */
interface HeadsUpRepository {

    /**
     * True if we are exiting the headsUp pinned mode, and some notifications might still be
     * animating out. This is used to keep their view container visible.
     */
    val isHeadsUpAnimatingAway: StateFlow<Boolean>

    /** The heads up row that should be displayed on top. */
    val topHeadsUpRow: Flow<HeadsUpRowRepository?>

    /** Set of currently active top-level heads up rows to be displayed. */
    val activeHeadsUpRows: Flow<Set<HeadsUpRowRepository>>

    fun setHeadsUpAnimatingAway(animatingAway: Boolean)
}
