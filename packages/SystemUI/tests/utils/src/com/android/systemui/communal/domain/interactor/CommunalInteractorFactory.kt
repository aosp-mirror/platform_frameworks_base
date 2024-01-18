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

package com.android.systemui.communal.domain.interactor

import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalPrefsRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.test.TestScope

// TODO(b/319335645): get rid of me and use kosmos.
object CommunalInteractorFactory {

    @JvmOverloads
    @JvmStatic
    fun create(
        testScope: TestScope = TestScope(),
        communalRepository: FakeCommunalRepository =
            FakeCommunalRepository(testScope.backgroundScope),
        keyguardRepository: FakeKeyguardRepository = FakeKeyguardRepository(),
        widgetRepository: FakeCommunalWidgetRepository =
            FakeCommunalWidgetRepository(testScope.backgroundScope),
        mediaRepository: FakeCommunalMediaRepository = FakeCommunalMediaRepository(),
        smartspaceRepository: FakeSmartspaceRepository = FakeSmartspaceRepository(),
        tutorialRepository: FakeCommunalTutorialRepository = FakeCommunalTutorialRepository(),
        communalPrefsRepository: FakeCommunalPrefsRepository = FakeCommunalPrefsRepository(),
        appWidgetHost: CommunalAppWidgetHost = mock(),
        editWidgetsActivityStarter: EditWidgetsActivityStarter = mock(),
    ): WithDependencies {
        val keyguardInteractor =
            KeyguardInteractorFactory.create(repository = keyguardRepository).keyguardInteractor
        val communalTutorialInteractor =
            CommunalTutorialInteractor(
                testScope.backgroundScope,
                tutorialRepository,
                keyguardInteractor,
                communalRepository,
            )

        return WithDependencies(
            testScope,
            communalRepository,
            widgetRepository,
            communalPrefsRepository,
            mediaRepository,
            smartspaceRepository,
            tutorialRepository,
            keyguardRepository,
            keyguardInteractor,
            communalTutorialInteractor,
            appWidgetHost,
            editWidgetsActivityStarter,
            CommunalInteractor(
                testScope.backgroundScope,
                communalRepository,
                widgetRepository,
                communalPrefsRepository,
                mediaRepository,
                smartspaceRepository,
                keyguardInteractor,
                appWidgetHost,
                editWidgetsActivityStarter,
            ),
        )
    }

    data class WithDependencies(
        val testScope: TestScope,
        val communalRepository: FakeCommunalRepository,
        val widgetRepository: FakeCommunalWidgetRepository,
        val communalPrefsRepository: FakeCommunalPrefsRepository,
        val mediaRepository: FakeCommunalMediaRepository,
        val smartspaceRepository: FakeSmartspaceRepository,
        val tutorialRepository: FakeCommunalTutorialRepository,
        val keyguardRepository: FakeKeyguardRepository,
        val keyguardInteractor: KeyguardInteractor,
        val tutorialInteractor: CommunalTutorialInteractor,
        val appWidgetHost: CommunalAppWidgetHost,
        val editWidgetsActivityStarter: EditWidgetsActivityStarter,
        val communalInteractor: CommunalInteractor,
    )
}
