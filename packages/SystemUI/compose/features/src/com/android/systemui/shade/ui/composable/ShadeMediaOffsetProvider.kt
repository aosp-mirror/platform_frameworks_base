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

package com.android.systemui.shade.ui.composable

import androidx.compose.ui.unit.IntOffset
import com.android.systemui.qs.ui.adapter.QSSceneAdapter

/**
 * Provider for the extra offset for the Media section in the shade to accommodate for the squishing
 * qs or qqs tiles.
 */
interface ShadeMediaOffsetProvider {

    /** Returns current offset to be applied to the Media Carousel */
    val offset: IntOffset

    /**
     * [ShadeMediaOffsetProvider] implementation for Quick Settings.
     *
     * [updateLayout] should represent an access to some state to trigger Compose to relayout to
     * track [QSSceneAdapter] internal state changes during the transition.
     */
    class Qs(private val updateLayout: () -> Unit, private val qsSceneAdapter: QSSceneAdapter) :
        ShadeMediaOffsetProvider {

        override val offset: IntOffset
            get() =
                calculateQsOffset(
                    updateLayout,
                    qsSceneAdapter.qsHeight,
                    qsSceneAdapter.squishedQsHeight
                )
    }

    /**
     * [ShadeMediaOffsetProvider] implementation for Quick Quick Settings.
     *
     * [updateLayout] should represent an access to some state to trigger Compose to relayout to
     * track [QSSceneAdapter] internal state changes during the transition.
     */
    class Qqs(private val updateLayout: () -> Unit, private val qsSceneAdapter: QSSceneAdapter) :
        ShadeMediaOffsetProvider {

        override val offset: IntOffset
            get() =
                calculateQsOffset(
                    updateLayout,
                    qsSceneAdapter.qqsHeight,
                    qsSceneAdapter.squishedQqsHeight
                )
    }

    companion object {

        protected fun calculateQsOffset(
            updateLayout: () -> Unit,
            qsHeight: Int,
            qsSquishedHeight: Int
        ): IntOffset {
            updateLayout()
            val distanceFromBottomToActualBottom = qsHeight - qsSquishedHeight
            return IntOffset(0, -distanceFromBottomToActualBottom)
        }
    }
}
