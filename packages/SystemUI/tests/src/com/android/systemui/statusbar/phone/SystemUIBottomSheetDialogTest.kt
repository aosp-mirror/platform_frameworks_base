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

import android.content.res.Configuration
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class SystemUIBottomSheetDialogTest : SysuiTestCase() {

    private val configurationController = mock<ConfigurationController>()
    private val config = mock<Configuration>()

    private lateinit var dialog: SystemUIBottomSheetDialog

    @Before
    fun setup() {
        dialog = SystemUIBottomSheetDialog(mContext, configurationController)
    }

    @Test
    fun onStart_registersConfigCallback() {
        dialog.show()

        verify(configurationController).addCallback(any())
    }

    @Test
    fun onStop_unregisterConfigCallback() {
        dialog.show()
        dialog.dismiss()

        verify(configurationController).removeCallback(any())
    }

    @Test
    fun onConfigurationChanged_calledInSubclass() {
        var onConfigChangedCalled = false
        val subclass =
            object : SystemUIBottomSheetDialog(mContext, configurationController) {
                override fun onConfigurationChanged() {
                    onConfigChangedCalled = true
                }
            }

        subclass.show()

        val captor = argumentCaptor<ConfigurationController.ConfigurationListener>()
        verify(configurationController).addCallback(capture(captor))
        captor.value.onConfigChanged(config)

        assertThat(onConfigChangedCalled).isTrue()
    }
}
