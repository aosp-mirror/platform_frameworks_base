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

package com.android.systemui.qs.dagger

import android.content.Context
import com.android.systemui.qs.dagger.QSScopeModule.Companion.QS_USING_COLLAPSED_LANDSCAPE_MEDIA
import com.android.systemui.qs.dagger.QSScopeModule.Companion.QS_USING_MEDIA_PLAYER
import dagger.Module
import dagger.Provides
import javax.inject.Named

@Module(includes = [QSScopeModule::class])
interface QSSceneModule {

    @Module
    companion object {

        /**  */
        @Provides
        @Named(QS_USING_MEDIA_PLAYER)
        @JvmStatic
        fun providesQSUsingMediaPlayer(context: Context?): Boolean {
            return false
        }

        /**  */
        @Provides
        @Named(QS_USING_COLLAPSED_LANDSCAPE_MEDIA)
        @JvmStatic
        fun providesQSUsingCollapsedLandscapeMedia(context: Context): Boolean {
            return false
        }
    }
}
