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

package com.android.systemui.media.controls.domain

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataManagerImpl
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataProcessor
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Provider

/** Dagger module for injecting media controls domain interfaces. */
@Module
interface MediaDomainModule {

    @Binds
    @IntoMap
    @ClassKey(MediaCarouselInteractor::class)
    fun bindMediaCarouselInteractor(interactor: MediaCarouselInteractor): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(MediaDataProcessor::class)
    fun bindMediaDataProcessor(interactor: MediaDataProcessor): CoreStartable

    companion object {

        @Provides
        @SysUISingleton
        fun providesMediaDataManager(
            legacyProvider: Provider<LegacyMediaDataManagerImpl>,
            newProvider: Provider<MediaCarouselInteractor>,
        ): MediaDataManager {
            return if (SceneContainerFlag.isEnabled) {
                newProvider.get()
            } else {
                legacyProvider.get()
            }
        }
    }
}
