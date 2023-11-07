/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.systemui.statusbar.phone

import android.app.AlertDialog
import android.content.res.Configuration
import android.testing.TestableLooper.RunWithLooper
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.util.mockito.mock
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(AndroidJUnit4::class)
@RunWithLooper
@SmallTest
class AlertDialogWithDelegateTest : SysuiTestCase() {

    private val delegate = mock<DialogDelegate<AlertDialog>>()

    @Test
    fun delegateIsCalled_inCorrectOrder() {
        val configuration = Configuration()
        val inOrder = Mockito.inOrder(delegate)
        val dialog = createDialog()

        dialog.show()
        dialog.onWindowFocusChanged(/* hasFocus= */ true)
        dialog.onConfigurationChanged(configuration)
        dialog.dismiss()

        inOrder.verify(delegate).beforeCreate(dialog, /* savedInstanceState= */ null)
        inOrder.verify(delegate).onCreate(dialog, /* savedInstanceState= */ null)
        inOrder.verify(delegate).onStart(dialog)
        inOrder.verify(delegate).onWindowFocusChanged(dialog, /* hasFocus= */ true)
        inOrder.verify(delegate).onConfigurationChanged(dialog, configuration)
        inOrder.verify(delegate).onStop(dialog)
    }

    private fun createDialog(): AlertDialogWithDelegate =
        AlertDialogWithDelegate(context, R.style.Theme_SystemUI_Dialog, delegate).apply {
            window?.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        }
}
