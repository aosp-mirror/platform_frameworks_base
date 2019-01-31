/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server;

import android.os.IDynamicAndroidService;
import android.os.ServiceManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

public class DynamicAndroidServiceTest extends AndroidTestCase {
    private static final String TAG = "DynamicAndroidServiceTests";
    private IDynamicAndroidService mService;

    @Override
    protected void setUp() throws Exception {
        mService =
                IDynamicAndroidService.Stub.asInterface(
                        ServiceManager.getService("dynamic_android"));
    }

    @LargeTest
    public void test1() {
        assertTrue("dynamic_android service available", mService != null);
        try {
            mService.startInstallation(1 << 20, 8 << 30);
            fail("DynamicAndroidService did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        } catch (Exception e) {
            fail(e.toString());
        }
    }
}
