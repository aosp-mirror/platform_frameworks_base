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

import android.content.Intent
import android.os.UserHandle
import android.testing.TestableLooper
import android.view.View
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Dependency
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorActivity
import com.android.systemui.mediaprojection.permission.ENTIRE_SCREEN
import com.android.systemui.mediaprojection.permission.SINGLE_APP
import com.android.systemui.model.SysUiState
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class ScreenRecordPermissionDialogDelegateTest : SysuiTestCase() {

    @Mock private lateinit var starter: ActivityStarter
    @Mock private lateinit var controller: RecordingController
    @Mock private lateinit var userContextProvider: UserContextProvider
    @Mock private lateinit var onStartRecordingClicked: Runnable
    @Mock private lateinit var mediaProjectionMetricsLogger: MediaProjectionMetricsLogger

    private lateinit var dialog: SystemUIDialog

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val systemUIDialogFactory =
            SystemUIDialog.Factory(
                context,
                Dependency.get(SystemUIDialogManager::class.java),
                Dependency.get(SysUiState::class.java),
                Dependency.get(BroadcastDispatcher::class.java),
                Dependency.get(DialogTransitionAnimator::class.java),
            )

        val delegate =
            ScreenRecordPermissionDialogDelegate(
                UserHandle.of(0),
                TEST_HOST_UID,
                controller,
                starter,
                userContextProvider,
                onStartRecordingClicked,
                mediaProjectionMetricsLogger,
                systemUIDialogFactory,
                context,
            )
        dialog = delegate.createDialog()
    }

    @After
    fun teardown() {
        if (::dialog.isInitialized) {
            dismissDialog()
        }
    }

    @Test
    fun testShowDialog_partialScreenSharingEnabled_optionsSpinnerIsVisible() {
        showDialog()

        val visibility = dialog.requireViewById<Spinner>(R.id.screen_share_mode_options).visibility
        assertThat(visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testShowDialog_singleAppSelected_showTapsIsGone() {
        showDialog()
        onSpinnerItemSelected(SINGLE_APP)

        val visibility = dialog.requireViewById<View>(R.id.show_taps).visibility
        assertThat(visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testShowDialog_entireScreenSelected_showTapsIsVisible() {
        showDialog()
        onSpinnerItemSelected(ENTIRE_SCREEN)

        val visibility = dialog.requireViewById<View>(R.id.show_taps).visibility
        assertThat(visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun startButtonText_entireScreenSelected() {
        showDialog()

        onSpinnerItemSelected(ENTIRE_SCREEN)

        assertThat(getStartButton().text)
            .isEqualTo(
                context.getString(R.string.screenrecord_permission_dialog_continue_entire_screen)
            )
    }

    @Test
    fun startButtonText_singleAppSelected() {
        showDialog()

        onSpinnerItemSelected(SINGLE_APP)

        assertThat(getStartButton().text)
            .isEqualTo(
                context.getString(
                    R.string.media_projection_entry_generic_permission_dialog_continue_single_app
                )
            )
    }

    @Test
    fun startClicked_singleAppSelected_passesHostUidToAppSelector() {
        showDialog()
        onSpinnerItemSelected(SINGLE_APP)

        clickOnStart()

        assertExtraPassedToAppSelector(
            extraKey = MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_UID,
            value = TEST_HOST_UID
        )
    }

    @Test
    fun showDialog_dialogIsShowing() {
        showDialog()

        assertThat(dialog.isShowing).isTrue()
    }

    @Test
    fun showDialog_singleAppIsDefault() {
        showDialog()

        val spinner = dialog.requireViewById<Spinner>(R.id.screen_share_mode_options)
        val singleApp =
            context.getString(R.string.screenrecord_permission_dialog_option_text_single_app)
        assertEquals(spinner.adapter.getItem(0), singleApp)
    }

    @Test
    fun showDialog_cancelClicked_dialogIsDismissed() {
        showDialog()

        clickOnCancel()

        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun showDialog_cancelClickedMultipleTimes_projectionRequestCancelledIsLoggedOnce() {
        showDialog()

        clickOnCancel()
        clickOnCancel()

        verify(mediaProjectionMetricsLogger).notifyProjectionRequestCancelled(TEST_HOST_UID)
    }

    @Test
    fun dismissDialog_dismissCalledMultipleTimes_projectionRequestCancelledIsLoggedOnce() {
        showDialog()

        dismissDialog()
        dismissDialog()

        verify(mediaProjectionMetricsLogger).notifyProjectionRequestCancelled(TEST_HOST_UID)
    }

    @Test
    fun showDialog_singleAppSelected_clickOnStart_projectionRequestCancelledIsNotLoggedOnce() {
        showDialog()
        onSpinnerItemSelected(SINGLE_APP)

        clickOnStart()

        verify(mediaProjectionMetricsLogger, never())
            .notifyProjectionRequestCancelled(TEST_HOST_UID)
    }

    private fun showDialog() {
        dialog.show()
    }

    private fun dismissDialog() {
        dialog.dismiss()
    }

    private fun clickOnCancel() {
        dialog.requireViewById<View>(android.R.id.button2).performClick()
    }

    private fun getStartButton() = dialog.requireViewById<TextView>(android.R.id.button1)

    private fun clickOnStart() {
        getStartButton().performClick()
    }

    private fun onSpinnerItemSelected(position: Int) {
        val spinner = dialog.requireViewById<Spinner>(R.id.screen_share_mode_options)
        checkNotNull(spinner.onItemSelectedListener)
            .onItemSelected(spinner, mock(), position, /* id= */ 0)
    }

    private fun assertExtraPassedToAppSelector(extraKey: String, value: Int) {
        val intentCaptor = argumentCaptor<Intent>()
        verify(starter).startActivity(intentCaptor.capture(), /* dismissShade= */ eq(true))

        val intent = intentCaptor.value
        assertThat(intent.extras!!.getInt(extraKey)).isEqualTo(value)
    }

    companion object {
        private const val TEST_HOST_UID = 12345
    }
}
