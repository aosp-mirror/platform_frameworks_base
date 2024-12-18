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

package com.android.systemui.bluetooth.qsdialog

import android.testing.TestableLooper
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.SysUiState
import com.android.systemui.shade.data.repository.shadeDialogContextInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BluetoothTileDialogDelegateTest : SysuiTestCase() {
    companion object {
        const val DEVICE_NAME = "device"
        const val DEVICE_CONNECTION_SUMMARY = "active"
        const val ENABLED = true
        const val CONTENT_HEIGHT = WRAP_CONTENT
    }

    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var bluetoothDetailsContentManagerFactory:
        BluetoothDetailsContentManager.Factory

    @Mock private lateinit var bluetoothDetailsContentManager: BluetoothDetailsContentManager

    @Mock private lateinit var bluetoothTileDialogCallback: BluetoothTileDialogCallback

    @Mock private lateinit var uiEventLogger: UiEventLogger

    @Mock private lateinit var sysuiDialogFactory: SystemUIDialog.Factory
    @Mock private lateinit var dialogManager: SystemUIDialogManager
    @Mock private lateinit var sysuiState: SysUiState
    @Mock private lateinit var dialogTransitionAnimator: DialogTransitionAnimator

    private val uiProperties =
        BluetoothTileDialogViewModel.UiProperties.build(
            isBluetoothEnabled = ENABLED,
            isAutoOnToggleFeatureAvailable = ENABLED,
        )

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var testScope: TestScope
    private lateinit var mBluetoothTileDialogDelegate: BluetoothTileDialogDelegate

    private val kosmos = testKosmos()

    @Before
    fun setUp() {
        dispatcher = kosmos.testDispatcher
        testScope = kosmos.testScope

        whenever(sysuiState.setFlag(anyLong(), anyBoolean())).thenReturn(sysuiState)

        mBluetoothTileDialogDelegate =
            BluetoothTileDialogDelegate(
                uiProperties,
                CONTENT_HEIGHT,
                bluetoothTileDialogCallback,
                {},
                uiEventLogger,
                sysuiDialogFactory,
                kosmos.shadeDialogContextInteractor,
                bluetoothDetailsContentManagerFactory,
            )

        whenever(sysuiDialogFactory.create(any(SystemUIDialog.Delegate::class.java), any()))
            .thenAnswer {
                SystemUIDialog(
                    mContext,
                    0,
                    SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK,
                    dialogManager,
                    sysuiState,
                    fakeBroadcastDispatcher,
                    dialogTransitionAnimator,
                    it.getArgument(0),
                )
            }

        whenever(
                bluetoothDetailsContentManagerFactory.create(
                    any(),
                    anyInt(),
                    any(),
                    anyBoolean(),
                    any(),
                )
            )
            .thenReturn(bluetoothDetailsContentManager)
    }

    @Test
    fun testShowDialog_createRecyclerViewWithAdapter() {
        val dialog = mBluetoothTileDialogDelegate.createDialog()
        dialog.show()

        verify(bluetoothDetailsContentManager).bind(any())
        verify(bluetoothDetailsContentManager).start()
        dialog.dismiss()
        verify(bluetoothDetailsContentManager).releaseView()
    }
}
