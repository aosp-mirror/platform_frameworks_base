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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.kosmos

import android.content.applicationContext
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.bouncerRepository
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.model.sceneContainerPlugin
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.sceneContainerConfig
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.policy.domain.interactor.deviceProvisioningInteractor
import com.android.systemui.util.time.systemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Helper for using [Kosmos] from Java. */
@Deprecated("Please convert your test to Kotlin and use [Kosmos] directly.")
class KosmosJavaAdapter(
    testCase: SysuiTestCase,
) {

    private val kosmos = Kosmos()

    val testDispatcher by lazy { kosmos.testDispatcher }
    val testScope by lazy { kosmos.testScope }
    val fakeFeatureFlags by lazy { kosmos.fakeFeatureFlagsClassic }
    val fakeSceneContainerFlags by lazy { kosmos.fakeSceneContainerFlags }
    val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    val configurationInteractor by lazy { kosmos.configurationInteractor }
    val bouncerRepository by lazy { kosmos.bouncerRepository }
    val communalRepository by lazy { kosmos.fakeCommunalRepository }
    val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    val keyguardTransitionInteractor by lazy { kosmos.keyguardTransitionInteractor }
    val powerRepository by lazy { kosmos.fakePowerRepository }
    val clock by lazy { kosmos.systemClock }
    val mobileConnectionsRepository by lazy { kosmos.fakeMobileConnectionsRepository }
    val simBouncerInteractor by lazy { kosmos.simBouncerInteractor }
    val statusBarStateController by lazy { kosmos.statusBarStateController }
    val interactionJankMonitor by lazy { kosmos.interactionJankMonitor }
    val screenOffAnimationController by lazy { kosmos.screenOffAnimationController }
    val fakeSceneContainerConfig by lazy { kosmos.sceneContainerConfig }
    val sceneInteractor by lazy { kosmos.sceneInteractor }
    val falsingCollector by lazy { kosmos.falsingCollector }
    val powerInteractor by lazy { kosmos.powerInteractor }
    val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }
    val deviceUnlockedInteractor by lazy { kosmos.deviceUnlockedInteractor }
    val communalInteractor by lazy { kosmos.communalInteractor }
    val sceneContainerPlugin by lazy { kosmos.sceneContainerPlugin }
    val deviceProvisioningInteractor by lazy { kosmos.deviceProvisioningInteractor }
    val fakeDeviceProvisioningRepository by lazy { kosmos.fakeDeviceProvisioningRepository }

    init {
        kosmos.applicationContext = testCase.context
        kosmos.testCase = testCase
    }
}
