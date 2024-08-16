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
package com.android.systemui.accessibility.extradim

import android.content.DialogInterface
import android.testing.TestableLooper
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.accessibility.AccessibilityShortcutController
import com.android.internal.accessibility.common.ShortcutConstants
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.SysUiState
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify

/** Tests for [ExtraDimDialogDelegate]. */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class ExtraDimDialogDelegateTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private lateinit var extraDimDialogDelegate: ExtraDimDialogDelegate

    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope

    @Mock private lateinit var dialog: SystemUIDialog
    @Mock private lateinit var accessibilityManager: AccessibilityManager
    @Mock private lateinit var dialogFactory: SystemUIDialog.Factory
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var sysuiState: SysUiState

    @Before
    fun setUp() {
        whenever(sysuiState.setFlag(anyLong(), anyBoolean())).thenReturn(sysuiState)
        whenever(dialog.context).thenReturn(context)

        extraDimDialogDelegate =
            ExtraDimDialogDelegate(
                context,
                testScope.backgroundScope,
                kosmos.testDispatcher,
                dialogFactory,
                accessibilityManager,
                userTracker
            )
    }

    @Test
    fun clickButton_removeExtraDimShortcuts() =
        kosmos.testScope.runTest {
            extraDimDialogDelegate.beforeCreate(dialog, /* savedInstanceState= */ null)

            val clickListener = argumentCaptor<DialogInterface.OnClickListener>()

            // Verify the button has the right text
            verify(dialog)
                .setPositiveButton(
                    eq(R.string.accessibility_deprecate_extra_dim_dialog_button),
                    clickListener.capture()
                )

            clickListener.firstValue.onClick(dialog, 0)
            advanceUntilIdle()
            runCurrent()
            verify(accessibilityManager)
                .enableShortcutsForTargets(
                    eq(false),
                    eq(ShortcutConstants.UserShortcutType.ALL),
                    eq(
                        setOf(
                            AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_COMPONENT_NAME
                                .flattenToString()
                        )
                    ),
                    anyInt()
                )
        }
}
