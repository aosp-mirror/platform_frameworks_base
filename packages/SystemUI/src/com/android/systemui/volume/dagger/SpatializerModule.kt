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

package com.android.systemui.volume.dagger

import android.media.AudioManager
import android.media.Spatializer
import com.android.settingslib.media.data.repository.SpatializerRepository
import com.android.settingslib.media.data.repository.SpatializerRepositoryImpl
import com.android.settingslib.media.domain.interactor.SpatializerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import dagger.Module
import dagger.Provides
import kotlin.coroutines.CoroutineContext

/** Spatializer module. */
@Module
interface SpatializerModule {

    companion object {

        @Provides
        @SysUISingleton
        fun provideSpatializer(
            audioManager: AudioManager,
        ): Spatializer = audioManager.spatializer

        @Provides
        @SysUISingleton
        fun provdieSpatializerRepository(
            spatializer: Spatializer,
            @Background backgroundContext: CoroutineContext,
        ): SpatializerRepository = SpatializerRepositoryImpl(spatializer, backgroundContext)

        @Provides
        @SysUISingleton
        fun provideSpatializerInetractor(repository: SpatializerRepository): SpatializerInteractor =
            SpatializerInteractor(repository)
    }
}
