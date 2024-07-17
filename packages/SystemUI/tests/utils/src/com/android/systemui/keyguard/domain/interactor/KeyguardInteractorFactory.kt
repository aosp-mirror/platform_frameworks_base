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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor.ConfigurationBasedDimensions
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope

/**
 * Simply put, I got tired of adding a constructor argument and then having to tweak dozens of
 * files. This should alleviate some of the burden by providing defaults for testing.
 */
object KeyguardInteractorFactory {

    @JvmOverloads
    @JvmStatic
    fun create(
        featureFlags: FakeFeatureFlags = FakeFeatureFlags(),
        repository: FakeKeyguardRepository = FakeKeyguardRepository(),
        commandQueue: FakeCommandQueue = FakeCommandQueue(),
        bouncerRepository: FakeKeyguardBouncerRepository = FakeKeyguardBouncerRepository(),
        configurationRepository: FakeConfigurationRepository = FakeConfigurationRepository(),
        shadeRepository: FakeShadeRepository = FakeShadeRepository(),
        sceneInteractor: SceneInteractor = mock(),
        fromGoneTransitionInteractor: FromGoneTransitionInteractor = mock(),
        fromLockscreenTransitionInteractor: FromLockscreenTransitionInteractor = mock(),
        sharedNotificationContainerInteractor: SharedNotificationContainerInteractor? = null,
        powerInteractor: PowerInteractor = PowerInteractorFactory.create().powerInteractor,
        testScope: CoroutineScope = TestScope(),
    ): WithDependencies {
        // Mock these until they are replaced by kosmos
        val currentKeyguardStateFlow = MutableSharedFlow<KeyguardState>()
        val transitionStateFlow = MutableStateFlow(TransitionStep())
        val keyguardTransitionInteractor =
            mock<KeyguardTransitionInteractor>().also {
                whenever(it.currentKeyguardState).thenReturn(currentKeyguardStateFlow)
                whenever(it.transitionState).thenReturn(transitionStateFlow)
            }
        val configurationDimensionFlow = MutableSharedFlow<ConfigurationBasedDimensions>()
        configurationDimensionFlow.tryEmit(
            ConfigurationBasedDimensions(
                useSplitShade = false,
                useLargeScreenHeader = false,
                marginHorizontal = 0,
                marginBottom = 0,
                marginTop = 0,
                marginTopLargeScreen = 0,
                keyguardSplitShadeTopMargin = 0,
            )
        )
        val sncInteractor =
            sharedNotificationContainerInteractor
                ?: mock<SharedNotificationContainerInteractor>().also {
                    whenever(it.configurationBasedDimensions).thenReturn(configurationDimensionFlow)
                }
        return WithDependencies(
            repository = repository,
            commandQueue = commandQueue,
            featureFlags = featureFlags,
            bouncerRepository = bouncerRepository,
            configurationRepository = configurationRepository,
            shadeRepository = shadeRepository,
            powerInteractor = powerInteractor,
            KeyguardInteractor(
                repository = repository,
                commandQueue = commandQueue,
                powerInteractor = powerInteractor,
                bouncerRepository = bouncerRepository,
                configurationInteractor = ConfigurationInteractor(configurationRepository),
                shadeRepository = shadeRepository,
                keyguardTransitionInteractor = keyguardTransitionInteractor,
                sceneInteractorProvider = { sceneInteractor },
                fromGoneTransitionInteractor = { fromGoneTransitionInteractor },
                fromLockscreenTransitionInteractor = { fromLockscreenTransitionInteractor },
                sharedNotificationContainerInteractor = { sncInteractor },
                applicationScope = testScope,
            ),
        )
    }

    data class WithDependencies(
        val repository: FakeKeyguardRepository,
        val commandQueue: FakeCommandQueue,
        val featureFlags: FakeFeatureFlags,
        val bouncerRepository: FakeKeyguardBouncerRepository,
        val configurationRepository: FakeConfigurationRepository,
        val shadeRepository: FakeShadeRepository,
        val powerInteractor: PowerInteractor,
        val keyguardInteractor: KeyguardInteractor,
    )
}
