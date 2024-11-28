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

package com.android.systemui.reardisplay

import android.hardware.devicestate.feature.flags.Flags.FLAG_DEVICE_STATE_RDM_V2
import android.hardware.display.rearDisplay
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.Display
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.deviceStateManager
import com.android.systemui.display.domain.interactor.RearDisplayStateInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.rearDisplayInnerDialogDelegateFactory
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** atest SystemUITests:com.android.systemui.reardisplay.RearDisplayCoreStartableTest */
@SmallTest
@kotlinx.coroutines.ExperimentalCoroutinesApi
class RearDisplayCoreStartableTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val mockDelegate: RearDisplayInnerDialogDelegate = mock()
    private val mockDialog: SystemUIDialog = mock()

    private val fakeRearDisplayStateInteractor = FakeRearDisplayStateInteractor(kosmos)
    private val impl =
        RearDisplayCoreStartable(
            mContext,
            kosmos.deviceStateManager,
            fakeRearDisplayStateInteractor,
            kosmos.rearDisplayInnerDialogDelegateFactory,
            kosmos.testScope,
        )

    @Before
    fun setup() {
        whenever(kosmos.rearDisplay.flags).thenReturn(Display.FLAG_REAR)
        whenever(kosmos.rearDisplay.displayAdjustments)
            .thenReturn(mContext.display.displayAdjustments)
        whenever(kosmos.rearDisplayInnerDialogDelegateFactory.create(any(), any()))
            .thenReturn(mockDelegate)
        whenever(mockDelegate.createDialog()).thenReturn(mockDialog)
    }

    @Test
    @DisableFlags(FLAG_DEVICE_STATE_RDM_V2)
    fun testWhenFlagDisabled() =
        kosmos.runTest {
            impl.use {
                it.start()
                assertThat(impl.stateChangeListener).isNull()
            }
        }

    @Test
    @EnableFlags(FLAG_DEVICE_STATE_RDM_V2)
    fun testShowAndDismissDialog() =
        kosmos.runTest {
            impl.use {
                it.start()
                fakeRearDisplayStateInteractor.emitRearDisplay()
                verify(mockDialog).show()
                verify(mockDialog, never()).dismiss()

                fakeRearDisplayStateInteractor.emitDisabled()
                verify(mockDialog).dismiss()
            }
        }

    private class FakeRearDisplayStateInteractor(private val kosmos: Kosmos) :
        RearDisplayStateInteractor {
        private val stateFlow = MutableSharedFlow<RearDisplayStateInteractor.State>()

        suspend fun emitRearDisplay() =
            stateFlow.emit(RearDisplayStateInteractor.State.Enabled(kosmos.rearDisplay))

        suspend fun emitDisabled() = stateFlow.emit(RearDisplayStateInteractor.State.Disabled)

        override val state: Flow<RearDisplayStateInteractor.State>
            get() = stateFlow
    }
}
