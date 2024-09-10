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
import android.testing.TestableLooper
import android.view.WindowManager
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.AlertDialogWithDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class SystemCastPermissionDialogDelegateTest : SysuiTestCase() {

    private lateinit var dialog: AlertDialog

    private val appName = "Test App"

    private val resIdSingleApp =
        R.string.media_projection_entry_cast_permission_dialog_option_text_single_app
    private val resIdFullScreen =
        R.string.media_projection_entry_cast_permission_dialog_option_text_entire_screen
    private val resIdSingleAppDisabled =
        R.string.media_projection_entry_app_permission_dialog_single_app_disabled

    @After
    fun teardown() {
        if (::dialog.isInitialized) {
            dialog.dismiss()
        }
    }

    @Test
    fun showDefaultDialog() {
        setUpAndShowDialog()

        val spinner = dialog.requireViewById<Spinner>(R.id.screen_share_mode_options)
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

    @Test
    fun showDialog_disableSingleApp() {
        setUpAndShowDialog(
            mediaProjectionConfig = MediaProjectionConfig.createConfigForDefaultDisplay()
        )

        val spinner = dialog.requireViewById<Spinner>(R.id.screen_share_mode_options)
        val secondOptionWarningText =
            spinner.adapter
                .getDropDownView(1, null, spinner)
                .findViewById<TextView>(android.R.id.text2)
                ?.text

        // check that the first option is full screen and enabled
        assertEquals(context.getString(resIdFullScreen), spinner.selectedItem)

        // check that the second option is single app and disabled
        assertEquals(context.getString(resIdSingleAppDisabled, appName), secondOptionWarningText)
    }

    @Test
    fun showDialog_disableSingleApp_forceShowPartialScreenShareTrue() {
        setUpAndShowDialog(
            mediaProjectionConfig = MediaProjectionConfig.createConfigForDefaultDisplay(),
            overrideDisableSingleAppOption = true,
        )

        val spinner = dialog.requireViewById<Spinner>(R.id.screen_share_mode_options)
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

    @Test
    fun startButtonText_entireScreenSelected() {
        setUpAndShowDialog()
        onSpinnerItemSelected(ENTIRE_SCREEN)

        val startButtonText = dialog.requireViewById<TextView>(android.R.id.button1).text

        assertThat(startButtonText)
            .isEqualTo(
                context.getString(
                    R.string.media_projection_entry_cast_permission_dialog_continue_entire_screen
                )
            )
    }

    @Test
    fun startButtonText_singleAppSelected() {
        setUpAndShowDialog()
        onSpinnerItemSelected(SINGLE_APP)

        val startButtonText = dialog.requireViewById<TextView>(android.R.id.button1).text

        assertThat(startButtonText)
            .isEqualTo(
                context.getString(
                    R.string.media_projection_entry_generic_permission_dialog_continue_single_app
                )
            )
    }

    private fun setUpAndShowDialog(
        mediaProjectionConfig: MediaProjectionConfig? = null,
        overrideDisableSingleAppOption: Boolean = false,
    ) {
        val delegate =
            SystemCastPermissionDialogDelegate(
                context,
                mediaProjectionConfig,
                onStartRecordingClicked = {},
                onCancelClicked = {},
                appName,
                overrideDisableSingleAppOption,
                hostUid = 12345,
                mediaProjectionMetricsLogger = mock<MediaProjectionMetricsLogger>(),
            )

        dialog = AlertDialogWithDelegate(context, R.style.Theme_SystemUI_Dialog, delegate)
        SystemUIDialog.applyFlags(dialog)
        SystemUIDialog.setDialogSize(dialog)

        dialog.window?.addSystemFlags(
            WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
        )

        delegate.onCreate(dialog, savedInstanceState = null)
        dialog.show()
    }

    private fun onSpinnerItemSelected(position: Int) {
        val spinner = dialog.requireViewById<Spinner>(R.id.screen_share_mode_options)
        checkNotNull(spinner.onItemSelectedListener)
            .onItemSelected(spinner, mock(), position, /* id= */ 0)
    }
}
