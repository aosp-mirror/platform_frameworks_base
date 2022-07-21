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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.config.KeyguardQuickAffordanceConfigs
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.State
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePosition
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Defines interface for classes that encapsulate quick affordance state for the keyguard. */
interface KeyguardQuickAffordanceRepository {
    fun affordance(position: KeyguardQuickAffordancePosition): Flow<KeyguardQuickAffordanceModel>
}

/** Real implementation of [KeyguardQuickAffordanceRepository] */
@SysUISingleton
class KeyguardQuickAffordanceRepositoryImpl
@Inject
constructor(
    private val configs: KeyguardQuickAffordanceConfigs,
) : KeyguardQuickAffordanceRepository {

    /** Returns an observable for the quick affordance model in the given position. */
    override fun affordance(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceModel> {
        val configs = configs.getAll(position)
        return combine(configs.map { config -> config.state }) { states ->
            val index = states.indexOfFirst { state -> state is State.Visible }
            val visibleState =
                if (index != -1) {
                    states[index] as State.Visible
                } else {
                    null
                }
            if (visibleState != null) {
                KeyguardQuickAffordanceModel.Visible(
                    configKey = configs[index]::class,
                    icon = visibleState.icon,
                    contentDescriptionResourceId = visibleState.contentDescriptionResourceId,
                )
            } else {
                KeyguardQuickAffordanceModel.Hidden
            }
        }
    }
}
