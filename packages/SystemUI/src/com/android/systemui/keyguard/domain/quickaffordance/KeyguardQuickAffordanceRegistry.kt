/*
 *  Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import com.android.systemui.keyguard.data.quickaffordance.HomeControlsKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.QrCodeScannerKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.QuickAccessWalletKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import javax.inject.Inject

/** Central registry of all known quick affordance configs. */
interface KeyguardQuickAffordanceRegistry<T : KeyguardQuickAffordanceConfig> {
    fun getAll(position: KeyguardQuickAffordancePosition): List<T>
    fun get(key: String): T
}

class KeyguardQuickAffordanceRegistryImpl
@Inject
constructor(
    homeControls: HomeControlsKeyguardQuickAffordanceConfig,
    quickAccessWallet: QuickAccessWalletKeyguardQuickAffordanceConfig,
    qrCodeScanner: QrCodeScannerKeyguardQuickAffordanceConfig,
) : KeyguardQuickAffordanceRegistry<KeyguardQuickAffordanceConfig> {
    private val configsByPosition =
        mapOf(
            KeyguardQuickAffordancePosition.BOTTOM_START to
                listOf(
                    homeControls,
                ),
            KeyguardQuickAffordancePosition.BOTTOM_END to
                listOf(
                    quickAccessWallet,
                    qrCodeScanner,
                ),
        )
    private val configByKey =
        configsByPosition.values.flatten().associateBy { config -> config.key }

    override fun getAll(
        position: KeyguardQuickAffordancePosition,
    ): List<KeyguardQuickAffordanceConfig> {
        return configsByPosition.getValue(position)
    }

    override fun get(
        key: String,
    ): KeyguardQuickAffordanceConfig {
        return configByKey.getValue(key)
    }
}
