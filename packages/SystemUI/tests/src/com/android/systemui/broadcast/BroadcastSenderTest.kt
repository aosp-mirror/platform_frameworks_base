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

package com.android.systemui.broadcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.wakelock.WakeLockFake
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class BroadcastSenderTest : SysuiTestCase() {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var broadcastSender: BroadcastSender
    private lateinit var executor: FakeExecutor
    private lateinit var wakeLock: WakeLockFake

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())
        wakeLock = WakeLockFake()
        val wakeLockBuilder = WakeLockFake.Builder(mContext)
        wakeLockBuilder.setWakeLock(wakeLock)
        broadcastSender = BroadcastSender(mockContext, wakeLockBuilder, executor)
    }

    @Test
    fun sendBroadcast_dispatchesWithWakelock() {
        val intent = Intent(Intent.ACTION_VIEW)
        broadcastSender.sendBroadcast(intent)

        runExecutorAssertingWakelock {
            verify(mockContext).sendBroadcast(intent)
        }
    }

    @Test
    fun sendBroadcastWithPermission_dispatchesWithWakelock() {
        val intent = Intent(Intent.ACTION_VIEW)
        val permission = "Permission"
        broadcastSender.sendBroadcast(intent, permission)

        runExecutorAssertingWakelock {
            verify(mockContext).sendBroadcast(intent, permission)
        }
    }

    @Test
    fun sendBroadcastAsUser_dispatchesWithWakelock() {
        val intent = Intent(Intent.ACTION_VIEW)
        broadcastSender.sendBroadcastAsUser(intent, UserHandle.ALL)

        runExecutorAssertingWakelock {
            verify(mockContext).sendBroadcastAsUser(intent, UserHandle.ALL)
        }
    }

    @Test
    fun sendBroadcastAsUserWithPermission_dispatchesWithWakelock() {
        val intent = Intent(Intent.ACTION_VIEW)
        val permission = "Permission"
        broadcastSender.sendBroadcastAsUser(intent, UserHandle.ALL, permission)

        runExecutorAssertingWakelock {
            verify(mockContext).sendBroadcastAsUser(intent, UserHandle.ALL, permission)
        }
    }

    @Test
    fun sendBroadcastAsUserWithPermissionAndOptions_dispatchesWithWakelock() {
        val intent = Intent(Intent.ACTION_VIEW)
        val permission = "Permission"
        val options = Bundle()
        options.putString("key", "value")

        broadcastSender.sendBroadcastAsUser(intent, UserHandle.ALL, permission, options)

        runExecutorAssertingWakelock {
            verify(mockContext).sendBroadcastAsUser(intent, UserHandle.ALL, permission, options)
        }
    }

    @Test
    fun sendBroadcastAsUserWithPermissionAndAppOp_dispatchesWithWakelock() {
        val intent = Intent(Intent.ACTION_VIEW)
        val permission = "Permission"

        broadcastSender.sendBroadcastAsUser(intent, UserHandle.ALL, permission, 12)

        runExecutorAssertingWakelock {
            verify(mockContext).sendBroadcastAsUser(intent, UserHandle.ALL, permission, 12)
        }
    }

    @Test
    fun sendCloseSystemDialogs_dispatchesWithWakelock() {
        val intentCaptor = ArgumentCaptor.forClass(Intent::class.java)

        broadcastSender.closeSystemDialogs()

        runExecutorAssertingWakelock {
            verify(mockContext).sendBroadcast(intentCaptor.capture())
            assertThat(intentCaptor.value.action).isEqualTo(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
    }

    private fun runExecutorAssertingWakelock(verification: () -> Unit) {
        assertThat(wakeLock.isHeld).isTrue()
        executor.runAllReady()
        verification.invoke()
        assertThat(wakeLock.isHeld).isFalse()
    }
}