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

package com.android.systemui.inputdevice.tutorial.domain.interactor

import android.app.Notification
import android.app.NotificationManager
import androidx.annotation.StringRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.inputdevice.tutorial.data.repository.TutorialSchedulerRepository
import com.android.systemui.inputdevice.tutorial.ui.TutorialNotificationCoordinator
import com.android.systemui.keyboard.data.repository.FakeKeyboardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.touchpad.data.repository.FakeTouchpadRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class TutorialNotificationCoordinatorTest : SysuiTestCase() {

    private lateinit var underTest: TutorialNotificationCoordinator
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val keyboardRepository = FakeKeyboardRepository()
    private val touchpadRepository = FakeTouchpadRepository()
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var repository: TutorialSchedulerRepository
    @Mock private lateinit var notificationManager: NotificationManager
    @Captor private lateinit var notificationCaptor: ArgumentCaptor<Notification>
    @get:Rule val rule = MockitoJUnit.rule()

    @Before
    fun setup() {
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined)
        repository =
            TutorialSchedulerRepository(
                context,
                dataStoreScope,
                dataStoreName = "TutorialNotificationCoordinatorTest"
            )
        val interactor =
            TutorialSchedulerInteractor(keyboardRepository, touchpadRepository, repository)
        underTest =
            TutorialNotificationCoordinator(
                testScope.backgroundScope,
                context,
                interactor,
                notificationManager
            )
        notificationCaptor = ArgumentCaptor.forClass(Notification::class.java)
        underTest.start()
    }

    @After
    fun clear() {
        runBlocking { repository.clearDataStore() }
        dataStoreScope.cancel()
    }

    @Test
    fun showKeyboardNotification() =
        testScope.runTest {
            keyboardRepository.setIsAnyKeyboardConnected(true)
            advanceTimeBy(LAUNCH_DELAY)
            verifyNotification(
                R.string.launch_keyboard_tutorial_notification_title,
                R.string.launch_keyboard_tutorial_notification_content
            )
        }

    @Test
    fun showTouchpadNotification() =
        testScope.runTest {
            touchpadRepository.setIsAnyTouchpadConnected(true)
            advanceTimeBy(LAUNCH_DELAY)
            verifyNotification(
                R.string.launch_touchpad_tutorial_notification_title,
                R.string.launch_touchpad_tutorial_notification_content
            )
        }

    @Test
    fun showKeyboardTouchpadNotification() =
        testScope.runTest {
            keyboardRepository.setIsAnyKeyboardConnected(true)
            touchpadRepository.setIsAnyTouchpadConnected(true)
            advanceTimeBy(LAUNCH_DELAY)
            verifyNotification(
                R.string.launch_keyboard_touchpad_tutorial_notification_title,
                R.string.launch_keyboard_touchpad_tutorial_notification_content
            )
        }

    @Test
    fun doNotShowNotification() =
        testScope.runTest {
            advanceTimeBy(LAUNCH_DELAY)
            verify(notificationManager, never()).notify(eq(TAG), eq(NOTIFICATION_ID), any())
        }

    private fun verifyNotification(@StringRes titleResId: Int, @StringRes contentResId: Int) {
        verify(notificationManager)
            .notify(eq(TAG), eq(NOTIFICATION_ID), notificationCaptor.capture())
        val notification = notificationCaptor.value
        val actualTitle = notification.getString(Notification.EXTRA_TITLE)
        val actualContent = notification.getString(Notification.EXTRA_TEXT)
        assertThat(actualTitle).isEqualTo(context.getString(titleResId))
        assertThat(actualContent).isEqualTo(context.getString(contentResId))
    }

    private fun Notification.getString(key: String): String =
        this.extras?.getCharSequence(key).toString()

    companion object {
        private const val TAG = "TutorialSchedulerInteractor"
        private const val NOTIFICATION_ID = 5566
        private val LAUNCH_DELAY = 72.hours
    }
}
