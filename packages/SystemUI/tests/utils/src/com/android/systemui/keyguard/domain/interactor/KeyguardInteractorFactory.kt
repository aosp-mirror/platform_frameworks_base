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
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository

/**
 * Simply put, I got tired of adding a constructor argument and then having to tweak dozens of
 * files. This should alleviate some of the burden by providing defaults for testing.
 */
object KeyguardInteractorFactory {

    @JvmOverloads
    @JvmStatic
    fun create(
        featureFlags: FakeFeatureFlags = createFakeFeatureFlags(),
        repository: FakeKeyguardRepository = FakeKeyguardRepository(),
        commandQueue: FakeCommandQueue = FakeCommandQueue(),
        bouncerRepository: FakeKeyguardBouncerRepository = FakeKeyguardBouncerRepository(),
        configurationRepository: FakeConfigurationRepository = FakeConfigurationRepository(),
    ): WithDependencies {
        return WithDependencies(
            repository = repository,
            commandQueue = commandQueue,
            featureFlags = featureFlags,
            bouncerRepository = bouncerRepository,
            configurationRepository = configurationRepository,
            KeyguardInteractor(
                repository = repository,
                commandQueue = commandQueue,
                featureFlags = featureFlags,
                bouncerRepository = bouncerRepository,
                configurationRepository = configurationRepository,
            )
        )
    }

    /** Provide defaults, otherwise tests will throw an error */
    fun createFakeFeatureFlags(): FakeFeatureFlags {
        return FakeFeatureFlags().apply { set(Flags.FACE_AUTH_REFACTOR, false) }
    }

    data class WithDependencies(
        val repository: FakeKeyguardRepository,
        val commandQueue: FakeCommandQueue,
        val featureFlags: FakeFeatureFlags,
        val bouncerRepository: FakeKeyguardBouncerRepository,
        val configurationRepository: FakeConfigurationRepository,
        val keyguardInteractor: KeyguardInteractor,
    )
}
