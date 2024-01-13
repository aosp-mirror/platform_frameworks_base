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

package com.android.systemui.scene

import android.content.Context
import android.content.applicationContext
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.bouncer.data.repository.bouncerRepository
import com.android.systemui.bouncer.data.repository.fakeSimBouncerRepository
import com.android.systemui.bouncer.domain.interactor.BouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.bouncerViewModel
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.classifier.domain.interactor.falsingInteractor
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.telephony.data.repository.fakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.telephony.domain.interactor.telephonyInteractor
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.android.systemui.util.time.systemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Utilities for creating scene container framework related repositories, interactors, and
 * view-models for tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Deprecated("Please use Kosmos instead.")
class SceneTestUtils {

    val kosmos = Kosmos()

    constructor(
        context: Context,
    ) {
        kosmos.applicationContext = context
    }

    constructor(testCase: SysuiTestCase) : this(context = testCase.context) {
        kosmos.testCase = testCase
    }

    val testDispatcher by lazy { kosmos.testDispatcher }
    val testScope by lazy { kosmos.testScope }
    val fakeFeatureFlags by lazy { kosmos.fakeFeatureFlagsClassic }
    val fakeSceneContainerFlags by lazy { kosmos.fakeSceneContainerFlags }
    val deviceEntryRepository by lazy { kosmos.fakeDeviceEntryRepository }
    val authenticationRepository by lazy { kosmos.fakeAuthenticationRepository }
    val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    val configurationInteractor by lazy { kosmos.configurationInteractor }
    val telephonyRepository by lazy { kosmos.fakeTelephonyRepository }
    val bouncerRepository by lazy { kosmos.bouncerRepository }
    val communalRepository by lazy { kosmos.fakeCommunalRepository }
    val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    val powerRepository by lazy { kosmos.fakePowerRepository }
    val simBouncerRepository by lazy { kosmos.fakeSimBouncerRepository }
    val clock by lazy { kosmos.systemClock }
    val mobileConnectionsRepository by lazy { kosmos.fakeMobileConnectionsRepository }
    val simBouncerInteractor by lazy { kosmos.simBouncerInteractor }
    val statusBarStateController by lazy { kosmos.statusBarStateController }
    val interactionJankMonitor by lazy { kosmos.interactionJankMonitor }
    val screenOffAnimationController by lazy { kosmos.screenOffAnimationController }

    fun fakeSceneContainerRepository(): SceneContainerRepository {
        return kosmos.sceneContainerRepository
    }

    fun fakeSceneKeys(): List<SceneKey> {
        return kosmos.sceneKeys
    }

    fun fakeSceneContainerConfig(): SceneContainerConfig {
        return kosmos.sceneContainerConfig
    }

    fun sceneInteractor(): SceneInteractor {
        return kosmos.sceneInteractor
    }

    fun deviceEntryInteractor(): DeviceEntryInteractor {
        return kosmos.deviceEntryInteractor
    }

    fun authenticationInteractor(): AuthenticationInteractor {
        return kosmos.authenticationInteractor
    }

    fun keyguardInteractor(): KeyguardInteractor {
        return kosmos.keyguardInteractor
    }

    fun communalInteractor(): CommunalInteractor {
        return kosmos.communalInteractor
    }

    fun bouncerInteractor(): BouncerInteractor {
        return kosmos.bouncerInteractor
    }

    fun notificationsPlaceholderViewModel(): NotificationsPlaceholderViewModel {
        return kosmos.notificationsPlaceholderViewModel
    }

    fun bouncerViewModel(): BouncerViewModel {
        return kosmos.bouncerViewModel
    }

    fun telephonyInteractor(): TelephonyInteractor {
        return kosmos.telephonyInteractor
    }

    fun falsingInteractor(): FalsingInteractor {
        return kosmos.falsingInteractor
    }

    fun falsingCollector(): FalsingCollector {
        return kosmos.falsingCollector
    }

    fun powerInteractor(): PowerInteractor {
        return kosmos.powerInteractor
    }

    fun selectedUserInteractor(): SelectedUserInteractor {
        return kosmos.selectedUserInteractor
    }

    fun bouncerActionButtonInteractor(): BouncerActionButtonInteractor {
        return kosmos.bouncerActionButtonInteractor
    }
}
