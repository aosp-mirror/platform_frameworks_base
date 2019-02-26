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
import android.os.UserManagerInternal;
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

    public void testSortToGlobalAndLocal() {
        final Bundle local = new Bundle();
        final Bundle global = new Bundle();

        UserRestrictionsUtils.sortToGlobalAndLocal(null, false /* isDeviceOwner */,
                UserManagerInternal.CAMERA_NOT_DISABLED, global, local);
        assertEquals(0, global.size());
        assertEquals(0, local.size());

        UserRestrictionsUtils.sortToGlobalAndLocal(Bundle.EMPTY, false /* isDeviceOwner */,
                UserManagerInternal.CAMERA_NOT_DISABLED, global, local);
        assertEquals(0, global.size());
        assertEquals(0, local.size());

        // Restrictions set by DO.
        UserRestrictionsUtils.sortToGlobalAndLocal(newRestrictions(
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_UNMUTE_MICROPHONE,
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_CONFIG_TETHERING,
                UserManager.DISALLOW_OUTGOING_BEAM,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.ENSURE_VERIFY_APPS
        ), true /* isDeviceOwner */, UserManagerInternal.CAMERA_NOT_DISABLED, global, local);


        assertRestrictions(newRestrictions(
                // This one is global no matter who sets it.
                UserManager.ENSURE_VERIFY_APPS,

                // These can be set by PO too, but when DO sets them, they're global.
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_UNMUTE_MICROPHONE,

                // These can only be set by DO.
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_CONFIG_TETHERING
        ), global);

        assertRestrictions(newRestrictions(
                // They can be set by both DO/PO.
                UserManager.DISALLOW_OUTGOING_BEAM,
                UserManager.DISALLOW_APPS_CONTROL
        ), local);

        local.clear();
        global.clear();

        // Restrictions set by PO.
        UserRestrictionsUtils.sortToGlobalAndLocal(newRestrictions(
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_UNMUTE_MICROPHONE,
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_CONFIG_TETHERING,
                UserManager.DISALLOW_OUTGOING_BEAM,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.ENSURE_VERIFY_APPS
        ), false /* isDeviceOwner */, UserManagerInternal.CAMERA_NOT_DISABLED, global, local);

        assertRestrictions(newRestrictions(
                // This one is global no matter who sets it.
                UserManager.ENSURE_VERIFY_APPS
        ), global);

        assertRestrictions(newRestrictions(
                // These can be set by PO too, but when PO sets them, they're local.
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_UNMUTE_MICROPHONE,

                // They can be set by both DO/PO.
                UserManager.DISALLOW_OUTGOING_BEAM,
                UserManager.DISALLOW_APPS_CONTROL,

                // These can only be set by DO.
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_CONFIG_TETHERING
        ), local);

    }

    public void testSortToLocalAndGlobalWithCameraDisabled() {
        final Bundle local = new Bundle();
        final Bundle global = new Bundle();

        UserRestrictionsUtils.sortToGlobalAndLocal(Bundle.EMPTY, false,
                UserManagerInternal.CAMERA_DISABLED_GLOBALLY, global, local);
        assertRestrictions(newRestrictions(UserManager.DISALLOW_CAMERA), global);
        assertEquals(0, local.size());
        global.clear();

        UserRestrictionsUtils.sortToGlobalAndLocal(Bundle.EMPTY, false,
                UserManagerInternal.CAMERA_DISABLED_LOCALLY, global, local);
        assertEquals(0, global.size());
        assertRestrictions(newRestrictions(UserManager.DISALLOW_CAMERA), local);
    }

    public void testMergeAll() {
        SparseArray<Bundle> restrictions = new SparseArray<>();
        assertNull(UserRestrictionsUtils.mergeAll(restrictions));

        restrictions.put(0, newRestrictions(UserManager.DISALLOW_ADJUST_VOLUME));
        restrictions.put(1, newRestrictions(UserManager.DISALLOW_USB_FILE_TRANSFER));
        restrictions.put(2, newRestrictions(UserManager.DISALLOW_APPS_CONTROL));

        Bundle result = UserRestrictionsUtils.mergeAll(restrictions);
        assertRestrictions(
                newRestrictions(
                        UserManager.DISALLOW_ADJUST_VOLUME,
                        UserManager.DISALLOW_USB_FILE_TRANSFER,
                        UserManager.DISALLOW_APPS_CONTROL),
                result);
    }

    public void testMoveRestriction() {
        SparseArray<Bundle> localRestrictions = new SparseArray<>();
        SparseArray<Bundle> globalRestrictions = new SparseArray<>();

        // User 0 has only local restrictions, nothing should change.
        localRestrictions.put(0, newRestrictions(UserManager.DISALLOW_ADJUST_VOLUME));
        // User 1 has a local restriction to be moved to global and some global already. Local
        // restrictions should be removed for this user.
        localRestrictions.put(1, newRestrictions(UserManager.ENSURE_VERIFY_APPS));
        globalRestrictions.put(1, newRestrictions(UserManager.DISALLOW_ADD_USER));
        // User 2 has a local restriction to be moved and one to leave local.
        localRestrictions.put(2, newRestrictions(
                UserManager.ENSURE_VERIFY_APPS,
                UserManager.DISALLOW_CONFIG_VPN));

        UserRestrictionsUtils.moveRestriction(
                UserManager.ENSURE_VERIFY_APPS, localRestrictions, globalRestrictions);

        // Check user 0.
        assertRestrictions(
                newRestrictions(UserManager.DISALLOW_ADJUST_VOLUME),
                localRestrictions.get(0));
        assertNull(globalRestrictions.get(0));

        // Check user 1.
        assertNull(localRestrictions.get(1));
        assertRestrictions(
                newRestrictions(UserManager.ENSURE_VERIFY_APPS, UserManager.DISALLOW_ADD_USER),
                globalRestrictions.get(1));

        // Check user 2.
        assertRestrictions(
                newRestrictions(UserManager.DISALLOW_CONFIG_VPN),
                localRestrictions.get(2));
        assertRestrictions(
                newRestrictions(UserManager.ENSURE_VERIFY_APPS),
                globalRestrictions.get(2));
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
