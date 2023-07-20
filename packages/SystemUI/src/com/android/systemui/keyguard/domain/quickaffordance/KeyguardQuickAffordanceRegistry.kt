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

import android.content.Context
import android.provider.Settings

import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys.CAMERA
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys.DO_NOT_DISTURB
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys.FLASHLIGHT
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys.HOME_CONTROLS
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys.QR_CODE_SCANNER
import com.android.systemui.keyguard.data.quickaffordance.BuiltInKeyguardQuickAffordanceKeys.QUICK_ACCESS_WALLET

import com.android.systemui.keyguard.data.quickaffordance.CameraQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.DoNotDisturbQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.FlashlightQuickAffordanceConfig
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
    fun updateSettings()
}

const val DEFAULT_CONFIG = HOME_CONTROLS + "," + FLASHLIGHT + "," + DO_NOT_DISTURB + ";" + QUICK_ACCESS_WALLET + "," + QR_CODE_SCANNER + "," + CAMERA

class KeyguardQuickAffordanceRegistryImpl
@Inject
constructor(
    private val context: Context,
    private val homeControls: HomeControlsKeyguardQuickAffordanceConfig,
    private val quickAccessWallet: QuickAccessWalletKeyguardQuickAffordanceConfig,
    private val qrCodeScanner: QrCodeScannerKeyguardQuickAffordanceConfig,
    private val camera: CameraQuickAffordanceConfig,
    private val flashlight: FlashlightQuickAffordanceConfig,
    private val doNotDisturb: DoNotDisturbQuickAffordanceConfig
) : KeyguardQuickAffordanceRegistry<KeyguardQuickAffordanceConfig> {

    private val configsBySetting: Map<String, KeyguardQuickAffordanceConfig> =
        mapOf(
            HOME_CONTROLS to homeControls,
            QUICK_ACCESS_WALLET to quickAccessWallet,
            QR_CODE_SCANNER to qrCodeScanner,
            CAMERA to camera,
            FLASHLIGHT to flashlight,
            DO_NOT_DISTURB to doNotDisturb
        )

    private var configsByPosition: Map<KeyguardQuickAffordancePosition, MutableList<KeyguardQuickAffordanceConfig>>
    private var configByKey: Map<String, KeyguardQuickAffordanceConfig>

    init {
        configsByPosition = mapOf()
        configByKey = mapOf()
        updateSettings()
    }

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

    override fun updateSettings() {
        var setting = Settings.System.getString(context.getContentResolver(),
                Settings.System.KEYGUARD_QUICK_TOGGLES_NEW)
        if (setting == null || setting.isEmpty())
            setting = DEFAULT_CONFIG
        val split: List<String> = setting.split(";")
        val start: List<String> = split.get(0).split(",")
        val end: List<String> = split.get(1).split(",")
        var startList: MutableList<KeyguardQuickAffordanceConfig> = mutableListOf()
        var endList: MutableList<KeyguardQuickAffordanceConfig> = mutableListOf()
        if (!start.get(0).equals("none")) {
            for (str in start)
                startList.add(configsBySetting.getOrDefault(str, homeControls))
        }
        if (!end.get(0).equals("none")) {
            for (str in end)
                endList.add(configsBySetting.getOrDefault(str, quickAccessWallet))
        }

        configsByPosition =
            mapOf(
                KeyguardQuickAffordancePosition.BOTTOM_START to
                    startList,
                KeyguardQuickAffordancePosition.BOTTOM_END to
                    endList,
            )

        configByKey =
            configsByPosition.values.flatten().associateBy { config -> config.key }
    }
}
