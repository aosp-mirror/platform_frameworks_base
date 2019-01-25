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

package com.android.server.power;

import static android.os.BatteryStats.Uid.NUM_USER_ACTIVITY_TYPES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.attention.AttentionManagerInternal;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.SystemClock;
import android.service.attention.AttentionService;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class AttentionDetectorTest extends AndroidTestCase {

    private @Mock AttentionManagerInternal mAttentionManagerInternal;
    private @Mock Runnable mOnUserAttention;
    private TestableAttentionDetector mAttentionDetector;
    private long mAttentionTimeout;
    private long mNextDimming;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mAttentionManagerInternal.checkAttention(anyInt(), anyLong(), any()))
                .thenReturn(true);
        mAttentionDetector = new TestableAttentionDetector();
        mAttentionDetector.onWakefulnessChangeStarted(PowerManagerInternal.WAKEFULNESS_AWAKE);
        mAttentionDetector.setAttentionServiceSupported(true);
        mNextDimming = SystemClock.uptimeMillis() + 3000L;
    }

    @Test
    public void testOnUserActivity_checksAttention() {
        long when = registerAttention();
        verify(mAttentionManagerInternal).checkAttention(anyInt(), anyLong(), any());
        assertThat(when).isLessThan(mNextDimming);
    }

    @Test
    public void testOnUserActivity_doesntCheckIfNotSupported() {
        mAttentionDetector.setAttentionServiceSupported(false);
        long when = registerAttention();
        verify(mAttentionManagerInternal, never()).checkAttention(anyInt(), anyLong(), any());
        assertThat(mNextDimming).isEqualTo(when);
    }

    @Test
    public void onUserActivity_ignoresWhiteListedActivityTypes() {
        for (int i = 0; i < NUM_USER_ACTIVITY_TYPES; i++) {
            int result = mAttentionDetector.onUserActivity(SystemClock.uptimeMillis(), i);
            if (result == -1) {
                throw new AssertionError("User activity " + i + " isn't listed in"
                        + " AttentionDetector#onUserActivity. Please consider how this new activity"
                        + " type affects the attention service.");
            }
        }
    }

    @Test
    public void testUpdateUserActivity_ignoresWhenItsNotTimeYet() {
        long now = SystemClock.uptimeMillis();
        mNextDimming = now;
        mAttentionDetector.onUserActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        mAttentionDetector.updateUserActivity(mNextDimming + 5000L);
        verify(mAttentionManagerInternal, never()).checkAttention(anyInt(), anyLong(), any());
    }

    @Test
    public void testOnUserActivity_ignoresAfterMaximumExtension() {
        long now = SystemClock.uptimeMillis();
        mAttentionDetector.onUserActivity(now - 15000L, PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        mAttentionDetector.updateUserActivity(now + 2000L);
        verify(mAttentionManagerInternal, never()).checkAttention(anyInt(), anyLong(), any());
    }

    @Test
    public void testOnUserActivity_skipsIfAlreadyScheduled() {
        registerAttention();
        reset(mAttentionManagerInternal);
        long when = mAttentionDetector.updateUserActivity(mNextDimming);
        verify(mAttentionManagerInternal, never()).checkAttention(anyInt(), anyLong(), any());
        assertThat(when).isLessThan(mNextDimming);
    }

    @Test
    public void testOnWakefulnessChangeStarted_cancelsRequestWhenNotAwake() {
        registerAttention();
        mAttentionDetector.onWakefulnessChangeStarted(PowerManagerInternal.WAKEFULNESS_ASLEEP);
        verify(mAttentionManagerInternal).cancelAttentionCheck(anyInt());
    }

    @Test
    public void testCallbackOnSuccess_ignoresIfNoAttention() {
        registerAttention();
        mAttentionDetector.mCallback.onSuccess(mAttentionDetector.getRequestCode(),
                AttentionService.ATTENTION_SUCCESS_ABSENT, SystemClock.uptimeMillis());
        verify(mOnUserAttention, never()).run();
    }

    @Test
    public void testCallbackOnSuccess_callsCallback() {
        registerAttention();
        mAttentionDetector.mCallback.onSuccess(mAttentionDetector.getRequestCode(),
                AttentionService.ATTENTION_SUCCESS_PRESENT, SystemClock.uptimeMillis());
        verify(mOnUserAttention).run();
    }

    @Test
    public void testCallbackOnFailure_unregistersCurrentRequestCode() {
        registerAttention();
        mAttentionDetector.mCallback.onFailure(mAttentionDetector.getRequestCode(),
                AttentionService.ATTENTION_FAILURE_UNKNOWN);
        mAttentionDetector.mCallback.onSuccess(mAttentionDetector.getRequestCode(),
                AttentionService.ATTENTION_SUCCESS_PRESENT, SystemClock.uptimeMillis());
        verify(mOnUserAttention, never()).run();
    }

    private long registerAttention() {
        mAttentionTimeout = 4000L;
        mAttentionDetector.onUserActivity(SystemClock.uptimeMillis(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        return mAttentionDetector.updateUserActivity(mNextDimming);
    }

    private class TestableAttentionDetector extends AttentionDetector {

        private boolean mAttentionServiceSupported;

        TestableAttentionDetector() {
            super(AttentionDetectorTest.this.mOnUserAttention, new Object());
            mAttentionManager = mAttentionManagerInternal;
            mMaximumExtensionMillis = 10000L;
        }

        void setAttentionServiceSupported(boolean supported) {
            mAttentionServiceSupported = supported;
        }

        @Override
        public boolean isAttentionServiceSupported() {
            return mAttentionServiceSupported;
        }

        @Override
        public long getAttentionTimeout() {
            return mAttentionTimeout;
        }
    }
}
