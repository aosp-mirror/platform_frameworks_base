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
import com.android.systemui.keyboard.shortcut.data.repository.CustomInputGesturesRepository
import com.android.systemui.keyboard.shortcut.data.repository.CustomShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.data.repository.DefaultShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.data.repository.InputGestureDataAdapter
import com.android.systemui.keyboard.shortcut.data.repository.InputGestureMaps
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutCategoriesUtils
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperStateRepository
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.data.source.AppCategoriesShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.CurrentAppShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.InputShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.KeyboardShortcutGroupsSource
import com.android.systemui.keyboard.shortcut.data.source.MultitaskingShortcutsSource
import com.android.systemui.keyboard.shortcut.data.source.SystemShortcutsSource
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutCustomizationInteractor
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperCategoriesInteractor
import com.android.systemui.keyboard.shortcut.domain.interactor.ShortcutHelperStateInteractor
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperExclusions
import com.android.systemui.keyboard.shortcut.ui.ShortcutCustomizationDialogStarter
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutCustomizationViewModel
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutHelperViewModel
import com.android.systemui.keyguard.data.repository.fakeCommandQueue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.backgroundCoroutineContext
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.sysUiState
import com.android.systemui.settings.displayTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.statusbar.phone.systemUIDialogFactory

var Kosmos.shortcutHelperAppCategoriesShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture { AppCategoriesShortcutsSource(windowManager, testDispatcher) }

var Kosmos.shortcutHelperSystemShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture { SystemShortcutsSource(mainResources, fakeInputManager.inputManager) }

var Kosmos.shortcutHelperMultiTaskingShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture { MultitaskingShortcutsSource(mainResources, applicationContext) }

val Kosmos.shortcutHelperStateRepository by
    Kosmos.Fixture {
        ShortcutHelperStateRepository(
            fakeCommandQueue,
            broadcastDispatcher,
            fakeInputManager.inputManager,
            testScope,
            testDispatcher,
        )
    }

var Kosmos.shortcutHelperInputShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture {
        InputShortcutsSource(mainResources, windowManager, fakeInputManager.inputManager)
    }

var Kosmos.shortcutHelperCurrentAppShortcutsSource: KeyboardShortcutGroupsSource by
    Kosmos.Fixture { CurrentAppShortcutsSource(windowManager) }

val Kosmos.shortcutHelperExclusions by
    Kosmos.Fixture { ShortcutHelperExclusions(applicationContext) }

val Kosmos.shortcutCategoriesUtils by
    Kosmos.Fixture {
        ShortcutCategoriesUtils(
            applicationContext,
            backgroundCoroutineContext,
            fakeInputManager.inputManager,
            shortcutHelperExclusions,
        )
    }

val Kosmos.defaultShortcutCategoriesRepository by
    Kosmos.Fixture {
        DefaultShortcutCategoriesRepository(
            applicationCoroutineScope,
            testDispatcher,
            shortcutHelperSystemShortcutsSource,
            shortcutHelperMultiTaskingShortcutsSource,
            shortcutHelperAppCategoriesShortcutsSource,
            shortcutHelperInputShortcutsSource,
            shortcutHelperCurrentAppShortcutsSource,
            fakeInputManager.inputManager,
            shortcutHelperStateRepository,
            shortcutCategoriesUtils,
        )
    }

val Kosmos.inputGestureMaps by Kosmos.Fixture { InputGestureMaps(applicationContext) }

val Kosmos.inputGestureDataAdapter by Kosmos.Fixture { InputGestureDataAdapter(userTracker, inputGestureMaps, applicationContext)}

val Kosmos.customInputGesturesRepository by
    Kosmos.Fixture { CustomInputGesturesRepository(userTracker, testDispatcher) }

val Kosmos.customShortcutCategoriesRepository by
    Kosmos.Fixture {
        CustomShortcutCategoriesRepository(
            shortcutHelperStateRepository,
            applicationCoroutineScope,
            testDispatcher,
            shortcutCategoriesUtils,
            inputGestureDataAdapter,
            customInputGesturesRepository,
            fakeInputManager.inputManager,
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
            shortcutHelperStateRepository,
        )
    }

val Kosmos.shortcutHelperCategoriesInteractor by
    Kosmos.Fixture {
        ShortcutHelperCategoriesInteractor(
            context = applicationContext,
            defaultShortcutCategoriesRepository,
        ) {
            customShortcutCategoriesRepository
        }
    }

val Kosmos.shortcutHelperViewModel by
    Kosmos.Fixture {
        ShortcutHelperViewModel(
            applicationContext,
            mockRoleManager,
            userTracker,
            applicationCoroutineScope,
            testDispatcher,
            shortcutHelperStateInteractor,
            shortcutHelperCategoriesInteractor,
        )
    }

val Kosmos.shortcutCustomizationDialogStarterFactory by
    Kosmos.Fixture {
        object : ShortcutCustomizationDialogStarter.Factory {
            override fun create(): ShortcutCustomizationDialogStarter {
                return ShortcutCustomizationDialogStarter(
                    shortcutCustomizationViewModelFactory,
                    systemUIDialogFactory,
                )
            }
        }
    }

val Kosmos.shortcutCustomizationInteractor by
    Kosmos.Fixture { ShortcutCustomizationInteractor(customShortcutCategoriesRepository) }

val Kosmos.shortcutCustomizationViewModelFactory by
    Kosmos.Fixture {
        object : ShortcutCustomizationViewModel.Factory {
            override fun create(): ShortcutCustomizationViewModel {
                return ShortcutCustomizationViewModel(
                    applicationContext,
                    shortcutCustomizationInteractor,
                )
            }
        }
    }
