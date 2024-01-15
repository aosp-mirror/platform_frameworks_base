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

package com.android.systemui.mediaprojection.permission

import android.app.AlertDialog
import android.media.projection.MediaProjectionConfig
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.WindowManager
import android.widget.Spinner
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.AlertDialogWithDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.mockito.mock
import junit.framework.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class MediaProjectionPermissionDialogDelegateTest : SysuiTestCase() {

    private lateinit var dialog: AlertDialog

    private val flags = mock<FeatureFlagsClassic>()
    private val onStartRecordingClicked = mock<Runnable>()
    private val mediaProjectionMetricsLogger = mock<MediaProjectionMetricsLogger>()

    private val mediaProjectionConfig: MediaProjectionConfig =
        MediaProjectionConfig.createConfigForDefaultDisplay()
    private val appName: String = "testApp"
    private val hostUid: Int = 12345

    private val resIdSingleApp = R.string.screen_share_permission_dialog_option_single_app
    private val resIdFullScreen = R.string.screen_share_permission_dialog_option_entire_screen
    private val resIdSingleAppDisabled =
        R.string.media_projection_entry_app_permission_dialog_single_app_disabled

    @Before
    fun setUp() {
        whenever(flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING)).thenReturn(true)
    }

    @After
    fun teardown() {
        if (::dialog.isInitialized) {
            dialog.dismiss()
        }
    }

    @Test
    fun showDialog_forceShowPartialScreenShareFalse() {
        // Set up dialog with MediaProjectionConfig.createConfigForDefaultDisplay() and
        // overrideDisableSingleAppOption = false
        val overrideDisableSingleAppOption = false
        setUpAndShowDialog(overrideDisableSingleAppOption)

        val spinner = dialog.requireViewById<Spinner>(R.id.screen_share_mode_spinner)
        val secondOptionText =
            spinner.adapter
                .getDropDownView(1, null, spinner)
                .findViewById<TextView>(android.R.id.text2)
                ?.text

        // check that the first option is full screen and enabled
        assertEquals(context.getString(resIdFullScreen), spinner.selectedItem)

        // check that the second option is single app and disabled
        assertEquals(context.getString(resIdSingleAppDisabled, appName), secondOptionText)
    }

    @Test
    fun showDialog_forceShowPartialScreenShareTrue() {
        // Set up dialog with MediaProjectionConfig.createConfigForDefaultDisplay() and
        // overrideDisableSingleAppOption = true
        val overrideDisableSingleAppOption = true
        setUpAndShowDialog(overrideDisableSingleAppOption)

        val spinner = dialog.requireViewById<Spinner>(R.id.screen_share_mode_spinner)
        val secondOptionText =
            spinner.adapter
                .getDropDownView(1, null, spinner)
                .findViewById<TextView>(android.R.id.text1)
                ?.text

        // check that the first option is single app and enabled
        assertEquals(context.getString(resIdSingleApp), spinner.selectedItem)

        // check that the second option is full screen and enabled
        assertEquals(context.getString(resIdFullScreen), secondOptionText)
    }

    private fun setUpAndShowDialog(overrideDisableSingleAppOption: Boolean) {
        val delegate =
            MediaProjectionPermissionDialogDelegate(
                context,
                mediaProjectionConfig,
                {},
                onStartRecordingClicked,
                appName,
                overrideDisableSingleAppOption,
                hostUid,
                mediaProjectionMetricsLogger
            )

        dialog = AlertDialogWithDelegate(context, R.style.Theme_SystemUI_Dialog, delegate)
        SystemUIDialog.applyFlags(dialog)
        SystemUIDialog.setDialogSize(dialog)

        dialog.window?.addSystemFlags(
            WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
        )

        delegate.onCreate(dialog, savedInstanceState = null)
        dialog.show()
    }
}
