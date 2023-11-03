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
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
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
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepository
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.ui.viewmodel.UserActionViewModel
import com.android.systemui.user.ui.viewmodel.UserViewModel
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime

/**
 * Utilities for creating scene container framework related repositories, interactors, and
 * view-models for tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneTestUtils(
    private val context: Context,
) {
    constructor(test: SysuiTestCase) : this(context = test.context)

    val kosmos = Kosmos()
    val testDispatcher = kosmos.testDispatcher
    val testScope = kosmos.testScope
    val featureFlags =
        FakeFeatureFlagsClassic().apply {
            set(Flags.FACE_AUTH_REFACTOR, false)
            set(Flags.FULL_SCREEN_USER_SWITCHER, false)
        }
    val sceneContainerFlags = FakeSceneContainerFlags().apply { enabled = true }
    val deviceEntryRepository: FakeDeviceEntryRepository by lazy { FakeDeviceEntryRepository() }
    val authenticationRepository: FakeAuthenticationRepository by lazy {
        FakeAuthenticationRepository(
            deviceEntryRepository = deviceEntryRepository,
            currentTime = { testScope.currentTime },
        )
    }

    val communalRepository: FakeCommunalRepository by lazy { FakeCommunalRepository() }
    private val communalWidgetRepository: FakeCommunalWidgetRepository by lazy {
        FakeCommunalWidgetRepository()
    }
    val keyguardRepository: FakeKeyguardRepository by lazy { FakeKeyguardRepository() }
    val powerRepository: FakePowerRepository by lazy { FakePowerRepository() }

    private val userRepository: UserRepository by lazy {
        FakeUserRepository().apply {
            val users = listOf(UserInfo(/* id=  */ 0, "name", /* flags= */ 0))
            setUserInfos(users)
            runBlocking { setSelectedUserInfo(users.first()) }
        }
    }

    private val falsingCollectorFake: FalsingCollector by lazy { FalsingCollectorFake() }
    private var falsingInteractor: FalsingInteractor? = null

    fun fakeSceneContainerRepository(
        containerConfig: SceneContainerConfig = fakeSceneContainerConfig(),
    ): SceneContainerRepository {
        return SceneContainerRepository(applicationScope(), containerConfig)
    }

    fun fakeSceneKeys(): List<SceneKey> {
        return kosmos.sceneKeys
    }

    fun fakeSceneContainerConfig(): SceneContainerConfig {
        return kosmos.sceneContainerConfig
    }

    @JvmOverloads
    fun sceneInteractor(
        repository: SceneContainerRepository = fakeSceneContainerRepository()
    ): SceneInteractor {
        return SceneInteractor(
            applicationScope = applicationScope(),
            repository = repository,
            powerRepository = powerRepository,
            logger = mock(),
        )
    }

    fun deviceEntryInteractor(
        repository: DeviceEntryRepository = deviceEntryRepository,
        authenticationInteractor: AuthenticationInteractor,
        sceneInteractor: SceneInteractor,
        faceAuthRepository: DeviceEntryFaceAuthRepository = FakeDeviceEntryFaceAuthRepository(),
        trustRepository: TrustRepository = FakeTrustRepository(),
    ): DeviceEntryInteractor {
        return DeviceEntryInteractor(
            applicationScope = applicationScope(),
            repository = repository,
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
            deviceEntryFaceAuthRepository = faceAuthRepository,
            trustRepository = trustRepository,
        )
    }

    fun authenticationInteractor(
        repository: AuthenticationRepository = authenticationRepository,
    ): AuthenticationInteractor {
        return AuthenticationInteractor(
            applicationScope = applicationScope(),
            repository = repository,
            backgroundDispatcher = testDispatcher,
            userRepository = userRepository,
            deviceEntryRepository = deviceEntryRepository,
            clock = mock { whenever(elapsedRealtime()).thenAnswer { testScope.currentTime } }
        )
    }

    fun keyguardInteractor(
        repository: KeyguardRepository = keyguardRepository
    ): KeyguardInteractor {
        return KeyguardInteractor(
            repository = repository,
            commandQueue = FakeCommandQueue(),
            featureFlags = featureFlags,
            sceneContainerFlags = sceneContainerFlags,
            bouncerRepository = FakeKeyguardBouncerRepository(),
            configurationRepository = FakeConfigurationRepository(),
            shadeRepository = FakeShadeRepository(),
            sceneInteractorProvider = { sceneInteractor() },
            powerInteractor = PowerInteractorFactory.create().powerInteractor,
        )
    }

    fun communalInteractor(): CommunalInteractor {
        return CommunalInteractor(
            communalRepository = communalRepository,
            widgetRepository = communalWidgetRepository,
        )
    }

    fun bouncerInteractor(
        deviceEntryInteractor: DeviceEntryInteractor,
        authenticationInteractor: AuthenticationInteractor,
        sceneInteractor: SceneInteractor,
    ): BouncerInteractor {
        return BouncerInteractor(
            applicationScope = applicationScope(),
            applicationContext = context,
            repository = BouncerRepository(featureFlags),
            deviceEntryInteractor = deviceEntryInteractor,
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
            flags = sceneContainerFlags,
            falsingInteractor = falsingInteractor(),
        )
    }

    fun bouncerViewModel(
        bouncerInteractor: BouncerInteractor,
        authenticationInteractor: AuthenticationInteractor,
        users: List<UserViewModel> = createUsers(),
    ): BouncerViewModel {
        return BouncerViewModel(
            applicationContext = context,
            applicationScope = applicationScope(),
            mainDispatcher = testDispatcher,
            bouncerInteractor = bouncerInteractor,
            authenticationInteractor = authenticationInteractor,
            flags = sceneContainerFlags,
            selectedUser = flowOf(users.first { it.isSelectionMarkerVisible }),
            users = flowOf(users),
            userSwitcherMenu = flowOf(createMenuActions()),
            telephonyInteractor =
                TelephonyInteractor(
                    repository = FakeTelephonyRepository(),
                ),
        )
    }

    fun falsingInteractor(collector: FalsingCollector = falsingCollector()): FalsingInteractor {
        return falsingInteractor ?: FalsingInteractor(collector).also { falsingInteractor = it }
    }

    fun falsingCollector(): FalsingCollector {
        return falsingCollectorFake
    }

    private fun applicationScope(): CoroutineScope {
        return testScope.backgroundScope
    }

    private fun createUsers(
        count: Int = 3,
        selectedIndex: Int = 0,
    ): List<UserViewModel> {
        check(selectedIndex in 0 until count)

        return buildList {
            repeat(count) { index ->
                add(
                    UserViewModel(
                        viewKey = index,
                        name = Text.Loaded("name_$index"),
                        image = BitmapDrawable(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)),
                        isSelectionMarkerVisible = index == selectedIndex,
                        alpha = 1f,
                        onClicked = {},
                    )
                )
            }
        }
    }

    private fun createMenuActions(): List<UserActionViewModel> {
        return buildList {
            repeat(3) { index ->
                add(
                    UserActionViewModel(
                        viewKey = index.toLong(),
                        iconResourceId = 0,
                        textResourceId = 0,
                        onClicked = {},
                    )
                )
            }
        }
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
