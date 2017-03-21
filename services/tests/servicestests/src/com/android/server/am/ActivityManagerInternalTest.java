/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static org.junit.Assert.assertEquals;

import android.app.ActivityManagerInternal;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link ActivityManagerInternal}.
 *
 * To run the tests, use
 *
 * runtest -c com.android.server.am.ActivityManagerInternalTest frameworks-services
 *
 * or the following steps:
 *
 * Build: m FrameworksServicesTests
 * Install: adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -e class com.android.server.am.ActivityManagerInternalTest -w \
 *     com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ActivityManagerInternalTest {
    @Mock private ActivityManagerService.Injector mMockInjector;

    private ActivityManagerService mAms;
    private ActivityManagerInternal mAmi;
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mAms = new ActivityManagerService(mMockInjector);
        mAmi = mAms.new LocalService();
    }

    @Test
    public void testNotifyNetworkPolicyRulesUpdated() {
        // For checking there is no crash when there are no active uid records.
        mAmi.notifyNetworkPolicyRulesUpdated(111, 11);

        // Insert active uid records.
        final UidRecord record1 = addActiveUidRecord(222, 22);
        final UidRecord record2 = addActiveUidRecord(333, 33);
        // Notify that network policy rules are updated for uid 222.
        mAmi.notifyNetworkPolicyRulesUpdated(222, 44);
        assertEquals("UidRecord for uid 222 should be updated",
                44L, record1.lastNetworkUpdatedProcStateSeq);
        assertEquals("UidRecord for uid 333 should not be updated",
                33L, record2.lastNetworkUpdatedProcStateSeq);
    }

    private UidRecord addActiveUidRecord(int uid, long lastNetworkUpdatedProcStateSeq) {
        final UidRecord record = new UidRecord(uid);
        record.lastNetworkUpdatedProcStateSeq = lastNetworkUpdatedProcStateSeq;
        mAms.mActiveUids.put(uid, record);
        return record;
    }
}
