/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static junit.framework.Assert.assertFalse;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link com.android.server.pm.UserRestrictionsUtils}.
 *
 * <p>Run with:<pre>
   atest UserRestrictionsUtilsTest
 * </pre>
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class UserRestrictionsUtilsTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testNonNull() {
        Bundle out = UserRestrictionsUtils.nonNull(null);
        assertNotNull(out);
        out.putBoolean("a", true); // Should not be Bundle.EMPTY.

        Bundle in = new Bundle();
        assertSame(in, UserRestrictionsUtils.nonNull(in));
    }

    @Test
    public void testMerge() {
        Bundle a = newRestrictions("a", "d");
        Bundle b = newRestrictions("b", "d", "e");

        UserRestrictionsUtils.merge(a, b);

        assertRestrictions(newRestrictions("a", "b", "d", "e"), a);

        UserRestrictionsUtils.merge(a, null);

        assertRestrictions(newRestrictions("a", "b", "d", "e"), a);

        assertThrows(IllegalArgumentException.class, () -> UserRestrictionsUtils.merge(a, a));
    }

    @Test
    public void testCanDeviceOwnerChange() {
        assertFalse(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_RECORD_AUDIO));
        assertFalse(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_WALLPAPER));
        assertTrue(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_ADD_USER));
        assertTrue(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_USER_SWITCH));
    }

    @Test
    public void testCanProfileOwnerChange_mainUser() {
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_RECORD_AUDIO,
                true,
                false));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_WALLPAPER,
                true,
                false));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_USER_SWITCH,
                true,
                false));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADD_USER,
                true,
                false));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME,
                true,
                false));
    }

    @Test
    public void testCanProfileOwnerChange_notMainUser() {
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_RECORD_AUDIO,
                false,
                false));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_WALLPAPER,
                false,
                false));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADD_USER,
                false,
                false));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_USER_SWITCH,
                false,
                false));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME,
                false,
                false));
    }

    @Test
    public void testCanProfileOwnerChange_restrictionRequiresOrgOwnedDevice_orgOwned() {
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_SIM_GLOBALLY,
                false,
                true));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_SIM_GLOBALLY,
                true,
                true));
    }

    @Test
    public void testCanProfileOwnerChange_restrictionRequiresOrgOwnedDevice_notOrgOwned() {
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_SIM_GLOBALLY,
                false,
                false));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_SIM_GLOBALLY,
                true,
                false));
    }

    @Test
    public void testCanProfileOwnerChange_restrictionNotRequiresOrgOwnedDevice_orgOwned() {
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME,
                false,
                true));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME,
                true,
                true));
    }

    @Test
    public void testCanProfileOwnerChange_restrictionNotRequiresOrgOwnedDevice_notOrgOwned() {
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME,
                false,
                false));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME,
                true,
                false));
    }

    @Test
    public void testMoveRestriction() {
        SparseArray<RestrictionsSet> localRestrictions = new SparseArray<>();
        RestrictionsSet globalRestrictions = new RestrictionsSet();

        // User 0 has only local restrictions, nothing should change.
        localRestrictions.put(0, newRestrictions(0, UserManager.DISALLOW_ADJUST_VOLUME));
        // User 1 has a local restriction to be moved to global and some global already. Local
        // restrictions should be removed for this user.
        localRestrictions.put(1, newRestrictions(1, UserManager.ENSURE_VERIFY_APPS));
        globalRestrictions.updateRestrictions(1,
                newRestrictions(UserManager.DISALLOW_ADD_USER));
        // User 2 has a local restriction to be moved and one to leave local.
        localRestrictions.put(2, newRestrictions(2,
                UserManager.ENSURE_VERIFY_APPS, UserManager.DISALLOW_CONFIG_VPN));

        UserRestrictionsUtils.moveRestriction(
                UserManager.ENSURE_VERIFY_APPS, localRestrictions, globalRestrictions);

        // Check user 0.
        assertRestrictions(
                newRestrictions(0, UserManager.DISALLOW_ADJUST_VOLUME),
                localRestrictions.get(0));
        assertNull(globalRestrictions.getRestrictions(0));

        // Check user 1.
        assertTrue(localRestrictions.get(1).isEmpty());
        assertRestrictions(
                newRestrictions(UserManager.ENSURE_VERIFY_APPS, UserManager.DISALLOW_ADD_USER),
                globalRestrictions.getRestrictions(1));

        // Check user 2.
        assertRestrictions(
                newRestrictions(2, UserManager.DISALLOW_CONFIG_VPN),
                localRestrictions.get(2));
        assertRestrictions(
                newRestrictions(UserManager.ENSURE_VERIFY_APPS),
                globalRestrictions.getRestrictions(2));
    }

    @Test
    public void testAreEqual() {
        assertTrue(UserRestrictionsUtils.areEqual(
                null,
                null));

        assertTrue(UserRestrictionsUtils.areEqual(
                null,
                Bundle.EMPTY));

        assertTrue(UserRestrictionsUtils.areEqual(
                Bundle.EMPTY,
                null));

        assertTrue(UserRestrictionsUtils.areEqual(
                Bundle.EMPTY,
                Bundle.EMPTY));

        assertTrue(UserRestrictionsUtils.areEqual(
                new Bundle(),
                Bundle.EMPTY));

        assertFalse(UserRestrictionsUtils.areEqual(
                null,
                newRestrictions("a")));

        assertFalse(UserRestrictionsUtils.areEqual(
                newRestrictions("a"),
                null));

        assertTrue(UserRestrictionsUtils.areEqual(
                newRestrictions("a"),
                newRestrictions("a")));

        assertFalse(UserRestrictionsUtils.areEqual(
                newRestrictions("a"),
                newRestrictions("a", "b")));

        assertFalse(UserRestrictionsUtils.areEqual(
                newRestrictions("a", "b"),
                newRestrictions("a")));

        assertFalse(UserRestrictionsUtils.areEqual(
                newRestrictions("b", "a"),
                newRestrictions("a", "a")));

        // Make sure false restrictions are handled correctly.
        final Bundle a = newRestrictions("a");
        a.putBoolean("b", true);

        final Bundle b = newRestrictions("a");
        b.putBoolean("b", false);

        assertFalse(UserRestrictionsUtils.areEqual(a, b));
        assertFalse(UserRestrictionsUtils.areEqual(b, a));
    }
}
