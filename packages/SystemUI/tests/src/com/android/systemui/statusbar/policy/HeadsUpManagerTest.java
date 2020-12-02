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

package com.android.systemui.statusbar.policy;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import android.app.Notification;
import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.statusbar.AlertingNotificationManager;
import com.android.systemui.statusbar.AlertingNotificationManagerTest;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class HeadsUpManagerTest extends AlertingNotificationManagerTest {
    private static final int TEST_A11Y_AUTO_DISMISS_TIME = 600;
    private static final int TEST_A11Y_TIMEOUT_TIME = 5_000;

    private AccessibilityManagerWrapper mAccessibilityMgr;
    private HeadsUpManager mHeadsUpManager;
    private boolean mLivesPastNormalTime;

    private final class TestableHeadsUpManager extends HeadsUpManager {
        TestableHeadsUpManager(Context context) {
            super(context);
            mMinimumDisplayTime = TEST_MINIMUM_DISPLAY_TIME;
            mAutoDismissNotificationDecay = TEST_AUTO_DISMISS_TIME;
        }
    }

    protected AlertingNotificationManager createAlertingNotificationManager() {
        return mHeadsUpManager;
    }

    @Before
    public void setUp() {
        mAccessibilityMgr = mDependency.injectMockDependency(AccessibilityManagerWrapper.class);

        mHeadsUpManager = new TestableHeadsUpManager(mContext);
        super.setUp();
        mHeadsUpManager.mHandler = mTestHandler;
    }

    @Test
    public void testShowNotification_autoDismissesWithAccessibilityTimeout() {
        doReturn(TEST_A11Y_AUTO_DISMISS_TIME).when(mAccessibilityMgr)
                .getRecommendedTimeoutMillis(anyInt(), anyInt());
        mHeadsUpManager.showNotification(mEntry);
        Runnable pastNormalTimeRunnable =
                () -> mLivesPastNormalTime = mHeadsUpManager.isAlerting(mEntry.getKey());
        mTestHandler.postDelayed(pastNormalTimeRunnable,
                        (TEST_A11Y_AUTO_DISMISS_TIME + TEST_AUTO_DISMISS_TIME) / 2);
        mTestHandler.postDelayed(TEST_TIMEOUT_RUNNABLE, TEST_A11Y_TIMEOUT_TIME);

        TestableLooper.get(this).processMessages(2);

        assertFalse("Test timed out", mTimedOut);
        assertTrue("Heads up should live long enough", mLivesPastNormalTime);
        assertFalse(mHeadsUpManager.isAlerting(mEntry.getKey()));
    }

    @Test
    public void testAlertEntryCompareTo_ongoingCallLessThanActiveRemoteInput() {
        HeadsUpManager.HeadsUpEntry ongoingCall = mHeadsUpManager.new HeadsUpEntry();
        ongoingCall.setEntry(new NotificationEntryBuilder()
                .setSbn(createNewSbn(0,
                        new Notification.Builder(mContext, "")
                                .setCategory(Notification.CATEGORY_CALL)
                                .setOngoing(true)))
                .build());

        HeadsUpManager.HeadsUpEntry activeRemoteInput = mHeadsUpManager.new HeadsUpEntry();
        activeRemoteInput.setEntry(new NotificationEntryBuilder()
                .setSbn(createNewNotification(1))
                .build());
        activeRemoteInput.remoteInputActive = true;

        assertThat(ongoingCall.compareTo(activeRemoteInput)).isLessThan(0);
        assertThat(activeRemoteInput.compareTo(ongoingCall)).isGreaterThan(0);
    }
}

