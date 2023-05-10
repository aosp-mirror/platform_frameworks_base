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
 */
package com.android.systemui.contrast

import android.app.UiModeManager
import android.app.UiModeManager.ContrastUtils.fromContrastLevel
import android.os.Looper
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.SecureSettings
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/** Test the behaviour of buttons of the [ContrastDialog]. */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class ContrastDialogTest : SysuiTestCase() {

    private lateinit var mainExecutor: Executor
    private lateinit var contrastDialog: ContrastDialog
    @Mock private lateinit var mockUiModeManager: UiModeManager
    @Mock private lateinit var mockUserTracker: UserTracker
    @Mock private lateinit var mockSecureSettings: SecureSettings

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mainExecutor = MoreExecutors.directExecutor()
        whenever(mockUserTracker.userId).thenReturn(context.userId)
    }

    @Test
    fun testClickButtons_putsContrastInSettings() {
        if (Looper.myLooper() == null) Looper.prepare()
        contrastDialog =
            ContrastDialog(
                context,
                mainExecutor,
                mockUiModeManager,
                mockUserTracker,
                mockSecureSettings
            )
        contrastDialog.show()
        try {
            contrastDialog.contrastButtons.forEach {
                (contrastLevel: Int, clickedButton: FrameLayout) ->
                clickedButton.performClick()
                verify(mockSecureSettings)
                    .putFloatForUser(
                        eq(Settings.Secure.CONTRAST_LEVEL),
                        eq(fromContrastLevel(contrastLevel)),
                        eq(context.userId)
                    )
            }
        } finally {
            contrastDialog.dismiss()
        }
    }
}
