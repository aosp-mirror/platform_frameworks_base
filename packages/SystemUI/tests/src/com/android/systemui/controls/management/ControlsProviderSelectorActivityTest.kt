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

package com.android.systemui.controls.management

import android.content.Intent
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.intercepting.SingleActivityFactory
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsProviderSelectorActivityTest : SysuiTestCase() {
    @Main private val executor: Executor = MoreExecutors.directExecutor()

    @Background private val backExecutor: Executor = MoreExecutors.directExecutor()

    @Mock lateinit var listingController: ControlsListingController

    @Mock lateinit var controlsController: ControlsController

    @Mock lateinit var userTracker: UserTracker

    @Mock lateinit var uiController: ControlsUiController

    private lateinit var controlsProviderSelectorActivity: ControlsProviderSelectorActivity_Factory
    private var latch: CountDownLatch = CountDownLatch(1)

    @Mock private lateinit var mockDispatcher: OnBackInvokedDispatcher
    @Captor private lateinit var captureCallback: ArgumentCaptor<OnBackInvokedCallback>

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            object :
                SingleActivityFactory<TestableControlsProviderSelectorActivity>(
                    TestableControlsProviderSelectorActivity::class.java
                ) {
                override fun create(intent: Intent?): TestableControlsProviderSelectorActivity {
                    return TestableControlsProviderSelectorActivity(
                        executor,
                        backExecutor,
                        listingController,
                        controlsController,
                        userTracker,
                        uiController,
                        mockDispatcher,
                        latch
                    )
                }
            },
            false,
            false
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val intent = Intent()
        intent.putExtra(ControlsProviderSelectorActivity.BACK_SHOULD_EXIT, true)
        activityRule.launchActivity(intent)
    }

    @Test
    fun testBackCallbackRegistrationAndUnregistration() {
        // 1. ensure that launching the activity results in it registering a callback
        verify(mockDispatcher)
            .registerOnBackInvokedCallback(
                ArgumentMatchers.eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT),
                captureCallback.capture()
            )
        activityRule.finishActivity()
        latch.await() // ensure activity is finished
        // 2. ensure that when the activity is finished, it unregisters the same callback
        verify(mockDispatcher).unregisterOnBackInvokedCallback(captureCallback.value)
    }

    public class TestableControlsProviderSelectorActivity(
        executor: Executor,
        backExecutor: Executor,
        listingController: ControlsListingController,
        controlsController: ControlsController,
        userTracker: UserTracker,
        uiController: ControlsUiController,
        private val mockDispatcher: OnBackInvokedDispatcher,
        private val latch: CountDownLatch
    ) :
        ControlsProviderSelectorActivity(
            executor,
            backExecutor,
            listingController,
            controlsController,
            userTracker,
            uiController
        ) {
        override fun getOnBackInvokedDispatcher(): OnBackInvokedDispatcher {
            return mockDispatcher
        }

        override fun onStop() {
            super.onStop()
            // ensures that test runner thread does not proceed until ui thread is done
            latch.countDown()
        }
    }
}
