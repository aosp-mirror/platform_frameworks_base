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
package com.android.systemui.power.domain.interactor

import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.mockito.mock

object PowerInteractorFactory {
    @JvmOverloads
    @JvmStatic
    fun create(
        repository: FakePowerRepository = FakePowerRepository(),
        falsingCollector: FalsingCollector = FalsingCollectorFake(),
        screenOffAnimationController: ScreenOffAnimationController = mock(),
        statusBarStateController: StatusBarStateController = mock(),
    ): WithDependencies {
        return WithDependencies(
            repository = repository,
            falsingCollector = falsingCollector,
            screenOffAnimationController = screenOffAnimationController,
            statusBarStateController = statusBarStateController,
            powerInteractor =
                PowerInteractor(
                    repository,
                    falsingCollector,
                    screenOffAnimationController,
                    statusBarStateController,
                    mock(),
                )
        )
    }

    data class WithDependencies(
        val repository: FakePowerRepository,
        val falsingCollector: FalsingCollector,
        val screenOffAnimationController: ScreenOffAnimationController,
        val statusBarStateController: StatusBarStateController,
        val powerInteractor: PowerInteractor,
    )
}
