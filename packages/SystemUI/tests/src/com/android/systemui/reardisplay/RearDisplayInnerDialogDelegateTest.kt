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

import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.systemUIDialogDotFactory
import com.android.systemui.testKosmos
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock

/** atest SystemUITests:com.android.systemui.reardisplay.RearDisplayInnerDialogDelegateTest */
@SmallTest
@TestableLooper.RunWithLooper
class RearDisplayInnerDialogDelegateTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    @Test
    fun testShowAndDismissDialog() {
        val dialogDelegate =
            RearDisplayInnerDialogDelegate(kosmos.systemUIDialogDotFactory, mContext) {}

        val dialog = dialogDelegate.createDialog()
        dialog.show()
        assertTrue(dialog.isShowing)

        dialog.dismiss()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun testCancel() {
        val mockCallback = mock<Runnable>()
        RearDisplayInnerDialogDelegate(kosmos.systemUIDialogDotFactory, mContext) {
                mockCallback.run()
            }
            .createDialog()
            .apply {
                show()
                findViewById<View>(R.id.button_cancel).performClick()
                verify(mockCallback).run()
            }
    }
}
