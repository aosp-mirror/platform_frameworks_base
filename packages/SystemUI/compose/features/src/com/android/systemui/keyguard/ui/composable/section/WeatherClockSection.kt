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

package com.android.systemui.keyguard.ui.composable.section

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneScope
import javax.inject.Inject

/** Provides small clock and large clock composables for the weather clock layout. */
class WeatherClockSection @Inject constructor() {
    @Composable
    fun SceneScope.Time(
        modifier: Modifier = Modifier,
    ) {
        // TODO: compose view
    }

    @Composable
    fun SceneScope.Date(
        modifier: Modifier = Modifier,
    ) {
        // TODO: compose view
    }

    @Composable
    fun SceneScope.Weather(
        modifier: Modifier = Modifier,
    ) {
        // TODO: compose view
    }

    @Composable
    fun SceneScope.DndAlarmStatus(
        modifier: Modifier = Modifier,
    ) {
        // TODO: compose view
    }

    @Composable
    fun SceneScope.Temperature(
        modifier: Modifier = Modifier,
    ) {
        // TODO: compose view
    }
}
