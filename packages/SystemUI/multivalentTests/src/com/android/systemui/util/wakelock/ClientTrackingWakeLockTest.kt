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

package com.android.systemui.util.wakelock

import android.os.Build
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.After
import org.junit.Assert
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ClientTrackingWakeLockTest : SysuiTestCase() {

    private val WHY = "test"
    private val WHY_2 = "test2"

    lateinit var mWakeLock: ClientTrackingWakeLock
    lateinit var mInner: PowerManager.WakeLock

    @Before
    fun setUp() {
        mInner =
            WakeLock.createWakeLockInner(mContext, "WakeLockTest", PowerManager.PARTIAL_WAKE_LOCK)
        mWakeLock = ClientTrackingWakeLock(mInner, null, 20000)
    }

    @After
    fun tearDown() {
        mInner.setReferenceCounted(false)
        mInner.release()
    }

    @Test
    fun createPartialInner_notHeldYet() {
        Assert.assertFalse(mInner.isHeld)
    }

    @Test
    fun wakeLock_acquire() {
        mWakeLock.acquire(WHY)
        Assert.assertTrue(mInner.isHeld)
    }

    @Test
    fun wakeLock_release() {
        mWakeLock.acquire(WHY)
        mWakeLock.release(WHY)
        Assert.assertFalse(mInner.isHeld)
    }

    @Test
    fun wakeLock_acquiredReleasedMultipleSources_stillHeld() {
        mWakeLock.acquire(WHY)
        mWakeLock.acquire(WHY_2)
        mWakeLock.release(WHY)

        Assert.assertTrue(mInner.isHeld)
        mWakeLock.release(WHY_2)
        Assert.assertFalse(mInner.isHeld)
    }

    @Test
    fun wakeLock_releasedTooManyTimes_stillReleased_noThrow() {
        Assume.assumeFalse(Build.IS_ENG)
        mWakeLock.acquire(WHY)
        mWakeLock.acquire(WHY_2)
        mWakeLock.release(WHY)
        mWakeLock.release(WHY_2)
        mWakeLock.release(WHY)
        Assert.assertFalse(mInner.isHeld)
    }

    @Test
    fun wakeLock_wrap() {
        val ran = BooleanArray(1)
        val wrapped = mWakeLock.wrap { ran[0] = true }
        Assert.assertTrue(mInner.isHeld)
        Assert.assertFalse(ran[0])
        wrapped.run()
        Assert.assertTrue(ran[0])
        Assert.assertFalse(mInner.isHeld)
    }

    @Test
    fun prodBuild_wakeLock_releaseWithoutAcquire_noThrow() {
        Assume.assumeFalse(Build.IS_ENG)
        // shouldn't throw an exception on production builds
        mWakeLock.release(WHY)
    }

    @Test
    fun acquireSeveralLocks_stringReportsCorrectCount() {
        mWakeLock.acquire(WHY)
        mWakeLock.acquire(WHY_2)
        mWakeLock.acquire(WHY)
        mWakeLock.acquire(WHY)
        mWakeLock.acquire(WHY_2)
        Assert.assertEquals(5, mWakeLock.activeClients())

        mWakeLock.release(WHY_2)
        mWakeLock.release(WHY_2)
        Assert.assertEquals(3, mWakeLock.activeClients())

        mWakeLock.release(WHY)
        mWakeLock.release(WHY)
        mWakeLock.release(WHY)
        Assert.assertEquals(0, mWakeLock.activeClients())
    }
}
