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

package com.android.systemui.communal.widgets

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class EditWidgetsActivityControllerTest : SysuiTestCase() {
    @Test
    fun activityLifecycle_stoppedWhenNotWaitingForResult() {
        val activity = mock<Activity>()
        val controller = EditWidgetsActivity.ActivityController(activity)

        val callbackCapture = argumentCaptor<ActivityLifecycleCallbacks>()
        verify(activity).registerActivityLifecycleCallbacks(callbackCapture.capture())

        callbackCapture.lastValue.onActivityStopped(activity)

        verify(activity).finish()
    }

    @Test
    fun activityLifecycle_notStoppedWhenNotWaitingForResult() {
        val activity = mock<Activity>()
        val controller = EditWidgetsActivity.ActivityController(activity)

        val callbackCapture = argumentCaptor<ActivityLifecycleCallbacks>()
        verify(activity).registerActivityLifecycleCallbacks(callbackCapture.capture())

        controller.onWaitingForResult(true)
        callbackCapture.lastValue.onActivityStopped(activity)

        verify(activity, never()).finish()
    }

    @Test
    fun activityLifecycle_stoppedAfterResultReturned() {
        val activity = mock<Activity>()
        val controller = EditWidgetsActivity.ActivityController(activity)

        val callbackCapture = argumentCaptor<ActivityLifecycleCallbacks>()
        verify(activity).registerActivityLifecycleCallbacks(callbackCapture.capture())

        controller.onWaitingForResult(true)
        controller.onWaitingForResult(false)
        callbackCapture.lastValue.onActivityStopped(activity)

        verify(activity).finish()
    }

    @Test
    fun activityLifecycle_statePreservedThroughInstanceSave() {
        val activity = mock<Activity>()
        val bundle = Bundle(1)

        run {
            val controller = EditWidgetsActivity.ActivityController(activity)
            val callbackCapture = argumentCaptor<ActivityLifecycleCallbacks>()
            verify(activity).registerActivityLifecycleCallbacks(callbackCapture.capture())

            controller.onWaitingForResult(true)
            callbackCapture.lastValue.onActivitySaveInstanceState(activity, bundle)
        }

        clearInvocations(activity)

        run {
            val controller = EditWidgetsActivity.ActivityController(activity)
            val callbackCapture = argumentCaptor<ActivityLifecycleCallbacks>()
            verify(activity).registerActivityLifecycleCallbacks(callbackCapture.capture())

            callbackCapture.lastValue.onActivityCreated(activity, bundle)
            callbackCapture.lastValue.onActivityStopped(activity)

            verify(activity, never()).finish()
        }
    }
}
