/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.model

import kotlinx.coroutines.flow.Flow

/**
 * Tracks conditions for auto-adding or removing specific tiles.
 *
 * When creating a new [AutoAddable], it needs to be registered in a [Module] like
 * [BaseAutoAddableModule], for example:
 * ```
 * @Binds
 * @IntoSet
 * fun providesMyAutoAddable(autoAddable: MyAutoAddable): AutoAddable
 * ```
 */
interface AutoAddable {

    /**
     * Signals associated with a particular user indicating whether a particular tile needs to be
     * auto-added or auto-removed.
     */
    fun autoAddSignal(userId: Int): Flow<AutoAddSignal>

    /**
     * Lifecycle for this object. It indicates in which cases [autoAddSignal] should be collected
     */
    val autoAddTracking: AutoAddTracking

    /** Human readable description */
    val description: String
}
