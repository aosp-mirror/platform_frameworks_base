/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.temporarydisplay.dagger

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.gesture.SwipeUpGestureLogger
import com.android.systemui.temporarydisplay.chipbar.SwipeChipbarAwayGestureHandler
import dagger.Module
import dagger.Provides

@Module
interface TemporaryDisplayModule {
    companion object {
        @Provides
        @SysUISingleton
        @ChipbarLog
        fun provideChipbarLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("ChipbarLog", 40)
        }

        @Provides
        @SysUISingleton
        fun provideSwipeChipbarAwayGestureHandler(
            mediaTttFlags: MediaTttFlags,
            context: Context,
            displayTracker: DisplayTracker,
            logger: SwipeUpGestureLogger,
        ): SwipeChipbarAwayGestureHandler? {
            return if (mediaTttFlags.isMediaTttDismissGestureEnabled()) {
                SwipeChipbarAwayGestureHandler(context, displayTracker, logger)
            } else {
                null
            }
        }
    }
}
