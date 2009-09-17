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

package com.android.telephonytest.unit;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;

/*
 * Check the PhoneNumberUtils utility class works as expected.
 *
 */

public class PhoneNumberUtilsUnitTest extends AndroidTestCase {
    private String mVoiceMailNumber;
    private static final String TAG = "PhoneNumberUtilsUnitTest";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // FIXME: Why are we getting a security exception here? The
        // permission is declared in the manifest....
        // mVoiceMailNumber = TelephonyManager.getDefault().getVoiceMailNumber();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Basic checks for the VoiceMail number.
     * Assumes READ_PHONE_STATE permission and we don't have it.
     */
    // TODO: Figure out why we don't have the permission declared in the manifest.
    @SmallTest
    public void testWithNumberNotEqualToVoiceMail() throws Exception {
        assertFalse(PhoneNumberUtils.isVoiceMailNumber("911"));
        assertFalse(PhoneNumberUtils.isVoiceMailNumber("tel:911"));
        assertFalse(PhoneNumberUtils.isVoiceMailNumber("+18001234567"));
        assertFalse(PhoneNumberUtils.isVoiceMailNumber(""));
        assertFalse(PhoneNumberUtils.isVoiceMailNumber(null));
        // FIXME:
        // assertTrue(PhoneNumberUtils.isVoiceMailNumber(mVoiceMailNumber));
    }

}
