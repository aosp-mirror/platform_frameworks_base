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

package com.android.systemui.activity.data.repository

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING
import android.app.activityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.log.core.Logger
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ActivityManagerRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val logger = Logger(logcatLogBuffer("ActivityManagerRepositoryTest"), "tag")

    private val Kosmos.underTest by Kosmos.Fixture { realActivityManagerRepository }

    @Test
    fun createIsAppVisibleFlow_fetchesInitialValue_true() =
        kosmos.runTest {
            whenever(activityManager.getUidImportance(THIS_UID)).thenReturn(IMPORTANCE_FOREGROUND)

            val latest by
                collectLastValue(underTest.createIsAppVisibleFlow(THIS_UID, logger, LOG_TAG))

            assertThat(latest).isTrue()
        }

    @Test
    fun createIsAppVisibleFlow_fetchesInitialValue_false() =
        kosmos.runTest {
            whenever(activityManager.getUidImportance(THIS_UID)).thenReturn(IMPORTANCE_GONE)

            val latest by
                collectLastValue(underTest.createIsAppVisibleFlow(THIS_UID, logger, LOG_TAG))

            assertThat(latest).isFalse()
        }

    @Test
    fun createIsAppVisibleFlow_getsImportanceUpdates() =
        kosmos.runTest {
            val latest by
                collectLastValue(underTest.createIsAppVisibleFlow(THIS_UID, logger, LOG_TAG))

            val listenerCaptor = argumentCaptor<ActivityManager.OnUidImportanceListener>()
            verify(activityManager).addOnUidImportanceListener(listenerCaptor.capture(), any())
            val listener = listenerCaptor.firstValue

            listener.onUidImportance(THIS_UID, IMPORTANCE_GONE)
            assertThat(latest).isFalse()

            listener.onUidImportance(THIS_UID, IMPORTANCE_FOREGROUND)
            assertThat(latest).isTrue()

            listener.onUidImportance(THIS_UID, IMPORTANCE_TOP_SLEEPING)
            assertThat(latest).isFalse()
        }

    @Test
    fun createIsAppVisibleFlow_ignoresUpdatesForOtherUids() =
        kosmos.runTest {
            val latest by
                collectLastValue(underTest.createIsAppVisibleFlow(THIS_UID, logger, LOG_TAG))

            val listenerCaptor = argumentCaptor<ActivityManager.OnUidImportanceListener>()
            verify(activityManager).addOnUidImportanceListener(listenerCaptor.capture(), any())
            val listener = listenerCaptor.firstValue

            listener.onUidImportance(THIS_UID, IMPORTANCE_GONE)
            assertThat(latest).isFalse()

            // WHEN another UID becomes foreground
            listener.onUidImportance(THIS_UID + 2, IMPORTANCE_FOREGROUND)

            // THEN this UID still stays not visible
            assertThat(latest).isFalse()
        }

    @Test
    fun createIsAppVisibleFlow_securityExceptionOnUidRegistration_ok() =
        kosmos.runTest {
            whenever(activityManager.getUidImportance(THIS_UID)).thenReturn(IMPORTANCE_GONE)
            whenever(activityManager.addOnUidImportanceListener(any(), any()))
                .thenThrow(SecurityException())

            val latest by
                collectLastValue(underTest.createIsAppVisibleFlow(THIS_UID, logger, LOG_TAG))

            // Verify no crash, and we get a value emitted
            assertThat(latest).isFalse()
        }

    /** Regression test for b/216248574. */
    @Test
    fun createIsAppVisibleFlow_getUidImportanceThrowsException_ok() =
        kosmos.runTest {
            whenever(activityManager.getUidImportance(any())).thenThrow(SecurityException())

            val latest by
                collectLastValue(underTest.createIsAppVisibleFlow(THIS_UID, logger, LOG_TAG))

            // Verify no crash, and we get a value emitted
            assertThat(latest).isFalse()
        }

    companion object {
        private const val THIS_UID = 558
        private const val LOG_TAG = "LogTag"
    }
}
