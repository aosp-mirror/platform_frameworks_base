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

package com.android.systemui.statusbar.core

import android.internal.statusbar.FakeStatusBarService.Companion.SECONDARY_DISPLAY_ID
import android.internal.statusbar.fakeStatusBarService
import android.platform.test.annotations.EnableFlags
import android.view.WindowInsets
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.initController
import com.android.systemui.keyguard.data.repository.fakeCommandQueue
import com.android.systemui.statusbar.data.repository.fakeStatusBarModeRepository
import com.android.systemui.statusbar.mockCommandQueueCallbacks
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.verify

@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommandQueueInitializerTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val initController = kosmos.initController
    private val commandQueue = kosmos.fakeCommandQueue
    private val commandQueueCallbacks = kosmos.mockCommandQueueCallbacks
    private val statusBarModeRepository = kosmos.fakeStatusBarModeRepository
    private val fakeStatusBarService = kosmos.fakeStatusBarService
    private val initializer = kosmos.commandQueueInitializer

    @Test
    fun start_registersStatusBar() {
        initializer.start()

        assertThat(fakeStatusBarService.registeredStatusBar).isNotNull()
    }

    @Test
    fun start_defaultDisplay_barResultHasTransientStatusBar_transientStateIsTrue() {
        fakeStatusBarService.transientBarTypes = WindowInsets.Type.statusBars()

        initializer.start()

        assertThat(statusBarModeRepository.defaultDisplay.isTransientShown.value).isTrue()
    }

    @Test
    fun start_defaultDisplay_barResultDoesNotHaveTransientStatusBar_transientStateIsFalse() {
        fakeStatusBarService.transientBarTypes = WindowInsets.Type.navigationBars()

        initializer.start()

        assertThat(statusBarModeRepository.defaultDisplay.isTransientShown.value).isFalse()
    }

    @Test
    fun start_secondaryDisplay_barResultHasTransientStatusBar_transientStateIsTrue() {
        fakeStatusBarService.transientBarTypesSecondaryDisplay = WindowInsets.Type.statusBars()
        fakeStatusBarService.transientBarTypes = WindowInsets.Type.navigationBars()

        initializer.start()

        assertThat(statusBarModeRepository.forDisplay(SECONDARY_DISPLAY_ID).isTransientShown.value)
            .isTrue()
        // Default display should be unaffected
        assertThat(statusBarModeRepository.defaultDisplay.isTransientShown.value).isFalse()
    }

    @Test
    fun start_secondaryDisplay_barResultDoesNotHaveTransientStatusBar_transientStateIsFalse() {
        fakeStatusBarService.transientBarTypesSecondaryDisplay = WindowInsets.Type.navigationBars()
        fakeStatusBarService.transientBarTypes = WindowInsets.Type.statusBars()

        initializer.start()

        assertThat(statusBarModeRepository.forDisplay(SECONDARY_DISPLAY_ID).isTransientShown.value)
            .isFalse()
        // Default display should be unaffected
        assertThat(statusBarModeRepository.defaultDisplay.isTransientShown.value).isTrue()
    }

    @Test
    fun start_callsOnSystemBarAttributesChanged_basedOnRegisterBarResult() {
        initializer.start()

        verify(commandQueueCallbacks)
            .onSystemBarAttributesChanged(
                context.displayId,
                fakeStatusBarService.appearance,
                fakeStatusBarService.appearanceRegions,
                fakeStatusBarService.navbarColorManagedByIme,
                fakeStatusBarService.behavior,
                fakeStatusBarService.requestedVisibleTypes,
                fakeStatusBarService.packageName,
                fakeStatusBarService.letterboxDetails,
            )
        verify(commandQueueCallbacks)
            .onSystemBarAttributesChanged(
                SECONDARY_DISPLAY_ID,
                fakeStatusBarService.appearanceSecondaryDisplay,
                fakeStatusBarService.appearanceRegionsSecondaryDisplay,
                fakeStatusBarService.navbarColorManagedByImeSecondaryDisplay,
                fakeStatusBarService.behaviorSecondaryDisplay,
                fakeStatusBarService.requestedVisibleTypesSecondaryDisplay,
                fakeStatusBarService.packageNameSecondaryDisplay,
                fakeStatusBarService.letterboxDetailsSecondaryDisplay,
            )
    }

    @Test
    fun start_callsSetIcon_basedOnRegisterBarResult() {
        initializer.start()

        assertThat(commandQueue.icons).isEqualTo(fakeStatusBarService.statusBarIcons)
    }

    @Test
    fun start_callsSetImeWindowStatus_basedOnRegisterBarResult() {
        initializer.start()

        verify(commandQueueCallbacks)
            .setImeWindowStatus(
                context.displayId,
                fakeStatusBarService.imeWindowVis,
                fakeStatusBarService.imeBackDisposition,
                fakeStatusBarService.showImeSwitcher,
            )

        verify(commandQueueCallbacks)
            .setImeWindowStatus(
                SECONDARY_DISPLAY_ID,
                fakeStatusBarService.imeWindowVisSecondaryDisplay,
                fakeStatusBarService.imeBackDispositionSecondaryDisplay,
                fakeStatusBarService.showImeSwitcherSecondaryDisplay,
            )
    }

    @Test
    fun start_afterPostInitTaskExecuted_callsDisableFlags_basedOnRegisterBarResult() {
        initializer.start()

        initController.executePostInitTasks()

        assertThat(commandQueue.disableFlags1ForDisplay(context.displayId))
            .isEqualTo(fakeStatusBarService.disabledFlags1)
        assertThat(commandQueue.disableFlags2ForDisplay(context.displayId))
            .isEqualTo(fakeStatusBarService.disabledFlags2)

        assertThat(commandQueue.disableFlags1ForDisplay(SECONDARY_DISPLAY_ID))
            .isEqualTo(fakeStatusBarService.disabledFlags1SecondaryDisplay)
        assertThat(commandQueue.disableFlags2ForDisplay(SECONDARY_DISPLAY_ID))
            .isEqualTo(fakeStatusBarService.disabledFlags2SecondaryDisplay)
    }

    @Test
    fun start_beforePostInitTaskExecuted_doesNotCallsDisableFlags() {
        initializer.start()

        assertThat(commandQueue.disableFlags1ForDisplay(context.displayId)).isNull()
        assertThat(commandQueue.disableFlags2ForDisplay(context.displayId)).isNull()
        assertThat(commandQueue.disableFlags1ForDisplay(SECONDARY_DISPLAY_ID)).isNull()
        assertThat(commandQueue.disableFlags2ForDisplay(SECONDARY_DISPLAY_ID)).isNull()
    }
}
