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
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Simply put, I got tired of adding a constructor argument and then having to tweak dozens of
 * files. This should alleviate some of the burden by providing defaults for testing.
 */
object KeyguardInteractorFactory {

    @JvmOverloads
    @JvmStatic
    fun create(
        featureFlags: FakeFeatureFlags = FakeFeatureFlags(),
        sceneContainerFlags: SceneContainerFlags = FakeSceneContainerFlags(),
        repository: FakeKeyguardRepository = FakeKeyguardRepository(),
        commandQueue: FakeCommandQueue = FakeCommandQueue(),
        bouncerRepository: FakeKeyguardBouncerRepository = FakeKeyguardBouncerRepository(),
        configurationRepository: FakeConfigurationRepository = FakeConfigurationRepository(),
        shadeRepository: FakeShadeRepository = FakeShadeRepository(),
        sceneInteractor: SceneInteractor = mock(),
        powerInteractor: PowerInteractor = PowerInteractorFactory.create().powerInteractor,
    ): WithDependencies {
        // Mock this until the class is replaced by kosmos
        val keyguardTransitionInteractor: KeyguardTransitionInteractor = mock()
        val currentKeyguardStateFlow = MutableSharedFlow<KeyguardState>()
        whenever(keyguardTransitionInteractor.currentKeyguardState)
            .thenReturn(currentKeyguardStateFlow)
        return WithDependencies(
            repository = repository,
            commandQueue = commandQueue,
            featureFlags = featureFlags,
            sceneContainerFlags = sceneContainerFlags,
            bouncerRepository = bouncerRepository,
            configurationRepository = configurationRepository,
            shadeRepository = shadeRepository,
            powerInteractor = powerInteractor,
            KeyguardInteractor(
                repository = repository,
                commandQueue = commandQueue,
                sceneContainerFlags = sceneContainerFlags,
                bouncerRepository = bouncerRepository,
                configurationInteractor = ConfigurationInteractor(configurationRepository),
                shadeRepository = shadeRepository,
                sceneInteractorProvider = { sceneInteractor },
                keyguardTransitionInteractor = keyguardTransitionInteractor,
                powerInteractor = powerInteractor,
            ),
        )
    }

    data class WithDependencies(
        val repository: FakeKeyguardRepository,
        val commandQueue: FakeCommandQueue,
        val featureFlags: FakeFeatureFlags,
        val sceneContainerFlags: SceneContainerFlags,
        val bouncerRepository: FakeKeyguardBouncerRepository,
        val configurationRepository: FakeConfigurationRepository,
        val shadeRepository: FakeShadeRepository,
        val powerInteractor: PowerInteractor,
        val keyguardInteractor: KeyguardInteractor,
    )
}
