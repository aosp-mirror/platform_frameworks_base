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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet

@Module
interface KeyguardDataQuickAffordanceModule {
    @Binds
    fun providerClientFactory(
        impl: KeyguardQuickAffordanceProviderClientFactoryImpl,
    ): KeyguardQuickAffordanceProviderClientFactory

    companion object {
        @Provides
        @ElementsIntoSet
        fun quickAffordanceConfigs(
            doNotDisturb: DoNotDisturbQuickAffordanceConfig,
            flashlight: FlashlightQuickAffordanceConfig,
            home: HomeControlsKeyguardQuickAffordanceConfig,
            quickAccessWallet: QuickAccessWalletKeyguardQuickAffordanceConfig,
            qrCodeScanner: QrCodeScannerKeyguardQuickAffordanceConfig,
            camera: CameraQuickAffordanceConfig,
        ): Set<KeyguardQuickAffordanceConfig> {
            return setOf(
                camera,
                doNotDisturb,
                flashlight,
                home,
                quickAccessWallet,
                qrCodeScanner,
            )
        }
    }
}
