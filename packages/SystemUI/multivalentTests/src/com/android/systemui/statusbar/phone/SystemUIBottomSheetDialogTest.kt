/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.statusbar.phone

import android.app.Dialog
import android.content.res.Configuration
import android.testing.TestableLooper.RunWithLooper
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class SystemUIBottomSheetDialogTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val configurationController = mock<ConfigurationController>()
    private val config = mock<Configuration>()
    private val delegate = mock<DialogDelegate<Dialog>>()

    private lateinit var dialog: SystemUIBottomSheetDialog

    @Before
    fun setup() {
        dialog =
            with(kosmos) {
                SystemUIBottomSheetDialog(
                    context,
                    testScope.backgroundScope,
                    configurationController,
                    delegate,
                    TestLayout(),
                    0,
                )
            }
    }

    @Test
    fun onStart_registersConfigCallback() {
        kosmos.testScope.runTest {
            dialog.show()
            runCurrent()

            verify(configurationController).addCallback(any())
        }
    }

    @Test
    fun onStop_unregisterConfigCallback() {
        kosmos.testScope.runTest {
            dialog.show()
            runCurrent()
            dialog.dismiss()
            runCurrent()

            verify(configurationController).removeCallback(any())
        }
    }

    @Test
    fun onConfigurationChanged_calledInDelegate() {
        kosmos.testScope.runTest {
            dialog.show()
            runCurrent()
            val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
            verify(configurationController).addCallback(capture(captor))

            captor.value.onConfigChanged(config)
            runCurrent()

            verify(delegate).onConfigurationChanged(any(), any())
        }
    }

    private class TestLayout : SystemUIBottomSheetDialog.WindowLayout {
        override fun calculate(): Flow<SystemUIBottomSheetDialog.WindowLayout.Layout> {
            return flowOf(
                SystemUIBottomSheetDialog.WindowLayout.Layout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
            )
        }
    }
}
