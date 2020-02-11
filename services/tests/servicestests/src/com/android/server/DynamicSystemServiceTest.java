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

import android.os.ServiceManager;
import android.os.image.IDynamicSystemService;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

public class DynamicSystemServiceTest extends AndroidTestCase {
    private static final String TAG = "DynamicSystemServiceTests";
    private IDynamicSystemService mService;

    @Override
    protected void setUp() throws Exception {
        mService =
                IDynamicSystemService.Stub.asInterface(
                        ServiceManager.getService("dynamic_system"));
    }

    @LargeTest
    public void test1() {
        assertTrue("dynamic_system service available", mService != null);
        try {
            mService.startInstallation("dsu");
            fail("DynamicSystemService did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        } catch (Exception e) {
            fail(e.toString());
        }
    }
}
