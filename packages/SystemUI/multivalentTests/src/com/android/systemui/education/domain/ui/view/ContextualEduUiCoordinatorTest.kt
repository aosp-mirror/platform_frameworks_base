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

import android.app.Dialog
import android.app.Notification
import android.app.NotificationManager
import android.content.applicationContext
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.contextualeducation.GestureType.HOME
import com.android.systemui.contextualeducation.GestureType.OVERVIEW
import com.android.systemui.education.data.repository.fakeEduClock
import com.android.systemui.education.domain.interactor.KeyboardTouchpadEduInteractor
import com.android.systemui.education.domain.interactor.contextualEducationInteractor
import com.android.systemui.education.domain.interactor.keyboardTouchpadEduInteractor
import com.android.systemui.education.ui.view.ContextualEduUiCoordinator
import com.android.systemui.education.ui.viewmodel.ContextualEduViewModel
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContextualEduUiCoordinatorTest(private val gestureType: GestureType) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val interactor = kosmos.contextualEducationInteractor
    private val eduClock = kosmos.fakeEduClock
    private val minDurationForNextEdu =
        KeyboardTouchpadEduInteractor.minIntervalBetweenEdu + 1.seconds
    private lateinit var underTest: ContextualEduUiCoordinator
    private lateinit var previousDialog: Dialog
    @Mock private lateinit var dialog: Dialog
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var accessibilityManagerWrapper: AccessibilityManagerWrapper
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    private var toastContent = ""
    private val timeoutMillis = 5000L

    @Before
    fun setUp() {
        testScope.launch {
            interactor.updateKeyboardFirstConnectionTime()
            interactor.updateTouchpadFirstConnectionTime()
        }

        whenever(accessibilityManagerWrapper.getRecommendedTimeoutMillis(any(), any()))
            .thenReturn(timeoutMillis.toInt())

        val viewModel =
            ContextualEduViewModel(
                kosmos.applicationContext.resources,
                kosmos.keyboardTouchpadEduInteractor,
                accessibilityManagerWrapper,
            )

        underTest =
            ContextualEduUiCoordinator(
                kosmos.applicationCoroutineScope,
                viewModel,
                kosmos.applicationContext,
                notificationManager,
            ) { model ->
                toastContent = model.message
                previousDialog = dialog
                dialog = mock<Dialog>()
                dialog
            }
        underTest.start()
        kosmos.keyboardTouchpadEduInteractor.start()
    }

    @Test
    fun showDialogOnNewEdu() =
        testScope.runTest {
            triggerEducation(gestureType)
            verify(dialog).show()
        }

    @Test
    fun showNotificationOn2ndEdu() =
        testScope.runTest {
            triggerEducation(gestureType)
            eduClock.offset(minDurationForNextEdu)
            triggerEducation(gestureType)
            verify(notificationManager).notifyAsUser(any(), anyInt(), any(), any())
        }

    @Test
    fun dismissDialogAfterTimeout() =
        testScope.runTest {
            triggerEducation(gestureType)
            advanceTimeBy(timeoutMillis + 1)
            verify(dialog).dismiss()
        }

    @Test
    fun dismissPreviousDialogOnNewDialog() =
        testScope.runTest {
            triggerEducation(BACK)
            triggerEducation(HOME)
            verify(previousDialog).dismiss()
        }

    @Test
    fun verifyEduToastContent() =
        testScope.runTest {
            triggerEducation(gestureType)

            val expectedContent =
                when (gestureType) {
                    BACK -> R.string.back_edu_toast_content
                    HOME -> R.string.home_edu_toast_content
                    OVERVIEW -> R.string.overview_edu_toast_content
                    ALL_APPS -> R.string.all_apps_edu_toast_content
                }

            assertThat(toastContent).isEqualTo(context.getString(expectedContent))
        }

    @Test
    fun verifyEduNotificationContent() =
        testScope.runTest {
            val notificationCaptor = ArgumentCaptor.forClass(Notification::class.java)
            triggerEducation(gestureType)

            eduClock.offset(minDurationForNextEdu)
            triggerEducation(gestureType)

            verify(notificationManager)
                .notifyAsUser(any(), anyInt(), notificationCaptor.capture(), any())

            val expectedTitle =
                when (gestureType) {
                    BACK -> R.string.back_edu_notification_title
                    HOME -> R.string.home_edu_notification_title
                    OVERVIEW -> R.string.overview_edu_notification_title
                    ALL_APPS -> R.string.all_apps_edu_notification_title
                }

            val expectedContent =
                when (gestureType) {
                    BACK -> R.string.back_edu_notification_content
                    HOME -> R.string.home_edu_notification_content
                    OVERVIEW -> R.string.overview_edu_notification_content
                    ALL_APPS -> R.string.all_apps_edu_notification_content
                }

            val expectedTutorialClassName =
                when (gestureType) {
                    OVERVIEW -> TUTORIAL_ACTION
                    else -> KeyboardTouchpadTutorialActivity::class.qualifiedName
                }

            verifyNotificationContent(
                expectedTitle,
                expectedContent,
                expectedTutorialClassName,
                notificationCaptor.value,
            )
        }

    private fun verifyNotificationContent(
        titleResId: Int,
        contentResId: Int,
        expectedTutorialClassName: String?,
        notification: Notification,
    ) {
        val expectedContent = context.getString(contentResId)
        val expectedTitle = context.getString(titleResId)
        val actualContent = notification.getString(Notification.EXTRA_TEXT)
        val actualTitle = notification.getString(Notification.EXTRA_TITLE)
        assertThat(actualContent).isEqualTo(expectedContent)
        assertThat(actualTitle).isEqualTo(expectedTitle)
        val actualTutorialClassName =
            notification.contentIntent.intent.component?.className
                ?: notification.contentIntent.intent.action
        assertThat(actualTutorialClassName).isEqualTo(expectedTutorialClassName)
    }

    private fun Notification.getString(key: String): String =
        this.extras?.getCharSequence(key).toString()

    private suspend fun TestScope.triggerEducation(gestureType: GestureType) {
        for (i in 1..KeyboardTouchpadEduInteractor.MAX_SIGNAL_COUNT) {
            interactor.incrementSignalCount(gestureType)
        }
        runCurrent()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getGestureTypes(): List<GestureType> {
            return listOf(BACK, HOME, OVERVIEW, ALL_APPS)
        }

        private const val TUTORIAL_ACTION: String = "com.android.systemui.action.TOUCHPAD_TUTORIAL"
    }
}
