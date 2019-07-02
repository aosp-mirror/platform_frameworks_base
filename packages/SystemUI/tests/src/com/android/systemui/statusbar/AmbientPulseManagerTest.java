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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AmbientPulseManagerTest extends AlertingNotificationManagerTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private static final int TEST_EXTENSION_TIME = 500;
    private AmbientPulseManager mAmbientPulseManager;
    private boolean mLivesPastNormalTime;

    protected AlertingNotificationManager createAlertingNotificationManager() {
        return mAmbientPulseManager;
    }

    @Before
    public void setUp() {
        mAmbientPulseManager = new AmbientPulseManager(mContext);
        mAmbientPulseManager.mMinimumDisplayTime = TEST_MINIMUM_DISPLAY_TIME;
        mAmbientPulseManager.mAutoDismissNotificationDecay = TEST_AUTO_DISMISS_TIME;
        mAmbientPulseManager.mExtensionTime = TEST_EXTENSION_TIME;
        super.setUp();
        mAmbientPulseManager.mHandler = mTestHandler;
    }

    @Test
    public void testExtendPulse() {
        mAmbientPulseManager.showNotification(mEntry);
        Runnable pastNormalTimeRunnable =
                () -> mLivesPastNormalTime = mAmbientPulseManager.isAlerting(mEntry.key);
        mTestHandler.postDelayed(pastNormalTimeRunnable,
                mAmbientPulseManager.mAutoDismissNotificationDecay +
                        mAmbientPulseManager.mExtensionTime / 2);
        mTestHandler.postDelayed(TEST_TIMEOUT_RUNNABLE, TEST_TIMEOUT_TIME);

        mAmbientPulseManager.extendPulse();

        // Wait for normal time runnable and extended remove runnable and process them on arrival.
        TestableLooper.get(this).processMessages(2);

        assertFalse("Test timed out", mTimedOut);
        assertTrue("Pulse was not extended", mLivesPastNormalTime);
        assertFalse(mAmbientPulseManager.isAlerting(mEntry.key));
    }
}

