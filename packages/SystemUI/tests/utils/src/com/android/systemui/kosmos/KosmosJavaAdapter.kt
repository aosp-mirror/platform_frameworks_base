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
import android.os.fakeExecutorHandler
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.bouncerRepository
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryUdfpsInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.globalactions.domain.interactor.globalActionsInteractor
import com.android.systemui.haptics.qs.qsLongPressEffect
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.fromGoneTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.fromLockscreenTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.fromPrimaryBouncerTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.model.sceneContainerPlugin
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.sceneContainerConfig
import com.android.systemui.scene.shared.model.sceneDataSource
import com.android.systemui.settings.brightness.domain.interactor.brightnessMirrorShowingInteractor
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shadeController
import com.android.systemui.statusbar.chips.ui.viewmodel.ongoingActivityChipsViewModel
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.sharedNotificationContainerInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.policy.domain.interactor.deviceProvisioningInteractor
import com.android.systemui.util.time.systemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Helper for using [Kosmos] from Java.
 *
 * If your test class extends [SysuiTestCase], you may use the secondary constructor so that
 * [Kosmos.applicationContext] and [Kosmos.testCase] are automatically set.
 */
@Deprecated("Please convert your test to Kotlin and use [Kosmos] directly.")
class KosmosJavaAdapter() {
    constructor(testCase: SysuiTestCase) : this() {
        kosmos.applicationContext = testCase.context
        kosmos.testCase = testCase
    }

    private val kosmos = Kosmos()

    val testDispatcher by lazy { kosmos.testDispatcher }
    val testScope by lazy { kosmos.testScope }
    val fakeExecutor by lazy { kosmos.fakeExecutor }
    val fakeExecutorHandler by lazy { kosmos.fakeExecutorHandler }
    val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    val configurationInteractor by lazy { kosmos.configurationInteractor }
    val bouncerRepository by lazy { kosmos.bouncerRepository }
    val communalRepository by lazy { kosmos.fakeCommunalSceneRepository }
    val headsUpNotificationInteractor by lazy { kosmos.headsUpNotificationInteractor }
    val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    val keyguardBouncerRepository by lazy { kosmos.fakeKeyguardBouncerRepository }
    val keyguardInteractor by lazy { kosmos.keyguardInteractor }
    val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    val keyguardTransitionInteractor by lazy { kosmos.keyguardTransitionInteractor }
    val powerRepository by lazy { kosmos.fakePowerRepository }
    val clock by lazy { kosmos.systemClock }
    val mobileConnectionsRepository by lazy { kosmos.fakeMobileConnectionsRepository }
    val simBouncerInteractor by lazy { kosmos.simBouncerInteractor }
    val statusBarStateController by lazy { kosmos.statusBarStateController }
    val interactionJankMonitor by lazy { kosmos.interactionJankMonitor }
    val fakeSceneContainerConfig by lazy { kosmos.sceneContainerConfig }
    val sceneInteractor by lazy { kosmos.sceneInteractor }
    val falsingCollector by lazy { kosmos.falsingCollector }
    val powerInteractor by lazy { kosmos.powerInteractor }
    val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }
    val deviceEntryUdfpsInteractor by lazy { kosmos.deviceEntryUdfpsInteractor }
    val deviceUnlockedInteractor by lazy { kosmos.deviceUnlockedInteractor }
    val communalInteractor by lazy { kosmos.communalInteractor }
    val sceneContainerPlugin by lazy { kosmos.sceneContainerPlugin }
    val deviceProvisioningInteractor by lazy { kosmos.deviceProvisioningInteractor }
    val fakeDeviceProvisioningRepository by lazy { kosmos.fakeDeviceProvisioningRepository }
    val fromLockscreenTransitionInteractor by lazy { kosmos.fromLockscreenTransitionInteractor }
    val fromPrimaryBouncerTransitionInteractor by lazy {
        kosmos.fromPrimaryBouncerTransitionInteractor
    }
    val fromGoneTransitionInteractor by lazy { kosmos.fromGoneTransitionInteractor }
    val globalActionsInteractor by lazy { kosmos.globalActionsInteractor }
    val sceneDataSource by lazy { kosmos.sceneDataSource }
    val keyguardClockInteractor by lazy { kosmos.keyguardClockInteractor }
    val sharedNotificationContainerInteractor by lazy {
        kosmos.sharedNotificationContainerInteractor
    }
    val brightnessMirrorShowingInteractor by lazy { kosmos.brightnessMirrorShowingInteractor }
    val qsLongPressEffect by lazy { kosmos.qsLongPressEffect }
    val shadeController by lazy { kosmos.shadeController }
    val shadeRepository by lazy { kosmos.shadeRepository }
    val shadeInteractor by lazy { kosmos.shadeInteractor }

    val ongoingActivityChipsViewModel by lazy { kosmos.ongoingActivityChipsViewModel }
}
