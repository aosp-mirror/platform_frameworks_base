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
            mPm.getPackageSizeInfo("com.android.framework", null);
            fail("PackageManager.getPackageSizeInfo did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }
}