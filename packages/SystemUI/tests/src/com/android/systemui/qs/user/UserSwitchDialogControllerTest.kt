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

package com.android.systemui.qs.user

import android.content.Intent
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PseudoGridView
import com.android.systemui.qs.tiles.UserDetailView
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatcher
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.argThat
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.function.Consumer

@SmallTest
@RunWith(AndroidTestingRunner::class)
class UserSwitchDialogControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var dialog: UserDialog
    @Mock
    private lateinit var falsingManager: FalsingManager
    @Mock
    private lateinit var settingsView: View
    @Mock
    private lateinit var doneView: View
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var userDetailViewAdapter: UserDetailView.Adapter
    @Mock
    private lateinit var launchView: View
    @Mock
    private lateinit var gridView: PseudoGridView
    @Captor
    private lateinit var clickCaptor: ArgumentCaptor<View.OnClickListener>

    private lateinit var controller: UserSwitchDialogController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(dialog.settingsButton).thenReturn(settingsView)
        `when`(dialog.doneButton).thenReturn(doneView)
        `when`(dialog.grid).thenReturn(gridView)

        `when`(launchView.context).thenReturn(mContext)

        controller = UserSwitchDialogController(
                { userDetailViewAdapter },
                activityStarter,
                falsingManager,
                { dialog }
        )
    }

    @Test
    fun showDialog_callsDialogShow() {
        controller.showDialog(launchView)
        verify(dialog).show()
    }

    @Test
    fun createCalledBeforeDoneButton() {
        controller.showDialog(launchView)
        val inOrder = inOrder(dialog)
        inOrder.verify(dialog).create()
        inOrder.verify(dialog).doneButton
    }

    @Test
    fun createCalledBeforeSettingsButton() {
        controller.showDialog(launchView)
        val inOrder = inOrder(dialog)
        inOrder.verify(dialog).create()
        inOrder.verify(dialog).settingsButton
    }

    @Test
    fun createCalledBeforeGrid() {
        controller.showDialog(launchView)
        val inOrder = inOrder(dialog)
        inOrder.verify(dialog).create()
        inOrder.verify(dialog).grid
    }

    @Test
    fun dialog_showForAllUsers() {
        controller.showDialog(launchView)
        verify(dialog).setShowForAllUsers(true)
    }

    @Test
    fun dialog_cancelOnTouchOutside() {
        controller.showDialog(launchView)
        verify(dialog).setCanceledOnTouchOutside(true)
    }

    @Test
    fun adapterAndGridLinked() {
        controller.showDialog(launchView)
        verify(userDetailViewAdapter).linkToViewGroup(gridView)
    }

    @Test
    fun clickDoneButton_dismiss() {
        controller.showDialog(launchView)

        verify(doneView).setOnClickListener(capture(clickCaptor))

        clickCaptor.value.onClick(doneView)

        verify(activityStarter, never()).postStartActivityDismissingKeyguard(any(), anyInt())
        verify(dialog).dismiss()
    }

    @Test
    fun clickSettingsButton_noFalsing_opensSettingsAndDismisses() {
        `when`(falsingManager.isFalseTap(anyInt())).thenReturn(false)

        controller.showDialog(launchView)

        verify(settingsView).setOnClickListener(capture(clickCaptor))

        clickCaptor.value.onClick(settingsView)

        verify(activityStarter)
                .postStartActivityDismissingKeyguard(
                        argThat(IntentMatcher(Settings.ACTION_USER_SETTINGS)),
                        eq(0)
                )
        verify(dialog).dismiss()
    }

    @Test
    fun clickSettingsButton_Falsing_notOpensSettingsAndDismisses() {
        `when`(falsingManager.isFalseTap(anyInt())).thenReturn(true)

        controller.showDialog(launchView)

        verify(settingsView).setOnClickListener(capture(clickCaptor))

        clickCaptor.value.onClick(settingsView)

        verify(activityStarter, never()).postStartActivityDismissingKeyguard(any(), anyInt())
        verify(dialog).dismiss()
    }

    @Test
    fun callbackFromDetailView_dismissesDialog() {
        val captor = argumentCaptor<Consumer<UserSwitcherController.UserRecord>>()

        controller.showDialog(launchView)
        verify(userDetailViewAdapter).injectCallback(capture(captor))

        captor.value.accept(mock(UserSwitcherController.UserRecord::class.java))

        verify(dialog).dismiss()
    }

    private class IntentMatcher(private val action: String) : ArgumentMatcher<Intent> {
        override fun matches(argument: Intent?): Boolean {
            return argument?.action == action
        }
    }
}