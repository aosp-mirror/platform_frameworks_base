/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net;

import static android.os.UserHandle.MIN_SECONDARY_USER_ID;
import static android.os.UserHandle.SYSTEM;
import static android.os.UserHandle.USER_SYSTEM;
import static android.os.UserHandle.getUid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Build;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UidRangeTest {

    /*
     * UidRange is no longer passed to netd. UID ranges between the framework and netd are passed as
     * UidRangeParcel objects.
     */

    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    @Test
    public void testSingleItemUidRangeAllowed() {
        new UidRange(123, 123);
        new UidRange(0, 0);
        new UidRange(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    public void testNegativeUidsDisallowed() {
        try {
            new UidRange(-2, 100);
            fail("Exception not thrown for negative start UID");
        } catch (IllegalArgumentException expected) {
        }

        try {
            new UidRange(-200, -100);
            fail("Exception not thrown for negative stop UID");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testStopLessThanStartDisallowed() {
        final int x = 4195000;
        try {
            new UidRange(x, x - 1);
            fail("Exception not thrown for negative-length UID range");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testGetStartAndEndUser() throws Exception {
        final UidRange uidRangeOfPrimaryUser = new UidRange(
                getUid(USER_SYSTEM, 10000), getUid(USER_SYSTEM, 10100));
        final UidRange uidRangeOfSecondaryUser = new UidRange(
                getUid(MIN_SECONDARY_USER_ID, 10000), getUid(MIN_SECONDARY_USER_ID, 10100));
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getStartUser());
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getEndUser());
        assertEquals(MIN_SECONDARY_USER_ID, uidRangeOfSecondaryUser.getStartUser());
        assertEquals(MIN_SECONDARY_USER_ID, uidRangeOfSecondaryUser.getEndUser());

        final UidRange uidRangeForDifferentUsers = new UidRange(
                getUid(USER_SYSTEM, 10000), getUid(MIN_SECONDARY_USER_ID, 10100));
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getStartUser());
        assertEquals(MIN_SECONDARY_USER_ID, uidRangeOfSecondaryUser.getEndUser());
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testCreateForUser() throws Exception {
        final UidRange uidRangeOfPrimaryUser = UidRange.createForUser(SYSTEM);
        final UidRange uidRangeOfSecondaryUser = UidRange.createForUser(
                UserHandle.of(USER_SYSTEM + 1));
        assertTrue(uidRangeOfPrimaryUser.stop < uidRangeOfSecondaryUser.start);
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getStartUser());
        assertEquals(USER_SYSTEM, uidRangeOfPrimaryUser.getEndUser());
        assertEquals(USER_SYSTEM + 1, uidRangeOfSecondaryUser.getStartUser());
        assertEquals(USER_SYSTEM + 1, uidRangeOfSecondaryUser.getEndUser());
    }
}
