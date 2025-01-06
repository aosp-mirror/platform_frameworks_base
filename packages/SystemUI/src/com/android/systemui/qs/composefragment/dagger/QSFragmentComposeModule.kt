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

package com.android.systemui.qs.composefragment.dagger

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.qs.flags.QSComposeFragment
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.util.Utils
import dagger.Module
import dagger.Provides
import javax.inject.Named

@Module
interface QSFragmentComposeModule {

    companion object {
        const val QS_USING_MEDIA_PLAYER = "compose_fragment_using_media_player"

        @Provides
        @SysUISingleton
        @Named(QS_USING_MEDIA_PLAYER)
        fun providesUsingMedia(@ShadeDisplayAware context: Context): Boolean {
            return QSComposeFragment.isEnabled && Utils.useQsMediaPlayer(context)
        }

        @Provides
        @SysUISingleton
        @QSFragmentComposeLog
        fun providesQSFragmentComposeViewModelTableLog(
            factory: TableLogBufferFactory
        ): TableLogBuffer {
            return factory.create("QSFragmentComposeViewModel", 200)
        }
    }
}
