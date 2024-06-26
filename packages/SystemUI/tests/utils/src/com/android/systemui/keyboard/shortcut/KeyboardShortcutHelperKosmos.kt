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

package com.android.systemui.keyboard.shortcut

import android.content.applicationContext
import android.hardware.input.fakeInputManager
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperRepository
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperInteractor
import com.android.systemui.keyboard.shortcut.ui.ShortcutHelperActivityStarter
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutHelperViewModel
import com.android.systemui.keyguard.data.repository.fakeCommandQueue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.sysUiState
import com.android.systemui.settings.displayTracker

val Kosmos.shortcutHelperRepository by
    Kosmos.Fixture {
        ShortcutHelperRepository(
            fakeCommandQueue,
            broadcastDispatcher,
            fakeInputManager.inputManager,
            testScope,
            testDispatcher
        )
    }

val Kosmos.shortcutHelperTestHelper by
    Kosmos.Fixture {
        ShortcutHelperTestHelper(
            shortcutHelperRepository,
            applicationContext,
            broadcastDispatcher,
            fakeCommandQueue
        )
    }

val Kosmos.shortcutHelperInteractor by
    Kosmos.Fixture {
        ShortcutHelperInteractor(displayTracker, testScope, sysUiState, shortcutHelperRepository)
    }

val Kosmos.shortcutHelperViewModel by
    Kosmos.Fixture { ShortcutHelperViewModel(testDispatcher, shortcutHelperInteractor) }

val Kosmos.fakeShortcutHelperStartActivity by Kosmos.Fixture { FakeShortcutHelperStartActivity() }

val Kosmos.shortcutHelperActivityStarter by
    Kosmos.Fixture {
        ShortcutHelperActivityStarter(
            applicationContext,
            applicationCoroutineScope,
            shortcutHelperViewModel,
            fakeShortcutHelperStartActivity,
        )
    }
