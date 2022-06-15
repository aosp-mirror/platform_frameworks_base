/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.google.android.startop.iorap

import android.net.Uri
import android.os.ServiceManager
import androidx.test.filters.FlakyTest
import androidx.test.filters.MediumTest
import org.junit.Test
import org.mockito.Mockito.argThat
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy
import org.mockito.Mockito.timeout

// @Ignore("Test is disabled until iorapd is added to init and there's selinux policies for it")
@MediumTest
@FlakyTest(bugId = 149098310) // Failing on cuttlefish with SecurityException.
class IIorapIntegrationTest {
    /**
     * @throws ServiceManager.ServiceNotFoundException if iorapd service could not be found
     */
    private val iorapService: IIorap by lazy {
        // TODO: connect to 'iorapd.stub' which doesn't actually do any work other than reply.
        IIorap.Stub.asInterface(ServiceManager.getServiceOrThrow("iorapd"))

        // Use 'adb shell setenforce 0' otherwise this whole test fails,
        // because the servicemanager is not allowed to hand out the binder token for iorapd.

        // TODO: implement the selinux policies for iorapd.
    }

    // A dummy binder stub implementation is required to use with mockito#spy.
    // Mockito overrides the methods at runtime and tracks how methods were invoked.
    open class DummyTaskListener : ITaskListener.Stub() {
        // Note: make parameters nullable to avoid the kotlin IllegalStateExceptions
        // from using the mockito matchers (eq, argThat, etc).
        override fun onProgress(requestId: RequestId?, result: TaskResult?) {
        }

        override fun onComplete(requestId: RequestId?, result: TaskResult?) {
        }
    }

    private fun testAnyMethod(func: (RequestId) -> Unit) {
        val taskListener = spy(DummyTaskListener())!!

        // FIXME: b/149098310
        return

        try {
            iorapService.setTaskListener(taskListener)
            // Note: Binder guarantees total order for oneway messages sent to the same binder
            // interface, so we don't need any additional blocking here before sending later calls.

            // Every new method call should have a unique request id.
            val requestId = RequestId.nextValueForSequence()!!

            // Apply the specific function under test.
            func(requestId)

            // Typical mockito behavior is to allow any-order callbacks, but we want to test order.
            val inOrder = inOrder(taskListener)

            // The "stub" behavior of iorapd is that every request immediately gets a response of
            //   BEGAN,ONGOING,COMPLETED
            inOrder.verify(taskListener, timeout(100))
                .onProgress(eq(requestId), argThat { it!!.state == TaskResult.STATE_BEGAN })
            inOrder.verify(taskListener, timeout(100))
                .onProgress(eq(requestId), argThat { it!!.state == TaskResult.STATE_ONGOING })
            inOrder.verify(taskListener, timeout(100))
                .onComplete(eq(requestId), argThat { it!!.state == TaskResult.STATE_COMPLETED })
            inOrder.verifyNoMoreInteractions()
        } finally {
            // iorapService.setTaskListener(null)
            // FIXME: null is broken, C++ side sees a non-null object.
        }
    }

    @Test
    fun testOnPackageEvent() {
        // FIXME (b/137134253): implement PackageEvent parsing on the C++ side.
        // This is currently (silently: b/137135024) failing because IIorap is 'oneway' and the
        // C++ PackageEvent un-parceling fails since its not implemented fully.
        /*
        testAnyMethod { requestId : RequestId ->
            iorapService.onPackageEvent(requestId,
                    PackageEvent.createReplaced(
                            Uri.parse("https://www.google.com"), "com.fake.package"))
        }
        */
    }

    @Test
    fun testOnAppIntentEvent() {
        testAnyMethod { requestId: RequestId ->
            iorapService.onAppIntentEvent(requestId, AppIntentEvent.createDefaultIntentChanged(
                    ActivityInfo("dont care", "dont care"),
                    ActivityInfo("dont care 2", "dont care 2")))
        }
    }

    @Test
    fun testOnAppLaunchEvent() {
        testAnyMethod { requestId : RequestId ->
            iorapService.onAppLaunchEvent(requestId, AppLaunchEvent.IntentFailed(/*sequenceId*/123))
        }
    }

    @Test
    fun testOnSystemServiceEvent() {
        testAnyMethod { requestId: RequestId ->
            iorapService.onSystemServiceEvent(requestId,
                    SystemServiceEvent(SystemServiceEvent.TYPE_START))
        }
    }

    @Test
    fun testOnSystemServiceUserEvent() {
        testAnyMethod { requestId: RequestId ->
            iorapService.onSystemServiceUserEvent(requestId,
                    SystemServiceUserEvent(SystemServiceUserEvent.TYPE_START_USER, 0))
        }
    }
}
