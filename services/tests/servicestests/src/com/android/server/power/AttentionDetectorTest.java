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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.attention.AttentionManagerInternal;
import android.attention.AttentionManagerInternal.AttentionCallbackInternal;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.attention.AttentionService;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class AttentionDetectorTest extends AndroidTestCase {

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private AttentionManagerInternal mAttentionManagerInternal;
    @Mock
    private WindowManagerInternal mWindowManagerInternal;
    @Mock
    private Runnable mOnUserAttention;
    private TestableAttentionDetector mAttentionDetector;
    private long mAttentionTimeout;
    private long mNextDimming;
    private int mIsSettingEnabled;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mPackageManager.getAttentionServicePackageName()).thenReturn("com.google.android.as");
        when(mPackageManager.checkPermission(any(), any())).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        when(mAttentionManagerInternal.checkAttention(anyLong(), any()))
                .thenReturn(true);
        when(mWindowManagerInternal.isKeyguardShowingAndNotOccluded()).thenReturn(false);
        mAttentionDetector = new TestableAttentionDetector();
        mAttentionDetector.onWakefulnessChangeStarted(PowerManagerInternal.WAKEFULNESS_AWAKE);
        mAttentionDetector.setAttentionServiceSupported(true);
        mNextDimming = SystemClock.uptimeMillis() + 3000L;

        // Save the existing state.
        mIsSettingEnabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.ADAPTIVE_SLEEP, 0, UserHandle.USER_CURRENT);

        Settings.System.putIntForUser(getContext().getContentResolver(),
                Settings.System.ADAPTIVE_SLEEP, 1, UserHandle.USER_CURRENT);
        mAttentionDetector.updateEnabledFromSettings(getContext());
    }

    @After
    public void tearDown() {
        Settings.System.putIntForUser(getContext().getContentResolver(),
                Settings.System.ADAPTIVE_SLEEP, mIsSettingEnabled, UserHandle.USER_CURRENT);
    }

    @Test
    public void testOnUserActivity_checksAttention() {
        long when = registerAttention();
        verify(mAttentionManagerInternal).checkAttention(anyLong(), any());
        assertThat(when).isLessThan(mNextDimming);
    }

    @Test
    public void testOnUserActivity_doesntCheckIfNotEnabled() {
        Settings.System.putIntForUser(getContext().getContentResolver(),
                Settings.System.ADAPTIVE_SLEEP, 0, UserHandle.USER_CURRENT);
        mAttentionDetector.updateEnabledFromSettings(getContext());
        long when = registerAttention();
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
        assertThat(mNextDimming).isEqualTo(when);
    }

    @Test
    public void testOnUserActivity_doesntCheckIfNotSupported() {
        mAttentionDetector.setAttentionServiceSupported(false);
        long when = registerAttention();
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
        assertThat(mNextDimming).isEqualTo(when);
    }

    @Test
    public void testOnUserActivity_doesntCheckIfInLockscreen() {
        when(mWindowManagerInternal.isKeyguardShowingAndNotOccluded()).thenReturn(true);

        long when = registerAttention();
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
        assertThat(mNextDimming).isEqualTo(when);
    }

    @Test
    public void testOnUserActivity_doesntCheckIfNotSufficientPermissions() {
        when(mPackageManager.checkPermission(any(), any())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        long when = registerAttention();
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
        assertThat(mNextDimming).isEqualTo(when);
    }

    @Test
    public void testOnUserActivity_disablesSettingIfNotSufficientPermissions() {
        when(mPackageManager.checkPermission(any(), any())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        registerAttention();
        boolean enabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.ADAPTIVE_SLEEP, 0, UserHandle.USER_CURRENT) == 1;
        assertFalse(enabled);
    }

    @Test
    public void testOnUserActivity_doesntCrashIfNoAttentionService() {
        mAttentionManagerInternal = null;
        registerAttention();
        // Does not crash.
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
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
    }

    @Test
    public void testUpdateUserActivity_schedulesTheNextCheck() {
        long now = SystemClock.uptimeMillis();
        mNextDimming = now;
        mAttentionDetector.onUserActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        long nextTimeout = mAttentionDetector.updateUserActivity(mNextDimming + 5000L);
        assertThat(nextTimeout).isEqualTo(mNextDimming + 5000L);
    }

    @Test
    public void testOnUserActivity_ignoresAfterMaximumExtension() {
        long now = SystemClock.uptimeMillis();
        mAttentionDetector.onUserActivity(now - 15000L, PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        mAttentionDetector.updateUserActivity(now + 2000L);
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
    }

    @Test
    public void testOnUserActivity_ignoresIfAlreadyDoneForThatNextScreenDimming() {
        long when = registerAttention();
        verify(mAttentionManagerInternal).checkAttention(anyLong(), any());
        assertThat(when).isLessThan(mNextDimming);
        clearInvocations(mAttentionManagerInternal);

        long redundantWhen = mAttentionDetector.updateUserActivity(mNextDimming);
        assertThat(redundantWhen).isEqualTo(mNextDimming);
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
    }

    @Test
    public void testOnUserActivity_skipsIfAlreadyScheduled() {
        registerAttention();
        reset(mAttentionManagerInternal);
        long when = mAttentionDetector.updateUserActivity(mNextDimming + 1);
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
        assertThat(when).isLessThan(mNextDimming);
    }

    @Test
    public void testOnWakefulnessChangeStarted_cancelsRequestWhenNotAwake() {
        registerAttention();
        mAttentionDetector.onWakefulnessChangeStarted(PowerManagerInternal.WAKEFULNESS_ASLEEP);

        ArgumentCaptor<AttentionCallbackInternal> callbackCaptor = ArgumentCaptor.forClass(
                AttentionCallbackInternal.class);
        verify(mAttentionManagerInternal).cancelAttentionCheck(callbackCaptor.capture());
        assertEquals(callbackCaptor.getValue(), mAttentionDetector.mCallback);
    }

    @Test
    public void testCallbackOnSuccess_ignoresIfNoAttention() {
        registerAttention();
        mAttentionDetector.mCallback.onSuccess(AttentionService.ATTENTION_SUCCESS_ABSENT,
                SystemClock.uptimeMillis());
        verify(mOnUserAttention, never()).run();
    }

    @Test
    public void testCallbackOnSuccess_callsCallback() {
        registerAttention();
        mAttentionDetector.mCallback.onSuccess(AttentionService.ATTENTION_SUCCESS_PRESENT,
                SystemClock.uptimeMillis());
        verify(mOnUserAttention).run();
    }

    @Test
    public void testCallbackOnSuccess_doesNotCallNonCurrentCallback() {
        mAttentionDetector.mRequestId = 5;
        registerAttention(); // mRequestId = 6;
        mAttentionDetector.mRequestId = 55;

        mAttentionDetector.mCallback.onSuccess(AttentionService.ATTENTION_SUCCESS_PRESENT,
                SystemClock.uptimeMillis());
        verify(mOnUserAttention, never()).run();
    }

    @Test
    public void testCallbackOnSuccess_callsCallbackAfterOldCallbackCame() {
        mAttentionDetector.mRequestId = 5;
        registerAttention(); // mRequestId = 6;
        mAttentionDetector.mRequestId = 55;

        mAttentionDetector.mCallback.onSuccess(AttentionService.ATTENTION_SUCCESS_PRESENT,
                SystemClock.uptimeMillis()); // old callback came
        mAttentionDetector.mRequestId = 6; // now back to current
        mAttentionDetector.mCallback.onSuccess(AttentionService.ATTENTION_SUCCESS_PRESENT,
                SystemClock.uptimeMillis());
        verify(mOnUserAttention).run();
    }

    @Test
    public void testCallbackOnSuccess_DoesNotGoIntoInfiniteLoop() {
        // Mimic real behavior
        doAnswer((invocation) -> {
            // Mimic a cache hit: calling onSuccess() immediately
            registerAttention();
            mAttentionDetector.mRequestId++;
            mAttentionDetector.mCallback.onSuccess(AttentionService.ATTENTION_SUCCESS_PRESENT,
                    SystemClock.uptimeMillis());
            return null;
        }).when(mOnUserAttention).run();

        registerAttention();
        // This test fails with literal stack overflow:
        // e.g. java.lang.StackOverflowError: stack size 1039KB
        mAttentionDetector.mCallback.onSuccess(AttentionService.ATTENTION_SUCCESS_PRESENT,
                SystemClock.uptimeMillis());

        // We don't actually get here when the test fails
        verify(mOnUserAttention, atMost(1)).run();
    }

    @Test
    public void testCallbackOnFailure_unregistersCurrentRequestCode() {
        registerAttention();
        mAttentionDetector.mCallback.onFailure(AttentionService.ATTENTION_FAILURE_UNKNOWN);
        mAttentionDetector.mCallback.onSuccess(AttentionService.ATTENTION_SUCCESS_PRESENT,
                SystemClock.uptimeMillis());
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
            mWindowManager = mWindowManagerInternal;
            mPackageManager = AttentionDetectorTest.this.mPackageManager;
            mContentResolver = getContext().getContentResolver();
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
