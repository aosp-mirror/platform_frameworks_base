/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.framework.permission.tests;

import junit.framework.TestCase;
import android.content.pm.PackageManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Verify PackageManager api's that require specific permissions.
 */
public class PmPermissionsTests extends AndroidTestCase {
    private PackageManager mPm;
    private String mPkgName = "com.android.framework.permission.tests";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mPm = getContext().getPackageManager();
    }

    /*
     * This test verifies that PackageManger.getPackageSizeInfo enforces permission
     * android.permission.GET_PACKAGE_SIZE
     */
    @SmallTest
    public void testGetPackageSize() {
        try {
            mPm.getPackageSizeInfo(mPkgName, null);
            fail("PackageManager.getPackageSizeInfo" +
                    "did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /*
     * This test verifies that PackageManger.DeleteApplicationCacheFiles enforces permission
     * android.permission.DELETE_CACHE_FILES
     */
    @SmallTest
    public void testDeleteApplicationCacheFiles() {
        try {
            mPm.deleteApplicationCacheFiles(mPkgName, null);
            fail("PackageManager.deleteApplicationCacheFiles" +
                    "did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /*
     * This test verifies that PackageManger.installPackage enforces permission
     * android.permission.INSTALL_PACKAGES
     */
    @SmallTest
    public void testInstallPackage() {
        try {
            mPm.installPackage(null, null, 0, null);
            fail("PackageManager.installPackage" +
                    "did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /*
     * This test verifies that PackageManger.freeStorage
     * enforces permission android.permission.CLEAR_APP_CACHE
     */
    @SmallTest
    public void testFreeStorage1() {
        try {
            mPm.freeStorage(100000, null);
            fail("PackageManager.freeStorage " +
                   "did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /*
     * This test verifies that PackageManger.freeStorageAndNotify
     * enforces permission android.permission.CLEAR_APP_CACHE
     */
    @SmallTest
    public void testFreeStorage2() {
        try {
            mPm.freeStorageAndNotify(100000, null);
            fail("PackageManager.freeStorageAndNotify" +
                    " did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /*
     * This test verifies that PackageManger.clearApplicationUserData
     * enforces permission android.permission.CLEAR_APP_USER_DATA
     */
    @SmallTest
    public void testClearApplicationUserData() {
        try {
            mPm.clearApplicationUserData(mPkgName, null);
            fail("PackageManager.clearApplicationUserData" +
                    "did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /*
     * This test verifies that PackageManger.deletePackage
     * enforces permission android.permission.DELETE_PACKAGES
     */
    @SmallTest
    public void testDeletePackage() {
        try {
            mPm.deletePackage(mPkgName, null, 0);
            fail("PackageManager.deletePackage" +
                   "did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }
}