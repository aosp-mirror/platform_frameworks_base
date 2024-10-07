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

package com.android.systemui.communal.domain.interactor

import android.os.userManager
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.communal.data.repository.communalMediaRepository
import com.android.systemui.communal.data.repository.communalSmartspaceRepository
import com.android.systemui.communal.data.repository.communalWidgetRepository
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.activityStarter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.settings.userTracker
import com.android.systemui.statusbar.phone.fakeManagedProfileController
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.mock

val Kosmos.communalInteractor by Fixture {
    CommunalInteractor(
        applicationScope = applicationCoroutineScope,
        bgDispatcher = testDispatcher,
        bgScope = testScope.backgroundScope,
        broadcastDispatcher = broadcastDispatcher,
        communalSceneInteractor = communalSceneInteractor,
        widgetRepository = communalWidgetRepository,
        communalPrefsInteractor = communalPrefsInteractor,
        mediaRepository = communalMediaRepository,
        smartspaceRepository = communalSmartspaceRepository,
        keyguardInteractor = keyguardInteractor,
        keyguardTransitionInteractor = keyguardTransitionInteractor,
        communalSettingsInteractor = communalSettingsInteractor,
        appWidgetHost = mock(),
        editWidgetsActivityStarter = editWidgetsActivityStarter,
        userTracker = userTracker,
        activityStarter = activityStarter,
        userManager = userManager,
        sceneInteractor = sceneInteractor,
        logBuffer = logcatLogBuffer("CommunalInteractor"),
        tableLogBuffer = mock(),
        managedProfileController = fakeManagedProfileController
    )
}

val Kosmos.editWidgetsActivityStarter by Fixture<EditWidgetsActivityStarter> { mock() }

suspend fun Kosmos.setCommunalEnabled(enabled: Boolean) {
    fakeFeatureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, enabled)
    if (enabled) {
        fakeUserRepository.asMainUser()
    } else {
        fakeUserRepository.asDefaultUser()
    }
}

suspend fun Kosmos.setCommunalAvailable(available: Boolean) {
    setCommunalEnabled(available)
    with(fakeKeyguardRepository) {
        setIsEncryptedOrLockdown(!available)
        setKeyguardShowing(available)
    }
}
