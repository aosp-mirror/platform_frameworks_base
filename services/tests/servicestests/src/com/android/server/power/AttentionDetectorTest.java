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
import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;

import static com.android.server.power.AttentionDetector.DEFAULT_POST_DIM_CHECK_DURATION_MILLIS;
import static com.android.server.power.AttentionDetector.DEFAULT_PRE_DIM_CHECK_DURATION_MILLIS;
import static com.android.server.power.AttentionDetector.KEY_MAX_EXTENSION_MILLIS;
import static com.android.server.power.AttentionDetector.KEY_POST_DIM_CHECK_DURATION_MILLIS;
import static com.android.server.power.AttentionDetector.KEY_PRE_DIM_CHECK_DURATION_MILLIS;

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
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
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
    private static final long DEFAULT_DIM_DURATION_MILLIS = 6_000L;

    @Mock
    private AttentionManagerInternal mAttentionManagerInternal;
    @Mock
    private WindowManagerInternal mWindowManagerInternal;
    @Mock
    private Runnable mOnUserAttention;
    private TestableAttentionDetector mAttentionDetector;
    private AttentionDetector mRealAttentionDetector;
    private long mNextDimming;
    private int mIsSettingEnabled;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mAttentionManagerInternal.checkAttention(anyLong(), any()))
                .thenReturn(true);
        when(mWindowManagerInternal.isKeyguardShowingAndNotOccluded()).thenReturn(false);
        mAttentionDetector = new TestableAttentionDetector();
        mRealAttentionDetector = new AttentionDetector(mOnUserAttention, new Object());
        mRealAttentionDetector.mDefaultMaximumExtensionMillis = 900_000L;
        mAttentionDetector.onWakefulnessChangeStarted(PowerManagerInternal.WAKEFULNESS_AWAKE);
        mAttentionDetector.setAttentionServiceSupported(true);
        mNextDimming = SystemClock.uptimeMillis() + 3000L;

        // Save the existing state.
        mIsSettingEnabled = Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.ADAPTIVE_SLEEP, 0, UserHandle.USER_CURRENT);

        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.ADAPTIVE_SLEEP, 1, UserHandle.USER_CURRENT);
        mAttentionDetector.updateEnabledFromSettings(getContext());

        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_EXTENSION_MILLIS,
                Long.toString(10_000L), false);
    }

    @After
    public void tearDown() {
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.ADAPTIVE_SLEEP, mIsSettingEnabled, UserHandle.USER_CURRENT);

        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_PRE_DIM_CHECK_DURATION_MILLIS,
                Long.toString(DEFAULT_PRE_DIM_CHECK_DURATION_MILLIS), false);
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_POST_DIM_CHECK_DURATION_MILLIS,
                Long.toString(DEFAULT_POST_DIM_CHECK_DURATION_MILLIS), false);
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_EXTENSION_MILLIS,
                Long.toString(mRealAttentionDetector.mDefaultMaximumExtensionMillis), false);
    }

    @Test
    public void testOnUserActivity_checksAttention() {
        long when = registerAttention();
        verify(mAttentionManagerInternal).checkAttention(anyLong(), any());
        assertThat(when).isLessThan(mNextDimming);
    }

    @Test
    public void testOnUserActivity_doesntCheckIfNotEnabled() {
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.ADAPTIVE_SLEEP, 0, UserHandle.USER_CURRENT);
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
        mAttentionDetector.updateUserActivity(mNextDimming + 5000L, DEFAULT_DIM_DURATION_MILLIS);
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
    }

    @Test
    public void testUpdateUserActivity_schedulesTheNextCheck() {
        long now = SystemClock.uptimeMillis();
        mNextDimming = now;
        mAttentionDetector.onUserActivity(now, PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        long nextTimeout = mAttentionDetector.updateUserActivity(mNextDimming + 5000L,
                DEFAULT_DIM_DURATION_MILLIS);
        assertThat(nextTimeout).isEqualTo(mNextDimming + 5000L);
    }

    @Test
    public void testOnUserActivity_ignoresAfterMaximumExtension() {
        long now = SystemClock.uptimeMillis();
        mAttentionDetector.onUserActivity(now - 15000L, PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        mAttentionDetector.updateUserActivity(now + 2000L, DEFAULT_DIM_DURATION_MILLIS);
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
    }

    @Test
    public void testOnUserActivity_ignoresIfAlreadyDoneForThatNextScreenDimming() {
        long when = registerAttention();
        verify(mAttentionManagerInternal).checkAttention(anyLong(), any());
        assertThat(when).isLessThan(mNextDimming);
        clearInvocations(mAttentionManagerInternal);

        long redundantWhen = mAttentionDetector.updateUserActivity(mNextDimming,
                DEFAULT_DIM_DURATION_MILLIS);
        assertThat(redundantWhen).isEqualTo(mNextDimming);
        verify(mAttentionManagerInternal, never()).checkAttention(anyLong(), any());
    }

    @Test
    public void testOnUserActivity_skipsIfAlreadyScheduled() {
        registerAttention();
        reset(mAttentionManagerInternal);
        long when = mAttentionDetector.updateUserActivity(mNextDimming + 1,
                DEFAULT_DIM_DURATION_MILLIS);
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

    @Test
    public void testGetPreDimCheckDurationMillis_handlesGoodFlagValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_PRE_DIM_CHECK_DURATION_MILLIS, "555", false);
        assertThat(mRealAttentionDetector.getPreDimCheckDurationMillis()).isEqualTo(555);
    }

    @Test
    public void testGetPreDimCheckDurationMillis_rejectsNegativeValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_PRE_DIM_CHECK_DURATION_MILLIS, "-50", false);
        assertThat(mRealAttentionDetector.getPreDimCheckDurationMillis()).isEqualTo(
                DEFAULT_PRE_DIM_CHECK_DURATION_MILLIS);
    }

    @Test
    public void testGetPreDimCheckDurationMillis_rejectsTooBigValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_PRE_DIM_CHECK_DURATION_MILLIS, "20000", false);
        assertThat(mRealAttentionDetector.getPreDimCheckDurationMillis()).isEqualTo(
                DEFAULT_PRE_DIM_CHECK_DURATION_MILLIS);
    }

    @Test
    public void testGetPreDimCheckDurationMillis_handlesBadFlagValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_PRE_DIM_CHECK_DURATION_MILLIS, "20000k", false);
        assertThat(mRealAttentionDetector.getPreDimCheckDurationMillis()).isEqualTo(
                DEFAULT_PRE_DIM_CHECK_DURATION_MILLIS);

        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_PRE_DIM_CHECK_DURATION_MILLIS, "0.25", false);
        assertThat(mRealAttentionDetector.getPreDimCheckDurationMillis()).isEqualTo(
                DEFAULT_PRE_DIM_CHECK_DURATION_MILLIS);
    }

    @Test
    public void testGetPostDimCheckDurationMillis_handlesGoodFlagValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_POST_DIM_CHECK_DURATION_MILLIS, "333", false);
        assertThat(mRealAttentionDetector.getPostDimCheckDurationMillis()).isEqualTo(333);
    }

    @Test
    public void testGetPostDimCheckDurationMillis_rejectsNegativeValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_POST_DIM_CHECK_DURATION_MILLIS, "-50", false);
        assertThat(mRealAttentionDetector.getPostDimCheckDurationMillis()).isEqualTo(
                DEFAULT_POST_DIM_CHECK_DURATION_MILLIS);
    }

    @Test
    public void testGetPostDimCheckDurationMillis_rejectsTooBigValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_POST_DIM_CHECK_DURATION_MILLIS, "20000", false);
        assertThat(mRealAttentionDetector.getPostDimCheckDurationMillis()).isEqualTo(
                DEFAULT_POST_DIM_CHECK_DURATION_MILLIS);
    }

    @Test
    public void testGetPostDimCheckDurationMillis_handlesBadFlagValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_POST_DIM_CHECK_DURATION_MILLIS, "20000k", false);
        assertThat(mRealAttentionDetector.getPostDimCheckDurationMillis()).isEqualTo(
                DEFAULT_POST_DIM_CHECK_DURATION_MILLIS);

        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_POST_DIM_CHECK_DURATION_MILLIS, "0.25", false);
        assertThat(mRealAttentionDetector.getPostDimCheckDurationMillis()).isEqualTo(
                DEFAULT_POST_DIM_CHECK_DURATION_MILLIS);
    }

    @Test
    public void testGetMaxExtensionMillis_handlesGoodFlagValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_EXTENSION_MILLIS, "123", false);
        assertThat(mRealAttentionDetector.getMaxExtensionMillis()).isEqualTo(123);
    }

    @Test
    public void testGetMaxExtensionMillis_rejectsNegativeValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_EXTENSION_MILLIS, "-50", false);
        assertThat(mRealAttentionDetector.getMaxExtensionMillis()).isEqualTo(
                mRealAttentionDetector.mDefaultMaximumExtensionMillis);
    }

    @Test
    public void testGetMaxExtensionMillis_rejectsTooBigValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_EXTENSION_MILLIS, "9900000", false);
        assertThat(mRealAttentionDetector.getMaxExtensionMillis()).isEqualTo(
                mRealAttentionDetector.mDefaultMaximumExtensionMillis);
    }

    @Test
    public void testGetMaxExtensionMillis_handlesBadFlagValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_EXTENSION_MILLIS, "20000k", false);
        assertThat(mRealAttentionDetector.getMaxExtensionMillis()).isEqualTo(
                mRealAttentionDetector.mDefaultMaximumExtensionMillis);

        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_EXTENSION_MILLIS, "0.25", false);
        assertThat(mRealAttentionDetector.getMaxExtensionMillis()).isEqualTo(
                mRealAttentionDetector.mDefaultMaximumExtensionMillis);
    }

    private long registerAttention() {
        mAttentionDetector.mPreDimCheckDurationMillis = 4000L;
        mAttentionDetector.onUserActivity(SystemClock.uptimeMillis(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH);
        return mAttentionDetector.updateUserActivity(mNextDimming, DEFAULT_DIM_DURATION_MILLIS);
    }

    private class TestableAttentionDetector extends AttentionDetector {
        private boolean mAttentionServiceSupported;

        TestableAttentionDetector() {
            super(AttentionDetectorTest.this.mOnUserAttention, new Object());
            mAttentionManager = mAttentionManagerInternal;
            mWindowManager = mWindowManagerInternal;
            mContentResolver = getContext().getContentResolver();
        }

        void setAttentionServiceSupported(boolean supported) {
            mAttentionServiceSupported = supported;
        }

        @Override
        public boolean isAttentionServiceSupported() {
            return mAttentionServiceSupported;
        }
    }
}
