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

import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.data.repository.AuthenticationRepositoryImpl
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.bouncer.data.repository.BouncerRepository
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.keyguard.domain.interactor.LockscreenSceneInteractor
import com.android.systemui.scene.data.repository.SceneContainerRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope

/**
 * Utilities for creating scene container framework related repositories, interactors, and
 * view-models for tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneTestUtils(
    test: SysuiTestCase,
    private val testScope: TestScope? = null,
) {

    private val context = test.context

    fun fakeSceneContainerRepository(
        containerConfigurations: Set<SceneContainerConfig> =
            setOf(
                fakeSceneContainerConfig(CONTAINER_1),
                fakeSceneContainerConfig(CONTAINER_2),
            )
    ): SceneContainerRepository {
        return SceneContainerRepository(containerConfigurations.associateBy { it.name })
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
        name: String,
        sceneKeys: List<SceneKey> = fakeSceneKeys(),
    ): SceneContainerConfig {
        return SceneContainerConfig(
            name = name,
            sceneKeys = sceneKeys,
            initialSceneKey = SceneKey.Lockscreen,
        )
    }

    fun sceneInteractor(): SceneInteractor {
        return SceneInteractor(
            repository = fakeSceneContainerRepository(),
        )
    }

    fun authenticationRepository(): AuthenticationRepository {
        return AuthenticationRepositoryImpl()
    }

    fun authenticationInteractor(
        repository: AuthenticationRepository,
    ): AuthenticationInteractor {
        return AuthenticationInteractor(
            applicationScope = applicationScope(),
            repository = repository,
        )
    }

    private fun applicationScope(): CoroutineScope {
        return checkNotNull(testScope) {
                """
                TestScope not initialized, please create a TestScope and inject it into
                SceneTestUtils.
            """
                    .trimIndent()
            }
            .backgroundScope
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
            containerName = CONTAINER_1,
        )
    }

    fun bouncerViewModel(
        bouncerInteractor: BouncerInteractor,
    ): BouncerViewModel {
        return BouncerViewModel(
            applicationContext = context,
            applicationScope = applicationScope(),
            interactorFactory =
                object : BouncerInteractor.Factory {
                    override fun create(containerName: String): BouncerInteractor {
                        return bouncerInteractor
                    }
                },
            containerName = CONTAINER_1,
        )
    }

    fun lockScreenSceneInteractor(
        authenticationInteractor: AuthenticationInteractor,
        sceneInteractor: SceneInteractor,
        bouncerInteractor: BouncerInteractor,
    ): LockscreenSceneInteractor {
        return LockscreenSceneInteractor(
            applicationScope = applicationScope(),
            authenticationInteractor = authenticationInteractor,
            bouncerInteractorFactory =
                object : BouncerInteractor.Factory {
                    override fun create(containerName: String): BouncerInteractor {
                        return bouncerInteractor
                    }
                },
            sceneInteractor = sceneInteractor,
            containerName = CONTAINER_1,
        )
    }

    companion object {
        const val CONTAINER_1 = "container1"
        const val CONTAINER_2 = "container2"
    }
}
