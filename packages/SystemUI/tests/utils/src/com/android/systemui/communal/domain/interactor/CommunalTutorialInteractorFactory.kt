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

package com.android.systemui.communal.domain.interactor

import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import kotlinx.coroutines.test.TestScope

object CommunalTutorialInteractorFactory {

    @JvmOverloads
    @JvmStatic
    fun create(
        testScope: TestScope,
        communalTutorialRepository: FakeCommunalTutorialRepository =
            FakeCommunalTutorialRepository(),
        communalRepository: FakeCommunalRepository =
            FakeCommunalRepository(isCommunalEnabled = true),
        keyguardRepository: FakeKeyguardRepository = FakeKeyguardRepository(),
        keyguardInteractor: KeyguardInteractor =
            KeyguardInteractorFactory.create(
                    repository = keyguardRepository,
                )
                .keyguardInteractor
    ): WithDependencies {
        return WithDependencies(
            testScope = testScope,
            communalRepository = communalRepository,
            communalTutorialRepository = communalTutorialRepository,
            keyguardRepository = keyguardRepository,
            keyguardInteractor = keyguardInteractor,
            communalTutorialInteractor =
                CommunalTutorialInteractor(
                    testScope.backgroundScope,
                    communalTutorialRepository,
                    keyguardInteractor,
                    communalRepository,
                )
        )
    }

    data class WithDependencies(
        val testScope: TestScope,
        val communalRepository: FakeCommunalRepository,
        val communalTutorialRepository: FakeCommunalTutorialRepository,
        val keyguardRepository: FakeKeyguardRepository,
        val keyguardInteractor: KeyguardInteractor,
        val communalTutorialInteractor: CommunalTutorialInteractor,
    )
}
