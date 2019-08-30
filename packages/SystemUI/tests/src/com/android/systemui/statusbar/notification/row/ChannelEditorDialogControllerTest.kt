/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row

import android.app.INotificationManager
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_NONE
import android.content.pm.ParceledListSlice
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME
import androidx.test.filters.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View

import com.android.systemui.SysuiTestCase

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ChannelEditorDialogControllerTest : SysuiTestCase() {

    private lateinit var controller: ChannelEditorDialogController
    private lateinit var channel1: NotificationChannel
    private lateinit var channel2: NotificationChannel
    private lateinit var channelDefault: NotificationChannel
    private lateinit var group: NotificationChannelGroup

    private val appIcon = ColorDrawable(Color.MAGENTA)

    @Mock
    private lateinit var mockNoMan: INotificationManager
    @Mock(answer = Answers.RETURNS_SELF)
    private lateinit var dialogBuilder: ChannelEditorDialog.Builder
    @Mock
    private lateinit var dialog: ChannelEditorDialog

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(dialogBuilder.build()).thenReturn(dialog)
        controller = ChannelEditorDialogController(mContext, mockNoMan, dialogBuilder)

        channel1 = NotificationChannel(TEST_CHANNEL, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT)
        channel2 = NotificationChannel(TEST_CHANNEL2, TEST_CHANNEL_NAME2, IMPORTANCE_DEFAULT)
        channelDefault = NotificationChannel(
                NotificationChannel.DEFAULT_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT)

        group = NotificationChannelGroup(TEST_GROUP_ID, TEST_GROUP_NAME)

        `when`(mockNoMan.getNotificationChannelGroupsForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), anyBoolean()))
                .thenReturn(ParceledListSlice(listOf(group)))

        `when`(mockNoMan.areNotificationsEnabledForPackage(eq(TEST_PACKAGE_NAME), eq(TEST_UID)))
                .thenReturn(true)
    }

    @Test
    fun testPrepareDialogForApp_noExtraChannels() {
        group.channels = listOf(channel1, channel2)
        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channel1, channel2), appIcon, clickListener)

        assertEquals(2, controller.providedChannels.size)
    }

    @Test
    fun testPrepareDialogForApp_onlyDefaultChannel() {
        group.addChannel(channelDefault)

        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channelDefault), appIcon, clickListener)

        assertEquals("No channels should be shown when there is only the miscellaneous channel",
                0, controller.providedChannels.size)
    }

    @Test
    fun testPrepareDialogForApp_noProvidedChannels_noException() {
        group.channels = listOf()

        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(), appIcon, clickListener)
    }

    @Test
    fun testPrepareDialogForApp_retrievesUpTo4Channels() {
        val channel3 = NotificationChannel("test_channel_3", "Test channel 3", IMPORTANCE_DEFAULT)
        val channel4 = NotificationChannel("test_channel_4", "Test channel 4", IMPORTANCE_DEFAULT)

        group.channels = listOf(channel1, channel2, channel3, channel4)

        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channel1), appIcon, clickListener)

        assertEquals("ChannelEditorDialog should fetch enough channels to show 4",
                4, controller.providedChannels.size)
    }

    @Test
    fun testApply_demoteChannel() {
        group.channels = listOf(channel1, channel2)
        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channel1, channel2), appIcon, clickListener)

        // propose an adjustment of channel1
        controller.proposeEditForChannel(channel1, IMPORTANCE_NONE)

        controller.apply()

        assertEquals("Proposed edits should take effect after apply",
                IMPORTANCE_NONE, channel1.importance)

        // Channel 2 shouldn't have changed
        assertEquals("Proposed edits should take effect after apply",
                IMPORTANCE_DEFAULT, channel2.importance)
    }

    @Test
    fun testApply_demoteApp() {
        group.channels = listOf(channel1, channel2)
        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channel1, channel2), appIcon, clickListener)

        controller.proposeSetAppNotificationsEnabled(false)
        controller.apply()

        verify(mockNoMan, times(1)).setNotificationsEnabledForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(false))
    }

    @Test
    fun testApply_promoteApp() {
        // App starts out disabled
        `when`(mockNoMan.areNotificationsEnabledForPackage(eq(TEST_PACKAGE_NAME), eq(TEST_UID)))
                .thenReturn(false)

        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channel1, channel2), appIcon, clickListener)
        controller.proposeSetAppNotificationsEnabled(true)
        controller.apply()

        verify(mockNoMan, times(1)).setNotificationsEnabledForPackage(
                eq(TEST_PACKAGE_NAME), eq(TEST_UID), eq(true))
    }

    @Test
    fun testSettingsClickListenerNull_noCrash() {
        // GIVEN editor dialog
        group.channels = listOf(channel1, channel2)
        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channel1, channel2), appIcon, null)

        // WHEN user taps settings
        // Pass in any old view, it should never actually be used
        controller.launchSettings(View(context))

        // THEN no crash
    }

    @Test
    fun testDoneButtonSaysDone_noChanges() {
        // GIVEN the editor dialog with no changes
        `when`(dialogBuilder.build()).thenReturn(dialog)

        group.channels = listOf(channel1, channel2)
        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channel1, channel2), appIcon, null)

        // WHEN the user proposes a change
        controller.proposeEditForChannel(channel1, IMPORTANCE_NONE)

        // THEN the "done" button has been updated to "apply"
        verify(dialog).updateDoneButtonText(true /* hasChanges */)
    }

    @Test
    fun testDoneButtonGoesBackToNormal_changeThenNoChange() {
        val inOrderDialog = Mockito.inOrder(dialog)
        // GIVEN the editor dialog with no changes
        `when`(dialogBuilder.build()).thenReturn(dialog)

        group.channels = listOf(channel1, channel2)
        controller.prepareDialogForApp(TEST_APP_NAME, TEST_PACKAGE_NAME, TEST_UID,
                setOf(channel1, channel2), appIcon, null)

        // WHEN the user proposes a change
        controller.proposeEditForChannel(channel1, IMPORTANCE_NONE)
        // and WHEN the user sets the importance back to its original value
        controller.proposeEditForChannel(channel1, channel1.importance)

        // THEN the "done" button has been changed back to done
        inOrderDialog.verify(dialog, times(1)).updateDoneButtonText(eq(true))
        inOrderDialog.verify(dialog, times(1)).updateDoneButtonText(eq(false))
    }

    private val clickListener = object : NotificationInfo.OnSettingsClickListener {
        override fun onClick(v: View, c: NotificationChannel, appUid: Int) {
        }
    }

    companion object {
        const val TEST_APP_NAME = "Test App Name"
        const val TEST_PACKAGE_NAME = "test_package"
        const val TEST_SYSTEM_PACKAGE_NAME = PRINT_SPOOLER_PACKAGE_NAME
        const val TEST_UID = 1
        const val MULTIPLE_CHANNEL_COUNT = 2
        const val TEST_CHANNEL = "test_channel"
        const val TEST_CHANNEL_NAME = "Test Channel Name"
        const val TEST_CHANNEL2 = "test_channel2"
        const val TEST_CHANNEL_NAME2 = "Test Channel Name2"
        const val TEST_GROUP_ID = "test_group_id"
        const val TEST_GROUP_NAME = "Test Group Name"
    }
}
