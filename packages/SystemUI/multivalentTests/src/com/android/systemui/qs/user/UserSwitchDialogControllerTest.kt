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

import android.content.DialogInterface
import android.content.Intent
import android.provider.Settings
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.PseudoGridView
import com.android.systemui.qs.QSUserSwitcherEvent
import com.android.systemui.qs.tiles.UserDetailView
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatcher
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.argThat
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class UserSwitchDialogControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var dialogFactory: SystemUIDialog.Factory
    @Mock
    private lateinit var dialog: SystemUIDialog
    @Mock
    private lateinit var falsingManager: FalsingManager
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var userDetailViewAdapter: UserDetailView.Adapter
    @Mock
    private lateinit var launchExpandable: Expandable
    @Mock
    private lateinit var neutralButton: Button
    @Mock
    private lateinit var mDialogTransitionAnimator: DialogTransitionAnimator
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Captor
    private lateinit var clickCaptor: ArgumentCaptor<DialogInterface.OnClickListener>

    private lateinit var controller: UserSwitchDialogController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(dialog.context).thenReturn(mContext)
        whenever(dialogFactory.create()).thenReturn(dialog)

        controller = UserSwitchDialogController(
            { userDetailViewAdapter },
            activityStarter,
            falsingManager,
            mDialogTransitionAnimator,
            uiEventLogger,
            dialogFactory
        )
    }

    @Test
    fun showDialog_callsDialogShow() {
        val launchController = mock<DialogTransitionAnimator.Controller>()
        `when`(launchExpandable.dialogTransitionController(any())).thenReturn(launchController)
        controller.showDialog(context, launchExpandable)
        verify(mDialogTransitionAnimator).show(eq(dialog), eq(launchController), anyBoolean())
        verify(uiEventLogger).log(QSUserSwitcherEvent.QS_USER_DETAIL_OPEN)
    }

    @Test
    fun dialog_showForAllUsers() {
        controller.showDialog(context, launchExpandable)
        verify(dialog).setShowForAllUsers(true)
    }

    @Test
    fun dialog_cancelOnTouchOutside() {
        controller.showDialog(context, launchExpandable)
        verify(dialog).setCanceledOnTouchOutside(true)
    }

    @Test
    fun adapterAndGridLinked() {
        controller.showDialog(context, launchExpandable)
        verify(userDetailViewAdapter).linkToViewGroup(any<PseudoGridView>())
    }

    @Test
    fun doneButtonLogsCorrectly() {
        controller.showDialog(context, launchExpandable)

        verify(dialog).setPositiveButton(anyInt(), capture(clickCaptor))

        clickCaptor.value.onClick(dialog, DialogInterface.BUTTON_NEUTRAL)

        verify(uiEventLogger).log(QSUserSwitcherEvent.QS_USER_DETAIL_CLOSE)
    }

    @Test
    fun clickSettingsButton_noFalsing_opensSettings() {
        `when`(falsingManager.isFalseTap(anyInt())).thenReturn(false)

        controller.showDialog(context, launchExpandable)

        verify(dialog)
            .setNeutralButton(anyInt(), capture(clickCaptor), eq(false) /* dismissOnClick */)
        `when`(dialog.getButton(DialogInterface.BUTTON_NEUTRAL)).thenReturn(neutralButton)

        clickCaptor.value.onClick(dialog, DialogInterface.BUTTON_NEUTRAL)

        verify(activityStarter)
            .postStartActivityDismissingKeyguard(
                argThat(IntentMatcher(Settings.ACTION_USER_SETTINGS)),
                eq(0),
                eq(null)
            )
        verify(uiEventLogger).log(QSUserSwitcherEvent.QS_USER_MORE_SETTINGS)
    }

    @Test
    fun clickSettingsButton_Falsing_notOpensSettings() {
        `when`(falsingManager.isFalseTap(anyInt())).thenReturn(true)

        controller.showDialog(context, launchExpandable)

        verify(dialog)
            .setNeutralButton(anyInt(), capture(clickCaptor), eq(false) /* dismissOnClick */)

        clickCaptor.value.onClick(dialog, DialogInterface.BUTTON_NEUTRAL)

        verify(activityStarter, never()).postStartActivityDismissingKeyguard(any(), anyInt())
    }

    private class IntentMatcher(private val action: String) : ArgumentMatcher<Intent> {
        override fun matches(argument: Intent?): Boolean {
            return argument?.action == action
        }
    }
}
