/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Verify that accessing private-API protected Settings require specific permissions.
 */
public class SettingsPermissionsTests extends AndroidTestCase {

    private ContentResolver mContentResolver;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContentResolver = getContext().getContentResolver();
    }

    /**
     * Verify that writing to the GServices table in Settings provider requires permissions.
     * <p>Tests Permission:
     *   {@link android.Manifest.permission#WRITE_GSERVICES}
     */
    @MediumTest
    public void testWriteGServices() {
        try {
            ContentValues values = new ContentValues();
            values.put("url", "android");
            mContentResolver.insert(Settings.Gservices.CONTENT_URI, values);
            fail("Write into Gservices provider did not throw SecurityException as expected.");
        } catch (SecurityException e) {
            // expected
        }
    }
}