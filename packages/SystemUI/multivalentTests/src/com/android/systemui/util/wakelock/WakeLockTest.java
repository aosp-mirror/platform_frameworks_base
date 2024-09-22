/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.util.wakelock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.os.PowerManager;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;


@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class WakeLockTest extends SysuiTestCase {

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getFlags() {
        return FlagsParameterization.allCombinationsOf(
                Flags.FLAG_DELAYED_WAKELOCK_RELEASE_ON_BACKGROUND_THREAD);
    }

    @Rule public final SetFlagsRule mSetFlagsRule;

    public WakeLockTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(SetFlagsRule.DefaultInitValueType.NULL_DEFAULT, flags);
    }

    private static final String WHY = "test";
    WakeLock mWakeLock;
    PowerManager.WakeLock mInner;

    @Before
    public void setUp() {
        mInner = WakeLock.createWakeLockInner(mContext,
                WakeLockTest.class.getName(),
                PowerManager.PARTIAL_WAKE_LOCK);
        mWakeLock = WakeLock.wrap(mInner, null, 20000);
    }

    @After
    public void tearDown() {
        mInner.setReferenceCounted(false);
        mInner.release();
    }

    @Test
    public void createPartialInner_notHeldYet() {
        assertFalse(mInner.isHeld());
    }

    @Test
    public void wakeLock_acquire() {
        mWakeLock.acquire(WHY);
        assertTrue(mInner.isHeld());
    }

    @Test
    public void wakeLock_release() {
        mWakeLock.acquire(WHY);
        mWakeLock.release(WHY);
        assertFalse(mInner.isHeld());
    }

    @Test
    public void wakeLock_wrap() {
        boolean[] ran = new boolean[1];

        Runnable wrapped = mWakeLock.wrap(() -> {
            ran[0] = true;
        });

        assertTrue(mInner.isHeld());
        assertFalse(ran[0]);

        wrapped.run();

        assertTrue(ran[0]);
        assertFalse(mInner.isHeld());
    }

    @Test
    public void prodBuild_wakeLock_releaseWithoutAcquire_noThrow() {
        Assume.assumeFalse(Build.IS_ENG);
        // shouldn't throw an exception on production builds
        mWakeLock.release(WHY);
    }
}
