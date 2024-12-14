/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.domain.ui.view

import android.app.Notification
import android.app.NotificationManager
import android.content.applicationContext
import android.widget.Toast
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.education.data.repository.fakeEduClock
import com.android.systemui.education.domain.interactor.KeyboardTouchpadEduInteractor
import com.android.systemui.education.domain.interactor.contextualEducationInteractor
import com.android.systemui.education.domain.interactor.keyboardTouchpadEduInteractor
import com.android.systemui.education.ui.view.ContextualEduUiCoordinator
import com.android.systemui.education.ui.viewmodel.ContextualEduViewModel
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContextualEduUiCoordinatorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val interactor = kosmos.contextualEducationInteractor
    private val eduClock = kosmos.fakeEduClock
    private val minDurationForNextEdu =
        KeyboardTouchpadEduInteractor.minIntervalBetweenEdu + 1.seconds
    private lateinit var underTest: ContextualEduUiCoordinator
    @Mock private lateinit var toast: Toast
    @Mock private lateinit var notificationManager: NotificationManager
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    private var toastContent = ""

    @Before
    fun setUp() {
        testScope.launch {
            interactor.updateKeyboardFirstConnectionTime()
            interactor.updateTouchpadFirstConnectionTime()
        }

        val viewModel =
            ContextualEduViewModel(
                kosmos.applicationContext.resources,
                kosmos.keyboardTouchpadEduInteractor
            )
        underTest =
            ContextualEduUiCoordinator(
                kosmos.applicationCoroutineScope,
                viewModel,
                kosmos.applicationContext,
                notificationManager
            ) { content ->
                toastContent = content
                toast
            }
        underTest.start()
        kosmos.keyboardTouchpadEduInteractor.start()
    }

    @Test
    fun showToastOnNewEdu() =
        testScope.runTest {
            triggerEducation(BACK)
            verify(toast).show()
        }

    @Test
    fun showNotificationOn2ndEdu() =
        testScope.runTest {
            triggerEducation(BACK)
            eduClock.offset(minDurationForNextEdu)
            triggerEducation(BACK)
            verify(notificationManager).notifyAsUser(any(), anyInt(), any(), any())
        }

    @Test
    fun verifyBackEduToastContent() =
        testScope.runTest {
            triggerEducation(BACK)
            assertThat(toastContent).isEqualTo(context.getString(R.string.back_edu_toast_content))
        }

    @Test
    fun verifyBackEduNotificationContent() =
        testScope.runTest {
            val notificationCaptor = ArgumentCaptor.forClass(Notification::class.java)
            triggerEducation(BACK)

            eduClock.offset(minDurationForNextEdu)
            triggerEducation(BACK)

            verify(notificationManager)
                .notifyAsUser(any(), anyInt(), notificationCaptor.capture(), any())
            verifyNotificationContent(
                R.string.back_edu_notification_title,
                R.string.back_edu_notification_content,
                notificationCaptor.value
            )
        }

    private fun verifyNotificationContent(
        titleResId: Int,
        contentResId: Int,
        notification: Notification
    ) {
        val expectedContent = context.getString(contentResId)
        val expectedTitle = context.getString(titleResId)
        val actualContent = notification.getString(Notification.EXTRA_TEXT)
        val actualTitle = notification.getString(Notification.EXTRA_TITLE)
        assertThat(actualContent).isEqualTo(expectedContent)
        assertThat(actualTitle).isEqualTo(expectedTitle)
    }

    private fun Notification.getString(key: String): String =
        this.extras?.getCharSequence(key).toString()

    private suspend fun TestScope.triggerEducation(gestureType: GestureType) {
        for (i in 1..KeyboardTouchpadEduInteractor.MAX_SIGNAL_COUNT) {
            interactor.incrementSignalCount(gestureType)
        }
        runCurrent()
    }
}
