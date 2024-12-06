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

package com.android.systemui.qs.tiles.base.interactor

import android.annotation.WorkerThread
import com.android.systemui.plugins.qs.TileDetailsViewModel

interface QSTileUserActionInteractor<DATA_TYPE> {
    /**
     * Processes user input based on [QSTileInput.userId], [QSTileInput.action], and
     * [QSTileInput.data]. It's guaranteed that [QSTileInput.userId] is the same as the id passed to
     * [QSTileDataInteractor] to get [QSTileInput.data].
     *
     * It's safe to run long running computations inside this function.
     */
    @WorkerThread suspend fun handleInput(input: QSTileInput<DATA_TYPE>)

    /**
     * Provides the [TileDetailsViewModel] for constructing the corresponding details view.
     *
     * This property is defined here to reuse the business logic. For example, reusing the user
     * long-click as the go-to-settings callback in the details view.
     * Subclasses can override this property to provide a specific [TileDetailsViewModel]
     * implementation.
     *
     * @return The [TileDetailsViewModel] instance, or null if not implemented.
     */
    val detailsViewModel: TileDetailsViewModel?
        get() = null
}
