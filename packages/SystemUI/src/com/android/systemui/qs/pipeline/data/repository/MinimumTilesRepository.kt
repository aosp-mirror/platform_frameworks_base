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

package com.android.systemui.qs.pipeline.data.repository

import android.content.res.Resources
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Provides the minimum number of tiles required in QS. The default number of tiles should be at
 * least this many.
 */
interface MinimumTilesRepository {
    val minNumberOfTiles: Int
}

/**
 * Minimum number of tiles using the corresponding resource. The value will be read once upon
 * creation, as it's not expected to change.
 */
@SysUISingleton
class MinimumTilesResourceRepository @Inject constructor(@Main resources: Resources) :
    MinimumTilesRepository {
    override val minNumberOfTiles: Int =
        resources.getInteger(R.integer.quick_settings_min_num_tiles)
}

/** Provides a fixed minimum number of tiles. */
class MinimumTilesFixedRepository(override val minNumberOfTiles: Int = 0) : MinimumTilesRepository
