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

import android.content.pm.UserInfo
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.model.AuthenticationMethodModel as DataLayerAuthenticationMethodModel
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel as DomainLayerAuthenticationMethodModel
import com.android.systemui.bouncer.data.repository.BouncerRepository
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime

/**
 * Utilities for creating scene container framework related repositories, interactors, and
 * view-models for tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneTestUtils(
    test: SysuiTestCase,
) {
    val testDispatcher = StandardTestDispatcher()
    val testScope = TestScope(testDispatcher)
    val featureFlags =
        FakeFeatureFlags().apply {
            set(Flags.SCENE_CONTAINER, true)
            set(Flags.FACE_AUTH_REFACTOR, false)
        }
    private val userRepository: UserRepository by lazy {
        FakeUserRepository().apply {
            val users = listOf(UserInfo(/* id=  */ 0, "name", /* flags= */ 0))
            setUserInfos(users)
            runBlocking { setSelectedUserInfo(users.first()) }
        }
    }

    val authenticationRepository: FakeAuthenticationRepository by lazy {
        FakeAuthenticationRepository(
            currentTime = { testScope.currentTime },
        )
    }
    val keyguardRepository: FakeKeyguardRepository by lazy {
        FakeKeyguardRepository().apply {
            setWakefulnessModel(
                WakefulnessModel(
                    WakefulnessState.AWAKE,
                    WakeSleepReason.OTHER,
                    WakeSleepReason.OTHER,
                )
            )
        }
    }

    private val context = test.context

    fun fakeSceneContainerRepository(
        containerConfig: SceneContainerConfig = fakeSceneContainerConfig(),
    ): SceneContainerRepository {
        return SceneContainerRepository(applicationScope(), containerConfig)
    }

    fun fakeSceneKeys(): List<SceneKey> {
        return listOf(
            SceneKey.QuickSettings,
            SceneKey.Shade,
            SceneKey.Lockscreen,
            SceneKey.Bouncer,
            SceneKey.Gone,
        )
    }

    fun fakeSceneContainerConfig(
        sceneKeys: List<SceneKey> = fakeSceneKeys(),
    ): SceneContainerConfig {
        return SceneContainerConfig(
            sceneKeys = sceneKeys,
            initialSceneKey = SceneKey.Lockscreen,
        )
    }

    fun sceneInteractor(
        repository: SceneContainerRepository = fakeSceneContainerRepository()
    ): SceneInteractor {
        return SceneInteractor(
            applicationScope = applicationScope(),
            repository = repository,
            logger = mock(),
        )
    }

    fun authenticationRepository(): FakeAuthenticationRepository {
        return authenticationRepository
    }

    fun authenticationInteractor(
        repository: AuthenticationRepository,
        sceneInteractor: SceneInteractor = sceneInteractor(),
    ): AuthenticationInteractor {
        return AuthenticationInteractor(
            applicationScope = applicationScope(),
            repository = repository,
            backgroundDispatcher = testDispatcher,
            userRepository = userRepository,
            keyguardRepository = keyguardRepository,
            sceneInteractor = sceneInteractor,
            clock = mock { whenever(elapsedRealtime()).thenAnswer { testScope.currentTime } }
        )
    }

    fun keyguardRepository(): FakeKeyguardRepository {
        return keyguardRepository
    }

    fun keyguardInteractor(repository: KeyguardRepository): KeyguardInteractor {
        return KeyguardInteractor(
            repository = repository,
            commandQueue = FakeCommandQueue(),
            featureFlags = featureFlags,
            bouncerRepository = FakeKeyguardBouncerRepository(),
            configurationRepository = FakeConfigurationRepository(),
            shadeRepository = FakeShadeRepository(),
        )
    }

    fun bouncerInteractor(
        authenticationInteractor: AuthenticationInteractor,
        sceneInteractor: SceneInteractor,
    ): BouncerInteractor {
        return BouncerInteractor(
            applicationScope = applicationScope(),
            applicationContext = context,
            repository = BouncerRepository(),
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
            featureFlags = featureFlags,
        )
    }

    fun bouncerViewModel(
        bouncerInteractor: BouncerInteractor,
        authenticationInteractor: AuthenticationInteractor,
    ): BouncerViewModel {
        return BouncerViewModel(
            applicationContext = context,
            applicationScope = applicationScope(),
            bouncerInteractor = bouncerInteractor,
            authenticationInteractor = authenticationInteractor,
            featureFlags = featureFlags,
        )
    }

    private fun applicationScope(): CoroutineScope {
        return testScope.backgroundScope
    }

    companion object {
        fun DomainLayerAuthenticationMethodModel.toDataLayer(): DataLayerAuthenticationMethodModel {
            return when (this) {
                DomainLayerAuthenticationMethodModel.None -> DataLayerAuthenticationMethodModel.None
                DomainLayerAuthenticationMethodModel.Swipe ->
                    DataLayerAuthenticationMethodModel.None
                DomainLayerAuthenticationMethodModel.Pin -> DataLayerAuthenticationMethodModel.Pin
                DomainLayerAuthenticationMethodModel.Password ->
                    DataLayerAuthenticationMethodModel.Password
                DomainLayerAuthenticationMethodModel.Pattern ->
                    DataLayerAuthenticationMethodModel.Pattern
            }
        }
    }
}
