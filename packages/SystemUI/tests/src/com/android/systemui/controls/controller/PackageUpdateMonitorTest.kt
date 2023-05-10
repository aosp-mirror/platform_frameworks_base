/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.controls.controller

import android.content.Context
import android.os.Handler
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class PackageUpdateMonitorTest : SysuiTestCase() {

    @Mock private lateinit var context: Context
    @Mock private lateinit var bgHandler: Handler

    private lateinit var underTest: PackageUpdateMonitor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun startMonitoring_registerOnlyOnce() {
        underTest = PackageUpdateMonitor(USER, PACKAGE, {}, bgHandler, context)

        underTest.startMonitoring()
        // There are two receivers registered
        verify(context, times(2))
            .registerReceiverAsUser(any(), eq(USER), any(), eq(null), eq(bgHandler))

        underTest.startMonitoring()
        verifyNoMoreInteractions(context)
    }

    @Test
    fun stopMonitoring_unregistersOnlyOnce() {
        underTest = PackageUpdateMonitor(USER, PACKAGE, {}, bgHandler, context)

        underTest.startMonitoring()
        clearInvocations(context)

        underTest.stopMonitoring()
        verify(context).unregisterReceiver(any())

        underTest.stopMonitoring()
        verifyNoMoreInteractions(context)
    }

    @Test
    fun onPackageUpdated_correctPackageAndUser_callbackRuns() {
        val callback = mock<Runnable>()

        underTest = PackageUpdateMonitor(USER, PACKAGE, callback, bgHandler, context)

        underTest.onPackageUpdateFinished(PACKAGE, UserHandle.getUid(USER.identifier, 10000))
        verify(callback).run()
    }

    @Test
    fun onPackageUpdated_correctPackage_wrongUser_callbackDoesntRun() {
        val callback = mock<Runnable>()

        underTest = PackageUpdateMonitor(USER, PACKAGE, callback, bgHandler, context)

        underTest.onPackageUpdateFinished(PACKAGE, UserHandle.getUid(USER.identifier + 1, 10000))
        verify(callback, never()).run()
    }

    @Test
    fun onPackageUpdated_wrongPackage_correctUser_callbackDoesntRun() {
        val callback = mock<Runnable>()

        underTest = PackageUpdateMonitor(USER, PACKAGE, callback, bgHandler, context)

        underTest.onPackageUpdateFinished("bad", UserHandle.getUid(USER.identifier + 1, 10000))
        verify(callback, never()).run()
    }

    companion object {
        private val USER = UserHandle.of(0)
        private val PACKAGE = "pkg"
    }
}
