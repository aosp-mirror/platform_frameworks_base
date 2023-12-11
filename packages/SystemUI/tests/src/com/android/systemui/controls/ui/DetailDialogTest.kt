/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.app.ActivityView
import android.app.PendingIntent
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.capture
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class DetailDialogTest : SysuiTestCase() {

    @Mock
    private lateinit var activityView: ActivityView
    @Mock
    private lateinit var controlViewHolder: ControlViewHolder
    @Mock
    private lateinit var pendingIntent: PendingIntent
    @Captor
    private lateinit var viewCaptor: ArgumentCaptor<ActivityView>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    @Ignore("b/313949758")
    fun testPendingIntentIsUnModified() {
        // GIVEN the dialog is created with a PendingIntent
        val dialog = createDialog(pendingIntent)

        // WHEN the ActivityView is initialized
        dialog.stateCallback.onActivityViewReady(capture(viewCaptor))

        // THEN the PendingIntent used to call startActivity is unmodified by systemui
        verify(viewCaptor.value).startActivity(eq(pendingIntent), any(), any())
    }

    private fun createDialog(pendingIntent: PendingIntent): DetailDialog {
        return DetailDialog(
            controlViewHolder,
            pendingIntent
        )
    }
}
