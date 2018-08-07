/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static org.junit.Assert.assertEquals;

import android.Manifest.permission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.testing.TestableLooper.RunWithLooper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class TestablePermissionsTest {

    private static final Uri URI_1 = Uri.parse("content://my.authority/path1");
    private static final Uri URI_2 = Uri.parse("content://my.authority/path2");

    @Rule
    public TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());

    @Test
    public void testCheck() {
        mContext.getTestablePermissions().setPermission(permission.INTERACT_ACROSS_USERS,
                PERMISSION_GRANTED);
        mContext.getTestablePermissions().setPermission(permission.INTERACT_ACROSS_USERS_FULL,
                PERMISSION_DENIED);
        assertEquals(PERMISSION_GRANTED,
                mContext.checkPermission(permission.INTERACT_ACROSS_USERS, 0, 0));
        assertEquals(PERMISSION_DENIED,
                mContext.checkPermission(permission.INTERACT_ACROSS_USERS_FULL, 0, 0));
    }

    @Test
    public void testCheckUri() {
        mContext.getTestablePermissions().setPermission(URI_1, PERMISSION_GRANTED);
        mContext.getTestablePermissions().setPermission(URI_2, PERMISSION_DENIED);

        assertEquals(PERMISSION_GRANTED, mContext.checkUriPermission(URI_1, 0, 0, 0));
        assertEquals(PERMISSION_DENIED, mContext.checkUriPermission(URI_2, 0, 0, 0));
    }

    @Test
    public void testEnforceNoException() {
        mContext.getTestablePermissions().setPermission(permission.INTERACT_ACROSS_USERS,
                PERMISSION_GRANTED);
        mContext.enforceCallingOrSelfPermission(permission.INTERACT_ACROSS_USERS, "");
    }

    @Test(expected = SecurityException.class)
    public void testEnforceWithException() {
        mContext.getTestablePermissions().setPermission(permission.INTERACT_ACROSS_USERS,
                PERMISSION_DENIED);
        mContext.enforceCallingOrSelfPermission(permission.INTERACT_ACROSS_USERS, "");
    }

    @Test
    public void testEnforceUriNoException() {
        mContext.getTestablePermissions().setPermission(URI_1, PERMISSION_GRANTED);
        mContext.enforceUriPermission(URI_1, 0, 0, 0, "");
    }

    @Test(expected = SecurityException.class)
    public void testEnforceUriWithException() {
        mContext.getTestablePermissions().setPermission(URI_1, PERMISSION_DENIED);
        mContext.enforceUriPermission(URI_1, 0, 0, 0, "");
    }

}