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

package com.android.systemui.bouncer.data.repository

import android.content.res.Resources
import com.android.internal.R
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Encapsulates Emergency Services related state. */
@SysUISingleton
class EmergencyServicesRepository
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Main private val resources: Resources,
    configurationRepository: ConfigurationRepository,
) {
    /**
     * Whether to enable emergency services calls while the SIM card is locked. This is disabled in
     * certain countries that don't support this.
     */
    val enableEmergencyCallWhileSimLocked: StateFlow<Boolean> =
        configurationRepository.onConfigurationChange
            .map { getEnableEmergencyCallWhileSimLocked() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = getEnableEmergencyCallWhileSimLocked()
            )

    private fun getEnableEmergencyCallWhileSimLocked(): Boolean {
        return resources.getBoolean(R.bool.config_enable_emergency_call_while_sim_locked)
    }
}
