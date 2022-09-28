/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.user.data.repository

import android.os.UserManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.util.settings.FakeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScope
import org.mockito.Mock
import org.mockito.MockitoAnnotations

abstract class UserRepositoryImplTest : SysuiTestCase() {

    @Mock protected lateinit var manager: UserManager
    @Mock protected lateinit var controller: UserSwitcherController

    protected lateinit var underTest: UserRepositoryImpl

    protected lateinit var globalSettings: FakeSettings
    protected lateinit var tracker: FakeUserTracker
    protected lateinit var featureFlags: FakeFeatureFlags

    protected fun setUp(isRefactored: Boolean) {
        MockitoAnnotations.initMocks(this)

        globalSettings = FakeSettings()
        tracker = FakeUserTracker()
        featureFlags = FakeFeatureFlags()
        featureFlags.set(Flags.REFACTORED_USER_SWITCHER_CONTROLLER, isRefactored)
    }

    protected fun create(scope: CoroutineScope = TestCoroutineScope()): UserRepositoryImpl {
        return UserRepositoryImpl(
            appContext = context,
            manager = manager,
            controller = controller,
            applicationScope = scope,
            mainDispatcher = IMMEDIATE,
            backgroundDispatcher = IMMEDIATE,
            globalSettings = globalSettings,
            tracker = tracker,
            featureFlags = featureFlags,
        )
    }

    companion object {
        @JvmStatic protected val IMMEDIATE = Dispatchers.Main.immediate
    }
}
