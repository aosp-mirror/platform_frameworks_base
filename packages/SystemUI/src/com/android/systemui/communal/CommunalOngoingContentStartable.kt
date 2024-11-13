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

package com.android.systemui.communal

import com.android.systemui.CoreStartable
import com.android.systemui.communal.data.repository.CommunalMediaRepository
import com.android.systemui.communal.data.repository.CommunalSmartspaceRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class CommunalOngoingContentStartable
@Inject
constructor(
    @Background val bgScope: CoroutineScope,
    private val communalInteractor: CommunalInteractor,
    private val communalMediaRepository: CommunalMediaRepository,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val communalSmartspaceRepository: CommunalSmartspaceRepository,
) : CoreStartable {

    override fun start() {
        if (!communalSettingsInteractor.isCommunalFlagEnabled()) {
            return
        }

        bgScope.launch {
            communalInteractor.isCommunalEnabled.collect { enabled ->
                if (enabled) {
                    communalMediaRepository.startListening()
                    communalSmartspaceRepository.startListening()
                } else {
                    communalMediaRepository.stopListening()
                    communalSmartspaceRepository.stopListening()
                }
            }
        }
    }
}
