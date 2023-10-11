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

package com.android.systemui.communal.domain.interactor

import android.provider.Settings
import com.android.systemui.communal.data.repository.CommunalTutorialRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Encapsulates business-logic related to communal tutorial state. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalTutorialInteractor
@Inject
constructor(
    communalTutorialRepository: CommunalTutorialRepository,
    keyguardInteractor: KeyguardInteractor,
) {
    /** An observable for whether the tutorial is available. */
    val isTutorialAvailable: Flow<Boolean> =
        combine(
                keyguardInteractor.isKeyguardVisible,
                communalTutorialRepository.tutorialSettingState,
            ) { isKeyguardVisible, tutorialSettingState ->
                isKeyguardVisible &&
                    tutorialSettingState != Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
            }
            .distinctUntilChanged()
}
