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

import com.android.settingslib.flags.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.volume.domain.interactor.AudioSharingInteractor
import com.android.systemui.volume.domain.interactor.AudioSharingInteractorEmptyImpl
import com.android.systemui.volume.domain.interactor.AudioSharingInteractorImpl
import dagger.Lazy
import dagger.Module
import dagger.Provides

/** Dagger module for audio sharing code in the volume package */
@Module
interface AudioSharingModule {

    companion object {
        @Provides
        @SysUISingleton
        fun provideAudioSharingInteractor(
            impl: Lazy<AudioSharingInteractorImpl>,
            emptyImpl: Lazy<AudioSharingInteractorEmptyImpl>,
        ): AudioSharingInteractor =
            if (Flags.volumeDialogAudioSharingFix()) {
                impl.get()
            } else {
                emptyImpl.get()
            }
    }
}
