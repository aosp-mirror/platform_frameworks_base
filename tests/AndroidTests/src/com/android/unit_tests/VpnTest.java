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

package com.android.unit_tests;

import android.net.vpn.L2tpIpsecProfile;
import android.net.vpn.VpnType;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Unit test class to test VPN api
 * Use the below command to run the vpn unit test only
 * runtest vpntest or
 * adb shell am instrument -e class 'com.android.unit_tests.VpnTest'
 *   -w com.android.unit_tests/android.test.InstrumentationTestRunner
 */
public class VpnTest extends AndroidTestCase {

    @Override
    public void setUp() {
    }

    @Override
    public void tearDown() {
    }

    @SmallTest
    public void testGetType() {
        L2tpIpsecProfile li = new L2tpIpsecProfile();
        assertTrue(VpnType.L2TP_IPSEC== li.getType());
    }
}
