/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.test.odm.apps;

import com.android.tradefed.testtype.DeviceTestCase;

public class OdmAppsTest extends DeviceTestCase {
    /**
     * Test if /odm/app is working
     */
    public void testOdmApp() throws Exception {
        assertNotNull(getDevice().getAppPackageInfo("com.android.test.odm.app"));
    }

    /**
     * Test if /odm/priv-app is working
     */
    public void testOdmPrivApp() throws Exception {
        assertNotNull(getDevice().getAppPackageInfo("com.android.test.odm.privapp"));
    }
}
