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

import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.devicepolicy.DpmTestUtils;

/**
 * Tests for {@link com.android.server.pm.UserRestrictionsUtils}.
 *
 * <p>Run with:<pre>
   m FrameworksServicesTests &&
   adb install \
     -r out/target/product/hammerhead/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
   adb shell am instrument -e class com.android.server.pm.UserRestrictionsUtilsTest \
     -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
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
        assertFalse(UserRestrictionsUtils.isEmpty(DpmTestUtils.newRestrictions("a")));
    }

    public void testClone() {
        Bundle in = new Bundle();
        Bundle out = UserRestrictionsUtils.clone(in);
        assertNotSame(in, out);
        DpmTestUtils.assertRestrictions(out, new Bundle());

        out = UserRestrictionsUtils.clone(null);
        assertNotNull(out);
        out.putBoolean("a", true); // Should not be Bundle.EMPTY.
    }

    public void testMerge() {
        Bundle a = DpmTestUtils.newRestrictions("a", "d");
        Bundle b = DpmTestUtils.newRestrictions("b", "d", "e");

        UserRestrictionsUtils.merge(a, b);

        DpmTestUtils.assertRestrictions(DpmTestUtils.newRestrictions("a", "b", "d", "e"), a);

        UserRestrictionsUtils.merge(a, null);

        DpmTestUtils.assertRestrictions(DpmTestUtils.newRestrictions("a", "b", "d", "e"), a);

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
    }

    public void testCanProfileOwnerChange() {
        int user = UserHandle.USER_SYSTEM;
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_RECORD_AUDIO, user));
        assertFalse(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_WALLPAPER, user));
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
        assertTrue(UserRestrictionsUtils.canProfileOwnerChange(
                UserManager.DISALLOW_ADJUST_VOLUME, user));
    }

    public void testSortToGlobalAndLocal() {
        final Bundle local = new Bundle();
        final Bundle global = new Bundle();

        UserRestrictionsUtils.sortToGlobalAndLocal(null, global, local);
        assertEquals(0, global.size());
        assertEquals(0, local.size());

        UserRestrictionsUtils.sortToGlobalAndLocal(Bundle.EMPTY, global, local);
        assertEquals(0, global.size());
        assertEquals(0, local.size());

        UserRestrictionsUtils.sortToGlobalAndLocal(DpmTestUtils.newRestrictions(
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_UNMUTE_MICROPHONE,
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_CONFIG_TETHERING,
                UserManager.DISALLOW_OUTGOING_BEAM,
                UserManager.DISALLOW_APPS_CONTROL
        ), global, local);


        DpmTestUtils.assertRestrictions(DpmTestUtils.newRestrictions(
                // These can be set by PO too, but when DO sets them, they're global.
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_UNMUTE_MICROPHONE,

                // These can only be set by DO.
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_CONFIG_TETHERING
        ), global);

        DpmTestUtils.assertRestrictions(DpmTestUtils.newRestrictions(
                // They can be set by both DO/PO.
                UserManager.DISALLOW_OUTGOING_BEAM,
                UserManager.DISALLOW_APPS_CONTROL
        ), local);
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
                DpmTestUtils.newRestrictions("a")));

        assertFalse(UserRestrictionsUtils.areEqual(
                DpmTestUtils.newRestrictions("a"),
                null));

        assertTrue(UserRestrictionsUtils.areEqual(
                DpmTestUtils.newRestrictions("a"),
                DpmTestUtils.newRestrictions("a")));

        assertFalse(UserRestrictionsUtils.areEqual(
                DpmTestUtils.newRestrictions("a"),
                DpmTestUtils.newRestrictions("a", "b")));

        assertFalse(UserRestrictionsUtils.areEqual(
                DpmTestUtils.newRestrictions("a", "b"),
                DpmTestUtils.newRestrictions("a")));

        assertFalse(UserRestrictionsUtils.areEqual(
                DpmTestUtils.newRestrictions("b", "a"),
                DpmTestUtils.newRestrictions("a", "a")));

        // Make sure false restrictions are handled correctly.
        final Bundle a = DpmTestUtils.newRestrictions("a");
        a.putBoolean("b", true);

        final Bundle b = DpmTestUtils.newRestrictions("a");
        b.putBoolean("b", false);

        assertFalse(UserRestrictionsUtils.areEqual(a, b));
        assertFalse(UserRestrictionsUtils.areEqual(b, a));
    }
}
