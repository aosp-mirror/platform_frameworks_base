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

package com.android.systemui.user

import android.app.Application
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.Executor

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class UserSwitcherActivityTest : SysuiTestCase() {
    @Mock
    private lateinit var activity: UserSwitcherActivity
    @Mock
    private lateinit var userSwitcherController: UserSwitcherController
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var layoutInflater: LayoutInflater
    @Mock
    private lateinit var falsingCollector: FalsingCollector
    @Mock
    private lateinit var falsingManager: FalsingManager
    @Mock
    private lateinit var userManager: UserManager
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var flags: FeatureFlags
    @Mock
    private lateinit var viewModelFactoryLazy: dagger.Lazy<UserSwitcherViewModel.Factory>
    @Mock
    private lateinit var onBackDispatcher: OnBackInvokedDispatcher
    @Mock
    private lateinit var decorView: View
    @Mock
    private lateinit var window: Window
    @Mock
    private lateinit var userSwitcherRootView: UserSwitcherRootView
    @Captor
    private lateinit var onBackInvokedCallback: ArgumentCaptor<OnBackInvokedCallback>
    var isFinished = false

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        activity = spy(object : UserSwitcherActivity(
            userSwitcherController,
            broadcastDispatcher,
            falsingCollector,
            falsingManager,
            userManager,
            userTracker,
            flags,
            viewModelFactoryLazy,
        ) {
            override fun getOnBackInvokedDispatcher() = onBackDispatcher
            override fun getMainExecutor(): Executor = FakeExecutor(FakeSystemClock())
            override fun finish() {
                isFinished = true
            }
        })
        `when`(activity.window).thenReturn(window)
        `when`(window.decorView).thenReturn(decorView)
        `when`(activity.findViewById<UserSwitcherRootView>(R.id.user_switcher_root))
                .thenReturn(userSwitcherRootView)
        `when`(activity.findViewById<View>(R.id.cancel)).thenReturn(mock(View::class.java))
        `when`(activity.findViewById<View>(R.id.add)).thenReturn(mock(View::class.java))
        `when`(activity.application).thenReturn(mock(Application::class.java))
        doNothing().`when`(activity).setContentView(anyInt())
    }

    @Test
    fun testMaxColumns() {
        assertThat(activity.getMaxColumns(3)).isEqualTo(4)
        assertThat(activity.getMaxColumns(4)).isEqualTo(4)
        assertThat(activity.getMaxColumns(5)).isEqualTo(3)
        assertThat(activity.getMaxColumns(6)).isEqualTo(3)
        assertThat(activity.getMaxColumns(7)).isEqualTo(4)
        assertThat(activity.getMaxColumns(9)).isEqualTo(5)
    }

    @Test
    fun onCreate_callbackRegistration() {
        activity.createActivity()
        verify(onBackDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT), any())

        activity.destroyActivity()
        verify(onBackDispatcher).unregisterOnBackInvokedCallback(any())
    }

    @Test
    fun onBackInvokedCallback_finishesActivity() {
        activity.createActivity()
        verify(onBackDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT), onBackInvokedCallback.capture())

        onBackInvokedCallback.value.onBackInvoked()
        assertThat(isFinished).isTrue()
    }
}
