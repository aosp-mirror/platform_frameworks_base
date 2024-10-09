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

@EnableFlags(StatusBarSimpleFragment.FLAG_NAME)
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
    fun start_barResultHasTransientStatusBar_transientStateIsTrue() {
        fakeStatusBarService.transientBarTypes = WindowInsets.Type.statusBars()

        initializer.start()

        assertThat(statusBarModeRepository.defaultDisplay.isTransientShown.value).isTrue()
    }

    @Test
    fun start_barResultDoesNotHaveTransientStatusBar_transientStateIsFalse() {
        fakeStatusBarService.transientBarTypes = WindowInsets.Type.navigationBars()

        initializer.start()

        assertThat(statusBarModeRepository.defaultDisplay.isTransientShown.value).isFalse()
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
    }

    @Test
    fun start_afterPostInitTaskExecuted_callsDisableFlags_basedOnRegisterBarResult() {
        initializer.start()

        initController.executePostInitTasks()

        assertThat(commandQueue.disableFlags1ForDisplay(context.displayId))
            .isEqualTo(fakeStatusBarService.disabledFlags1)
        assertThat(commandQueue.disableFlags2ForDisplay(context.displayId))
            .isEqualTo(fakeStatusBarService.disabledFlags2)
    }

    @Test
    fun start_beforePostInitTaskExecuted_doesNotCallsDisableFlags() {
        initializer.start()

        assertThat(commandQueue.disableFlags1ForDisplay(context.displayId)).isNull()
        assertThat(commandQueue.disableFlags2ForDisplay(context.displayId)).isNull()
    }
}
