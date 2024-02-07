/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.dagger

import android.view.accessibility.CaptioningManager
import com.android.settingslib.view.accessibility.data.repository.CaptioningRepository
import com.android.settingslib.view.accessibility.data.repository.CaptioningRepositoryImpl
import com.android.settingslib.view.accessibility.domain.interactor.CaptioningInteractor
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import dagger.Module
import dagger.Provides
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

@Module
interface CaptioningModule {

    companion object {

        @Provides
        fun provideCaptioningRepository(
            captioningManager: CaptioningManager,
            @Background coroutineContext: CoroutineContext,
            @Application coroutineScope: CoroutineScope,
        ): CaptioningRepository =
            CaptioningRepositoryImpl(captioningManager, coroutineContext, coroutineScope)

        @Provides
        fun provideCaptioningInteractor(repository: CaptioningRepository): CaptioningInteractor =
            CaptioningInteractor(repository)
    }
}
