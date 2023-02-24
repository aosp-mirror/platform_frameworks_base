/*
 * Copyright (C) 2022 The Android Open Source Project
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

/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.screenrecord

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserContextProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class ScreenRecordDialogTest : SysuiTestCase() {

    @Mock
    private lateinit var starter: ActivityStarter
    @Mock
    private lateinit var controller: RecordingController
    @Mock
    private lateinit var userContextProvider: UserContextProvider
    @Mock
    private lateinit var flags: FeatureFlags
    @Mock
    private lateinit var dialogLaunchAnimator: DialogLaunchAnimator
    @Mock
    private lateinit var onStartRecordingClicked: Runnable

    private lateinit var dialog: ScreenRecordDialog

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        dialog = ScreenRecordDialog(
            context, controller, starter, userContextProvider, flags, dialogLaunchAnimator,
            onStartRecordingClicked
        )
    }

    @After
    fun teardown() {
        if (::dialog.isInitialized) {
            dialog.dismiss()
        }
    }

    @Test
    fun testShowDialog_partialScreenSharingDisabled_appButtonIsNotVisible() {
        whenever(flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING)).thenReturn(false)

        dialog.show()

        val visibility = dialog.requireViewById<View>(R.id.button_app).visibility
        assertThat(visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testShowDialog_partialScreenSharingEnabled_appButtonIsVisible() {
        whenever(flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING)).thenReturn(true)

        dialog.show()

        val visibility = dialog.requireViewById<View>(R.id.button_app).visibility
        assertThat(visibility).isEqualTo(View.VISIBLE)
    }
}
