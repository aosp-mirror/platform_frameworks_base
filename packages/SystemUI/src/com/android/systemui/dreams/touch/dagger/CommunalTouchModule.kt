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
package com.android.systemui.dreams.touch.dagger

import android.content.res.Resources
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import dagger.Module
import dagger.Provides
import javax.inject.Named

@Module
interface CommunalTouchModule {
    companion object {
        /** Provides the width of the gesture area for swiping open communal hub. */
        @JvmStatic
        @Provides
        @Named(COMMUNAL_GESTURE_INITIATION_WIDTH)
        fun providesCommunalGestureInitiationWidth(@Main resources: Resources): Int {
            return resources.getDimensionPixelSize(R.dimen.communal_gesture_initiation_width)
        }

        /** Width of swipe gesture edge to show communal hub. */
        const val COMMUNAL_GESTURE_INITIATION_WIDTH = "communal_gesture_initiation_width"
    }
}
