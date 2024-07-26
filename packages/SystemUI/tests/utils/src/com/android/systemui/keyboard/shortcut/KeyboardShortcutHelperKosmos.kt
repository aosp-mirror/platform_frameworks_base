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

import android.app.role.mockRoleManager
import android.content.applicationContext
import android.content.res.mainResources
import android.hardware.input.fakeInputManager
import android.view.windowManager
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperCategoriesRepository
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperStateRepository
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.data.source.AppCategoriesShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.CurrentAppShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.InputShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.KeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.data.source.MultitaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.SystemShortcutsSource
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperCategoriesInteractor
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperStateInteractor
import com.android.systemui.keyboard.shortcut.ui.ShortcutHelperActivityStarter
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutHelperViewModel
import com.android.systemui.keyguard.data.repository.fakeCommandQueue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.sysUiState
import com.android.systemui.settings.displayTracker
import com.android.systemui.settings.fakeUserTracker

var Kosmos.shortcutHelperAppCategoriesShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture {
        AppCategoriesShortcutsSource(
            windowManager,
            testDispatcher,
        )
    }

var Kosmos.shortcutHelperSystemShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture { SystemShortcutsSource(mainResources) }

var Kosmos.shortcutHelperMultiTaskingShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture { MultitaskingShortcutsSource(mainResources) }

val Kosmos.shortcutHelperStateRepository by
    Kosmos.Fixture {
        ShortcutHelperStateRepository(
            fakeCommandQueue,
            broadcastDispatcher,
            fakeInputManager.inputManager,
            testScope,
            testDispatcher
        )
    }

var Kosmos.shortcutHelperInputShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture { InputShortcutsSource(mainResources, windowManager) }

var Kosmos.shortcutHelperCurrentAppShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture { CurrentAppShortcutsSource(windowManager) }

val Kosmos.shortcutHelperCategoriesRepository by
    Kosmos.Fixture {
        ShortcutHelperCategoriesRepository(
            applicationContext,
            applicationCoroutineScope,
            testDispatcher,
            shortcutHelperSystemShortcutsSource,
            shortcutHelperMultiTaskingShortcutsSource,
            shortcutHelperAppCategoriesShortcutsSource,
            shortcutHelperInputShortcutsSource,
            shortcutHelperCurrentAppShortcutsSource,
            fakeInputManager.inputManager,
            shortcutHelperStateRepository,
        )
    }

val Kosmos.shortcutHelperTestHelper by
    Kosmos.Fixture {
        ShortcutHelperTestHelper(
            shortcutHelperStateRepository,
            applicationContext,
            broadcastDispatcher,
            fakeCommandQueue,
            fakeInputManager,
            windowManager,
        )
    }

val Kosmos.shortcutHelperStateInteractor by
    Kosmos.Fixture {
        ShortcutHelperStateInteractor(
            displayTracker,
            testScope,
            sysUiState,
            shortcutHelperStateRepository
        )
    }

val Kosmos.shortcutHelperCategoriesInteractor by
    Kosmos.Fixture { ShortcutHelperCategoriesInteractor(shortcutHelperCategoriesRepository) }

val Kosmos.shortcutHelperViewModel by
    Kosmos.Fixture {
        ShortcutHelperViewModel(
            mockRoleManager,
            fakeUserTracker,
            applicationCoroutineScope,
            testDispatcher,
            shortcutHelperStateInteractor,
            shortcutHelperCategoriesInteractor
        )
    }

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
