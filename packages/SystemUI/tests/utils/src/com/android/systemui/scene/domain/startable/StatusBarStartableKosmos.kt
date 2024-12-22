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

package com.android.systemui.scene.domain.startable

import android.content.applicationContext
import com.android.internal.statusbar.statusBarService
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.deviceconfig.domain.interactor.deviceConfigInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.navigation.domain.interactor.navigationInteractor
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.statusBarStartable by Fixture {
    StatusBarStartable(
        applicationScope = applicationCoroutineScope,
        backgroundDispatcher = testDispatcher,
        applicationContext = applicationContext,
        selectedUserInteractor = selectedUserInteractor,
        sceneInteractor = sceneInteractor,
        deviceEntryInteractor = deviceEntryInteractor,
        sceneContainerOcclusionInteractor = sceneContainerOcclusionInteractor,
        deviceConfigInteractor = deviceConfigInteractor,
        navigationInteractor = navigationInteractor,
        authenticationInteractor = authenticationInteractor,
        powerInteractor = powerInteractor,
        deviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor,
        statusBarService = statusBarService,
    )
}
