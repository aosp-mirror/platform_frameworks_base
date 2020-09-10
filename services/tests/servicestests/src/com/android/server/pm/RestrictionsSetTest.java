/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm;

import static com.android.server.devicepolicy.DpmTestUtils.assertRestrictions;
import static com.android.server.devicepolicy.DpmTestUtils.newRestrictions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Test for {@link RestrictionsSet}. */
@RunWith(AndroidJUnit4.class)
public class RestrictionsSetTest {

    private RestrictionsSet mRestrictionsSet = new RestrictionsSet();
    private final int originatingUserId = 0;

    @Test
    public void testUpdateRestrictions_addRestrictions() {
        Bundle restrictions = newRestrictions(UserManager.ENSURE_VERIFY_APPS);

        assertTrue(mRestrictionsSet.updateRestrictions(originatingUserId, restrictions));

        assertRestrictions(restrictions, mRestrictionsSet.getRestrictions(originatingUserId));
    }

    @Test
    public void testUpdateRestrictions_removeRestrictions() {
        Bundle restrictions = newRestrictions(UserManager.ENSURE_VERIFY_APPS);
        mRestrictionsSet.updateRestrictions(originatingUserId, restrictions);

        assertTrue(mRestrictionsSet.updateRestrictions(originatingUserId, new Bundle()));

        assertNull(mRestrictionsSet.getRestrictions(originatingUserId));
    }

    @Test
    public void testUpdateRestrictions_noChange() {
        Bundle restrictions = newRestrictions(UserManager.ENSURE_VERIFY_APPS);
        mRestrictionsSet.updateRestrictions(originatingUserId, restrictions);

        assertFalse(mRestrictionsSet.updateRestrictions(originatingUserId, restrictions));
    }

    @Test
    public void testMoveRestriction_containsRestriction() {
        RestrictionsSet destRestrictionsSet = new RestrictionsSet();

        String restriction = UserManager.DISALLOW_CONFIG_DATE_TIME;
        mRestrictionsSet.updateRestrictions(originatingUserId,
                newRestrictions(restriction));

        mRestrictionsSet.moveRestriction(destRestrictionsSet, restriction);

        assertNull(mRestrictionsSet.getRestrictions(originatingUserId));
        assertNotNull(destRestrictionsSet.getRestrictions(originatingUserId));
        assertRestrictions(newRestrictions(restriction),
                destRestrictionsSet.getRestrictions(originatingUserId));
    }

    @Test
    public void testMoveRestriction_doesNotContainRestriction() {
        RestrictionsSet destRestrictionsSet = new RestrictionsSet();

        mRestrictionsSet.updateRestrictions(originatingUserId,
                newRestrictions(UserManager.ENSURE_VERIFY_APPS));

        mRestrictionsSet.moveRestriction(destRestrictionsSet,
                UserManager.DISALLOW_CONFIG_DATE_TIME);

        assertRestrictions(newRestrictions(UserManager.ENSURE_VERIFY_APPS),
                mRestrictionsSet.getRestrictions(originatingUserId));
        assertNull(destRestrictionsSet.getRestrictions(originatingUserId));
    }

    @Test
    public void testIsEmpty_noRestrictions() {
        assertTrue(mRestrictionsSet.isEmpty());
    }

    @Test
    public void testIsEmpty_hasRestrictions() {
        mRestrictionsSet.updateRestrictions(originatingUserId,
                newRestrictions(UserManager.ENSURE_VERIFY_APPS,
                        UserManager.DISALLOW_CONFIG_DATE_TIME));

        assertFalse(mRestrictionsSet.isEmpty());
    }

    @Test
    public void testMergeAll_noRestrictions() {
        assertTrue(mRestrictionsSet.mergeAll().isEmpty());
    }

    @Test
    public void testMergeAll_hasRestrictions() {
        mRestrictionsSet.updateRestrictions(originatingUserId,
                newRestrictions(UserManager.ENSURE_VERIFY_APPS,
                        UserManager.DISALLOW_CONFIG_DATE_TIME));
        mRestrictionsSet.updateRestrictions(10,
                newRestrictions(UserManager.DISALLOW_ADD_USER,
                        UserManager.DISALLOW_AIRPLANE_MODE));

        Bundle actual = mRestrictionsSet.mergeAll();
        assertRestrictions(newRestrictions(UserManager.ENSURE_VERIFY_APPS,
                UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_AIRPLANE_MODE), actual);
    }

    @Test
    public void testGetEnforcingUsers_hasEnforcingUser() {
        mRestrictionsSet.updateRestrictions(originatingUserId,
                newRestrictions(UserManager.ENSURE_VERIFY_APPS));
        mRestrictionsSet.updateRestrictions(10,
                newRestrictions(UserManager.DISALLOW_ADD_USER));

        List<UserManager.EnforcingUser> enforcingUsers = mRestrictionsSet.getEnforcingUsers(
                UserManager.ENSURE_VERIFY_APPS, originatingUserId);

        UserManager.EnforcingUser enforcingUser1 = enforcingUsers.get(0);
        assertEquals(UserHandle.of(originatingUserId), enforcingUser1.getUserHandle());
        assertEquals(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER,
                enforcingUser1.getUserRestrictionSource());
    }

    @Test
    public void testGetEnforcingUsers_hasMultipleEnforcingUsers() {
        int originatingUserId2 = 10;
        mRestrictionsSet.updateRestrictions(originatingUserId,
                newRestrictions(UserManager.ENSURE_VERIFY_APPS));
        mRestrictionsSet.updateRestrictions(originatingUserId2,
                newRestrictions(UserManager.ENSURE_VERIFY_APPS));

        List<UserManager.EnforcingUser> enforcingUsers = mRestrictionsSet.getEnforcingUsers(
                UserManager.ENSURE_VERIFY_APPS, originatingUserId);

        assertEquals(2, enforcingUsers.size());
        for (UserManager.EnforcingUser enforcingUser : enforcingUsers) {
            int userId = enforcingUser.getUserHandle().getIdentifier();
            assertTrue((userId == originatingUserId) || (userId == originatingUserId2));
            if (userId == originatingUserId) {
                assertEquals(UserManager.RESTRICTION_SOURCE_DEVICE_OWNER,
                        enforcingUser.getUserRestrictionSource());
            }
            if (userId == originatingUserId2) {
                assertEquals(UserManager.RESTRICTION_SOURCE_PROFILE_OWNER,
                        enforcingUser.getUserRestrictionSource());
            }
        }
    }

    @Test
    public void testGetEnforcingUsers_noEnforcingUsers() {
        mRestrictionsSet.updateRestrictions(originatingUserId,
                newRestrictions(UserManager.DISALLOW_USER_SWITCH));

        List<UserManager.EnforcingUser> enforcingUsers = mRestrictionsSet.getEnforcingUsers(
                UserManager.ENSURE_VERIFY_APPS, originatingUserId);

        assertTrue(enforcingUsers.isEmpty());
    }

}
