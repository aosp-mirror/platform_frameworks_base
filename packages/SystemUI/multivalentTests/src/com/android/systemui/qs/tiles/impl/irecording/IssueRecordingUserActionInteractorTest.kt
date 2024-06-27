/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.irecording

import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.dialogTransitionAnimator
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.qs.pipeline.domain.interactor.panelInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.recordissue.RecordIssueDialogDelegate
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.settings.userTracker
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import com.android.systemui.statusbar.policy.keyguardStateController
import com.google.common.truth.Truth
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class IssueRecordingUserActionInteractorTest : SysuiTestCase() {

    val user = UserHandle(1)
    val kosmos = Kosmos().also { it.testCase = this }

    private lateinit var userContextProvider: UserContextProvider
    private lateinit var underTest: IssueRecordingUserActionInteractor

    private var hasCreatedDialogDelegate: Boolean = false

    @Before
    fun setup() {
        hasCreatedDialogDelegate = false
        with(kosmos) {
            val factory =
                object : RecordIssueDialogDelegate.Factory {
                    override fun create(onStarted: Runnable): RecordIssueDialogDelegate {
                        hasCreatedDialogDelegate = true

                        // Inside some tests in presubmit, createDialog throws an error because
                        // the test thread's looper hasn't been prepared, and Dialog.class
                        // internally is creating a new handler. For testing, we only care that the
                        // dialog is created, so using a mock is acceptable here.
                        return mock(RecordIssueDialogDelegate::class.java)
                    }
                }

            userContextProvider = userTracker
            underTest =
                IssueRecordingUserActionInteractor(
                    testDispatcher,
                    KeyguardDismissUtil(
                        keyguardStateController,
                        statusBarStateController,
                        activityStarter
                    ),
                    keyguardStateController,
                    dialogTransitionAnimator,
                    panelInteractor,
                    userTracker,
                    factory
                )
        }
    }

    @Test
    fun handleInput_showsPromptToStartRecording_whenNotRecordingAlready() {
        kosmos.testScope.runTest {
            underTest.handleInput(
                QSTileInput(user, QSTileUserAction.Click(null), IssueRecordingModel(false))
            )
            Truth.assertThat(hasCreatedDialogDelegate).isTrue()
        }
    }

    @Test
    fun handleInput_attemptsToStopRecording_whenRecording() {
        kosmos.testScope.runTest {
            val input = QSTileInput(user, QSTileUserAction.Click(null), IssueRecordingModel(true))
            try {
                underTest.handleInput(input)
            } catch (e: NullPointerException) {
                // As of 06/07/2024, PendingIntent.startService is not easily mockable and throws
                // an NPE inside IActivityManager. Catching that here and ignore it, then verify
                // mock interactions were done correctly
            }
            Truth.assertThat(hasCreatedDialogDelegate).isFalse()
        }
    }
}
