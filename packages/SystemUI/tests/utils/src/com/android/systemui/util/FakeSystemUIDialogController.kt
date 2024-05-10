/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.util

import android.content.DialogInterface
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.stubbing.Stubber

class FakeSystemUIDialogController {

    val dialog: SystemUIDialog = mock()

    private val clickListeners: MutableMap<Int, DialogInterface.OnClickListener> = mutableMapOf()

    init {
        saveListener(DialogInterface.BUTTON_POSITIVE)
            .whenever(dialog)
            .setPositiveButton(any(), any())
        saveListener(DialogInterface.BUTTON_POSITIVE)
            .whenever(dialog)
            .setPositiveButton(any(), any(), any())

        saveListener(DialogInterface.BUTTON_NEGATIVE)
            .whenever(dialog)
            .setNegativeButton(any(), any())
        saveListener(DialogInterface.BUTTON_NEGATIVE)
            .whenever(dialog)
            .setNegativeButton(any(), any(), any())

        saveListener(DialogInterface.BUTTON_NEUTRAL).whenever(dialog).setNeutralButton(any(), any())
        saveListener(DialogInterface.BUTTON_NEUTRAL)
            .whenever(dialog)
            .setNeutralButton(any(), any(), any())
    }

    fun clickNegative() {
        performClick(DialogInterface.BUTTON_NEGATIVE, "This dialog has no negative button")
    }

    fun clickPositive() {
        performClick(DialogInterface.BUTTON_POSITIVE, "This dialog has no positive button")
    }

    fun clickNeutral() {
        performClick(DialogInterface.BUTTON_NEUTRAL, "This dialog has no neutral button")
    }

    fun cancel() {
        val captor = ArgumentCaptor.forClass(DialogInterface.OnCancelListener::class.java)
        verify(dialog).setOnCancelListener(captor.capture())
        captor.value.onCancel(dialog)
    }

    private fun performClick(which: Int, errorMessage: String) {
        clickListeners
            .getOrElse(which) { throw IllegalAccessException(errorMessage) }
            .onClick(dialog, which)
    }

    private fun saveListener(which: Int): Stubber = doAnswer {
        val listener = it.getArgument<DialogInterface.OnClickListener>(1)
        clickListeners[which] = listener
        Unit
    }
}
