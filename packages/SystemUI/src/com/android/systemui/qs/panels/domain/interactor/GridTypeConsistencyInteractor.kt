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

package com.android.systemui.qs.panels.domain.interactor

import com.android.systemui.qs.pipeline.shared.TileSpec

interface GridTypeConsistencyInteractor {
    /**
     * Given a list of tiles, return the best list of the same tiles (preserving as much order as
     * possible, such that it's consistent with the current layout.
     */
    fun reconcileTiles(tiles: List<TileSpec>): List<TileSpec>
}
