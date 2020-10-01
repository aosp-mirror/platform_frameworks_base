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

import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.SparseArray;

/**
 * Tests for {@link com.android.server.pm.UserRestrictionsUtils}.
 *
 * <p>Run with:<pre>
   m FrameworksServicesTests &&
   adb install \
     -r out/target/product/hammerhead/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
   adb shell am instrument -e class com.android.server.pm.UserRestrictionsUtilsTest \
     -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
@SmallTest
public class UserRestrictionsUtilsTest extends AndroidTestCase {
    public void testNonNull() {
        Bundle out = UserRestrictionsUtils.nonNull(null);
        assertNotNull(out);
        out.putBoolean("a", true); // Should not be Bundle.EMPTY.

        Bundle in = new Bundle();
        assertSame(in, UserRestrictionsUtils.nonNull(in));
    }

    public void testIsEmpty() {
        assertTrue(UserRestrictionsUtils.isEmpty(null));
        assertTrue(UserRestrictionsUtils.isEmpty(new Bundle()));
        assertFalse(UserRestrictionsUtils.isEmpty(newRestrictions("a")));
    }

    public void testClone() {
        Bundle in = new Bundle();
        Bundle out = UserRestrictionsUtils.clone(in);
        assertNotSame(in, out);
        assertRestrictions(out, new Bundle());

        out = UserRestrictionsUtils.clone(null);
        assertNotNull(out);
        out.putBoolean("a", true); // Should not be Bundle.EMPTY.
    }

    public void testMerge() {
        Bundle a = newRestrictions("a", "d");
        Bundle b = newRestrictions("b", "d", "e");

        UserRestrictionsUtils.merge(a, b);

        assertRestrictions(newRestrictions("a", "b", "d", "e"), a);

        UserRestrictionsUtils.merge(a, null);

        assertRestrictions(newRestrictions("a", "b", "d", "e"), a);

        try {
            UserRestrictionsUtils.merge(a, a);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testCanDeviceOwnerChange() {
        assertFalse(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_RECORD_AUDIO));
        assertFalse(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_WALLPAPER));
        assertTrue(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_ADD_USER));
        assertTrue(UserRestrictionsUtils.canDeviceOwnerChange(UserManager.DISALLOW_USER_SWITCH));
    }

    public void testCanProfileOwnerChange() {
        int user = UserHandle.USER_SYSTEM;
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_RECORD_AUDIO, user));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_WALLPAPER, user));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_USER_SWITCH, user));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADD_USER, user));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME, user));

        user = 10;
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_RECORD_AUDIO, user));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_WALLPAPER, user));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADD_USER, user));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_USER_SWITCH, user));
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME, user));
    }

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
