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

package com.android.systemui.motiontool

import android.view.WindowManagerGlobal
import com.android.app.motiontool.DdmHandleMotionTool
import com.android.app.motiontool.MotionToolManager
import com.android.app.viewcapture.ViewCapture
import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface MotionToolModule {

    companion object {

        @Provides
        fun provideDdmHandleMotionTool(motionToolManager: MotionToolManager): DdmHandleMotionTool {
            return DdmHandleMotionTool.getInstance(motionToolManager)
        }

        @Provides
        fun provideMotionToolManager(
            viewCapture: ViewCapture,
            windowManagerGlobal: WindowManagerGlobal
        ): MotionToolManager {
            return MotionToolManager.getInstance(viewCapture, windowManagerGlobal)
        }

        @Provides
        fun provideWindowManagerGlobal(): WindowManagerGlobal = WindowManagerGlobal.getInstance()

        @Provides fun provideViewCapture(): ViewCapture = ViewCapture.getInstance()
    }

    @Binds
    @IntoMap
    @ClassKey(MotionToolStartable::class)
    fun bindMotionToolStartable(impl: MotionToolStartable): CoreStartable
}
