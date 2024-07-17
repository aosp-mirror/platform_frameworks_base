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

package com.android.systemui.scene.domain.resolver

import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardEnabledInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Resolver for [SceneFamilies.Home]. The "home" scene family resolves to the scene that is
 * currently underneath any "overlay" scene, such as shades or bouncer.
 */
@SysUISingleton
class HomeSceneFamilyResolver
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    deviceEntryInteractor: DeviceEntryInteractor,
    keyguardEnabledInteractor: KeyguardEnabledInteractor,
) : SceneResolver {
    override val targetFamily: SceneKey = SceneFamilies.Home

    override val resolvedScene: StateFlow<SceneKey> =
        combine(
                keyguardEnabledInteractor.isKeyguardEnabled,
                deviceEntryInteractor.canSwipeToEnter,
                deviceEntryInteractor.isDeviceEntered,
                deviceEntryInteractor.isUnlocked,
                transform = ::homeScene,
            )
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    homeScene(
                        isKeyguardEnabled = keyguardEnabledInteractor.isKeyguardEnabled.value,
                        canSwipeToEnter = deviceEntryInteractor.canSwipeToEnter.value,
                        isDeviceEntered = deviceEntryInteractor.isDeviceEntered.value,
                        isUnlocked = deviceEntryInteractor.isUnlocked.value,
                    )
            )

    override fun includesScene(scene: SceneKey): Boolean = scene in homeScenes

    private fun homeScene(
        isKeyguardEnabled: Boolean,
        canSwipeToEnter: Boolean?,
        isDeviceEntered: Boolean,
        isUnlocked: Boolean,
    ): SceneKey =
        when {
            !isKeyguardEnabled -> Scenes.Gone
            canSwipeToEnter == true -> Scenes.Lockscreen
            !isDeviceEntered -> Scenes.Lockscreen
            !isUnlocked -> Scenes.Lockscreen
            else -> Scenes.Gone
        }

    companion object {
        val homeScenes =
            setOf(
                Scenes.Gone,
                Scenes.Lockscreen,
            )
    }
}

@Module
interface HomeSceneFamilyResolverModule {
    @Binds @IntoSet fun provideSceneResolver(interactor: HomeSceneFamilyResolver): SceneResolver
}
