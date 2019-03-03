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

import static junit.framework.TestCase.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SettableWakeLockTest extends SysuiTestCase {

    private WakeLockFake mFake;
    private SettableWakeLock mSettable;

    @Before
    public void setup() {
        mFake = new WakeLockFake();
        mSettable = new SettableWakeLock(mFake, "Fake");
    }

    @Test
    public void setAcquire_true_acquires() throws Exception {
        mSettable.setAcquired(true);
        assertTrue(mFake.isHeld());
        assertEquals(mFake.isHeld(), mSettable.isAcquired());
    }

    @Test
    public void setAcquire_false_releases() throws Exception {
        mSettable.setAcquired(true);
        mSettable.setAcquired(false);
        assertFalse(mFake.isHeld());
        assertEquals(mFake.isHeld(), mSettable.isAcquired());
    }

    @Test
    public void setAcquire_true_multipleTimes_isIdempotent() throws Exception {
        mSettable.setAcquired(true);
        mSettable.setAcquired(true);
        mSettable.setAcquired(true);
        mSettable.setAcquired(false);
        assertFalse(mFake.isHeld());
        assertEquals(mFake.isHeld(), mSettable.isAcquired());
    }

    @Test
    public void setAcquire_false_multipleTimes_idempotent() throws Exception {
        mSettable.setAcquired(true);
        mSettable.setAcquired(false);
        mSettable.setAcquired(false);
        assertFalse(mFake.isHeld());
        assertEquals(mFake.isHeld(), mSettable.isAcquired());
    }

    @Test
    public void setAcquire_false_multipleTimes_idempotent_again() throws Exception {
        mSettable.setAcquired(true);
        mSettable.setAcquired(false);
        mSettable.setAcquired(false);
        mSettable.setAcquired(true);
        assertTrue(mFake.isHeld());
        assertEquals(mFake.isHeld(), mSettable.isAcquired());
    }


}