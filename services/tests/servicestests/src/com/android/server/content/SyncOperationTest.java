/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.content;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * You can run those tests with:
 *
 * adb shell am instrument
 * -e debug false
 * -w
 * -e class android.content.SyncOperationTest com.android.frameworks.coretests/android.test.InstrumentationTestRunner
 */

public class SyncOperationTest extends AndroidTestCase {

    Account mDummy;
    /** Indicate an unimportant long that we're not testing. */
    long mUnimportantLong = 0L;
    /** Empty bundle. */
    Bundle mEmpty;
    /** Silly authority. */
    String mAuthority;

    @Override
    public void setUp() {
        mDummy = new Account("account1", "type1");
        mEmpty = new Bundle();
        mAuthority = "authority1";
    }

    @SmallTest
    public void testToKey() {
        Account account1 = new Account("account1", "type1");
        Account account2 = new Account("account2", "type2");

        Bundle b1 = new Bundle();
        Bundle b2 = new Bundle();
        b2.putBoolean("b2", true);

        SyncOperation op1 = new SyncOperation(account1, 0,
                1,
                SyncOperation.REASON_PERIODIC,
                "authority1",
                b1,
                100, /* run time from now*/
                10, /* flex */
                1000,
                10000,
                false);

        // Same as op1 but different time infos
        SyncOperation op2 = new SyncOperation(account1, 0,
                1,
                SyncOperation.REASON_PERIODIC,
                "authority1",
                b1,
                200,
                20,
                2000,
                20000,
                false);

        // Same as op1 but different authority
        SyncOperation op3 = new SyncOperation(account1, 0,
                1,
                SyncOperation.REASON_PERIODIC,
                "authority2",
                b1,
                100,
                10,
                1000,
                10000,
                false);

        // Same as op1 but different account
        SyncOperation op4 = new SyncOperation(account2, 0,
                1,
                SyncOperation.REASON_PERIODIC,
                "authority1",
                b1,
                100,
                10,
                1000,
                10000,
                false);

        // Same as op1 but different bundle
        SyncOperation op5 = new SyncOperation(account1, 0,
                1,
                SyncOperation.REASON_PERIODIC,
                "authority1",
                b2,
                100,
                10,
                1000,
                10000,
                false);

        assertEquals(op1.key, op2.key);
        assertNotSame(op1.key, op3.key);
        assertNotSame(op1.key, op4.key);
        assertNotSame(op1.key, op5.key);
    }

    @SmallTest
    public void testCompareTo() {
        long soon = 1000;
        long soonFlex = 50;
        long after = 1500;
        long afterFlex = 100;
        SyncOperation op1 = new SyncOperation(mDummy, 0, 0, SyncOperation.REASON_PERIODIC,
                "authority1", mEmpty, soon, soonFlex, mUnimportantLong, mUnimportantLong, true);

        // Interval disjoint from and after op1.
        SyncOperation op2 = new SyncOperation(mDummy, 0, 0, SyncOperation.REASON_PERIODIC,
                "authority1", mEmpty, after, afterFlex, mUnimportantLong, mUnimportantLong, true);

        // Interval equivalent to op1, but expedited.
        Bundle b2 = new Bundle();
        b2.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        SyncOperation op3 = new SyncOperation(mDummy, 0, 0, 0,
                "authority1", b2, -1, soonFlex, mUnimportantLong, mUnimportantLong, true);

        // Interval overlaps but not equivalent to op1.
        SyncOperation op4 = new SyncOperation(mDummy, 0, 0, SyncOperation.REASON_PERIODIC,
                "authority1", mEmpty, soon + 100, soonFlex + 100, mUnimportantLong, mUnimportantLong, true);

        assertTrue(op1.compareTo(op2) == -1);
        assertTrue("less than not transitive.", op2.compareTo(op1) == 1);
        assertTrue("Expedited sync not smaller than non-expedited.", op1.compareTo(op3) == 1);
        assertTrue("greater than not transitive. ", op3.compareTo(op1) == -1);
        assertTrue("overlapping intervals not correctly compared.", op1.compareTo(op4) == -1);
        assertTrue("equality not transitive.", op4.compareTo(op1) == 1);
    }

    @SmallTest
    public void testCopyConstructor() {
        long fiveSecondsFromNow = 5 * 1000L;
        long twoSecondsFlex = 2 * 1000L;
        long eightSeconds = 8 * 1000L;
        long fourSeconds = 4 * 1000L;

        Bundle withExpedited = new Bundle();
        withExpedited.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        SyncOperation op = new SyncOperation(mDummy, 0, 0, SyncOperation.REASON_USER_START,
                mAuthority, withExpedited, fiveSecondsFromNow, twoSecondsFlex,
                eightSeconds /* backoff */, fourSeconds /* delayUntil */, true);
        // Create another sync op to be rerun in 5 minutes.
        long now = SystemClock.elapsedRealtime();
        SyncOperation copy = new SyncOperation(op, fiveSecondsFromNow * 60);
        // Copying an expedited sync to be re-run should not keep expedited property.
        assertFalse("A rescheduled sync based off an expedited should not be expedited!",
                copy.isExpedited());
        assertFalse("A rescheduled sync based off an expedited should not have expedited=true in"
                + "its bundle.",
                copy.extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false));
        assertTrue("Copied sync is not respecting new provided run-time.",
                copy.latestRunTime == (now + fiveSecondsFromNow * 60));
        assertTrue("A rescheduled sync should not have any flex.",
                copy.flexTime == 0L);
        assertTrue("A rescheduled op should honour the old op's backoff.",
                copy.backoff == eightSeconds);
        assertTrue("A rescheduled op should honour the old op's delayUntil param.",
                copy.delayUntil == fourSeconds);

    }
}
