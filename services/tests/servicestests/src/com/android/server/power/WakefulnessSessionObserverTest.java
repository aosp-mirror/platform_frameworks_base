/*
 * Copyright 2024 The Android Open Source Project
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

import static android.os.PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;
import static android.os.PowerManager.WAKE_REASON_POWER_BUTTON;

import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_UNKNOWN;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_USER_ACTIVITY_TOUCH;
import static com.android.server.power.WakefulnessSessionObserver.OFF_REASON_POWER_BUTTON;
import static com.android.server.power.WakefulnessSessionObserver.OVERRIDE_OUTCOME_CANCEL_POWER_BUTTON;
import static com.android.server.power.WakefulnessSessionObserver.OVERRIDE_OUTCOME_CANCEL_USER_INTERACTION;
import static com.android.server.power.WakefulnessSessionObserver.OVERRIDE_OUTCOME_TIMEOUT_SUCCESS;
import static com.android.server.power.WakefulnessSessionObserver.OVERRIDE_OUTCOME_TIMEOUT_USER_INITIATED_REVERT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class WakefulnessSessionObserverTest {
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT_MS = 30000;
    private static final int OVERRIDE_SCREEN_OFF_TIMEOUT_MS = 15000;
    private WakefulnessSessionObserver mWakefulnessSessionObserver;
    private Context mContext;
    private OffsettableClock mTestClock;
    @Mock
    private WakefulnessSessionObserver.WakefulnessSessionFrameworkStatsLogger
            mWakefulnessSessionFrameworkStatsLogger;
    private WakefulnessSessionObserver.Injector mInjector =
            new WakefulnessSessionObserver.Injector() {
                @Override
                WakefulnessSessionObserver.WakefulnessSessionFrameworkStatsLogger
                        getWakefulnessSessionFrameworkStatsLogger() {
                    return mWakefulnessSessionFrameworkStatsLogger;
                }
                @Override
                WakefulnessSessionObserver.Clock getClock() {
                    return mTestClock::now;
                }
            };

    @Before
    public void setUp() {
        mTestClock = new OffsettableClock.Stopped();

        MockitoAnnotations.initMocks(this);
        mContext = spy(new ContextWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext()));
        doReturn(mContext).when(mContext).getApplicationContext();

        final Resources res = spy(mContext.getResources());
        doReturn(OVERRIDE_SCREEN_OFF_TIMEOUT_MS).when(res).getInteger(
                com.android.internal.R.integer.config_screenTimeoutOverride);
        when(mContext.getResources()).thenReturn(res);
        FakeSettingsProvider.clearSettingsProvider();
        MockContentResolver mockContentResolver = new MockContentResolver();
        mockContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mockContentResolver);
        Settings.System.putIntForUser(mockContentResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                DEFAULT_SCREEN_OFF_TIMEOUT_MS, UserHandle.USER_CURRENT);

        mWakefulnessSessionObserver = new WakefulnessSessionObserver(mContext, mInjector);
    }

    @After
    public void tearDown() {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Test
    public void testOnUserActivity_updateActivity() {
        int firstActivity = PowerManager.USER_ACTIVITY_EVENT_BUTTON;
        long firstActivityTimestamp = mTestClock.now();
        int powerGroupId = 1;
        mWakefulnessSessionObserver.notifyUserActivity(
                firstActivityTimestamp, powerGroupId, firstActivity);
        assertThat(mWakefulnessSessionObserver.mPowerGroups.get(powerGroupId)
                .mCurrentUserActivityEvent).isEqualTo(firstActivity);

        int newActivity = PowerManager.USER_ACTIVITY_EVENT_ATTENTION;
        advanceTime(10L);
        long newActivityTimestamp = mTestClock.now();
        mWakefulnessSessionObserver.notifyUserActivity(
                newActivityTimestamp, powerGroupId, newActivity);
        assertThat(mWakefulnessSessionObserver.mPowerGroups.get(powerGroupId)
                .mCurrentUserActivityEvent).isEqualTo(newActivity);
        assertThat(mWakefulnessSessionObserver.mPowerGroups.get(powerGroupId)
                .mCurrentUserActivityTimestamp).isEqualTo(newActivityTimestamp);
        assertThat(mWakefulnessSessionObserver.mPowerGroups.get(powerGroupId)
                .mPrevUserActivityEvent).isEqualTo(firstActivity);
        assertThat(mWakefulnessSessionObserver.mPowerGroups.get(powerGroupId)
                .mPrevUserActivityTimestamp).isEqualTo(firstActivityTimestamp);

        int otherPowerGroupId = 2;
        mWakefulnessSessionObserver.notifyUserActivity(
                firstActivityTimestamp, otherPowerGroupId, firstActivity);
        assertThat(mWakefulnessSessionObserver.mPowerGroups.get(otherPowerGroupId)
                .mCurrentUserActivityEvent).isEqualTo(firstActivity);
        assertThat(mWakefulnessSessionObserver.mPowerGroups.get(otherPowerGroupId)
                .mCurrentUserActivityTimestamp).isEqualTo(firstActivityTimestamp);
    }

    @Test
    public void testOnWakeLockAcquired() {
        mWakefulnessSessionObserver.onWakeLockAcquired(PowerManager.DOZE_WAKE_LOCK);
        for (int idx = 0; idx < mWakefulnessSessionObserver.mPowerGroups.size(); idx++) {
            assertThat(mWakefulnessSessionObserver.mPowerGroups.valueAt(idx).isInOverrideTimeout())
                    .isFalse();
        }

        mWakefulnessSessionObserver.onWakeLockAcquired(
                PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK);
        for (int idx = 0; idx < mWakefulnessSessionObserver.mPowerGroups.size(); idx++) {
            assertThat(mWakefulnessSessionObserver.mPowerGroups.valueAt(idx).isInOverrideTimeout())
                    .isTrue();
        }
    }

    @Test
    public void testOnWakeLockReleased() {
        mWakefulnessSessionObserver.onWakeLockReleased(
                PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK, RELEASE_REASON_UNKNOWN);
        for (int idx = 0; idx < mWakefulnessSessionObserver.mPowerGroups.size(); idx++) {
            assertThat(mWakefulnessSessionObserver.mPowerGroups.valueAt(idx).isInOverrideTimeout())
                    .isFalse();
        }
    }

    @Test
    public void testOnWakefulnessChangeStarted_onDozing_UserActivityAttention_OverrideTimeout() {
        int powerGroupId = 1;
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                powerGroupId,
                PowerManagerInternal.WAKEFULNESS_AWAKE,
                WAKE_REASON_POWER_BUTTON,
                mTestClock.now());
        mWakefulnessSessionObserver.notifyUserActivity(
                mTestClock.now(), powerGroupId, PowerManager.USER_ACTIVITY_EVENT_ATTENTION);
        mWakefulnessSessionObserver.onWakeLockAcquired(
                PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                powerGroupId,
                PowerManagerInternal.WAKEFULNESS_DOZING,
                GO_TO_SLEEP_REASON_TIMEOUT,
                mTestClock.now());

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logTimeoutOverrideEvent(
                        powerGroupId, // powerGroupId
                        OVERRIDE_OUTCOME_TIMEOUT_SUCCESS, // overrideOutcome
                        OVERRIDE_SCREEN_OFF_TIMEOUT_MS, // override timeout ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default timeout ms
    }

    @Test
    public void testOnWakefulnessChangeStarted_onDozing_UserActivityButton() {
        advanceTime(5000L); // reset current timestamp for new test case
        int powerGroupId = 2;
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                powerGroupId,
                PowerManagerInternal.WAKEFULNESS_AWAKE,
                WAKE_REASON_POWER_BUTTON,
                mTestClock.now());

        int userActivity = PowerManager.USER_ACTIVITY_EVENT_DEVICE_STATE;
        long userActivityTime = mTestClock.now();
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTime, powerGroupId, userActivity);
        long advancedTime = 10L;
        advanceTime(advancedTime);
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTime, powerGroupId, PowerManager.USER_ACTIVITY_EVENT_BUTTON);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                powerGroupId,
                PowerManagerInternal.WAKEFULNESS_DOZING,
                GO_TO_SLEEP_REASON_POWER_BUTTON,
                mTestClock.now());

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logSessionEvent(
                        powerGroupId, // powerGroupId
                        OFF_REASON_POWER_BUTTON, // interactiveStateOffReason
                        advancedTime, // interactiveStateOnDurationMs
                        userActivity, // userActivity
                        advancedTime,  // lastUserActivityEventDurationMs
                        0); // reducedInteractiveStateOnDurationMs;

        verify(mWakefulnessSessionFrameworkStatsLogger, never())
                .logTimeoutOverrideEvent(anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testOnWakefulnessChangeStarted_onDozing_UserActivityButton_OverrideTimeout() {
        int powerGroupId = 1;
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                powerGroupId,
                PowerManagerInternal.WAKEFULNESS_AWAKE,
                WAKE_REASON_POWER_BUTTON,
                mTestClock.now());
        mWakefulnessSessionObserver.onWakeLockAcquired(
                PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK);

        int userActivity = PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY;
        long userActivityTime = mTestClock.now();
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTime, powerGroupId, userActivity);
        long advancedTime = 10L;
        advanceTime(advancedTime);
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTime, powerGroupId, PowerManager.USER_ACTIVITY_EVENT_BUTTON);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                powerGroupId,
                PowerManagerInternal.WAKEFULNESS_DOZING,
                GO_TO_SLEEP_REASON_POWER_BUTTON,
                mTestClock.now());

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logTimeoutOverrideEvent(
                        powerGroupId, // powerGroupId
                        OVERRIDE_OUTCOME_CANCEL_POWER_BUTTON, // overrideOutcome
                        OVERRIDE_SCREEN_OFF_TIMEOUT_MS, // override timeout ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default timeout ms

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logSessionEvent(
                        powerGroupId, // powerGroupId
                        OFF_REASON_POWER_BUTTON, // interactiveStateOffReason
                        advancedTime, // interactiveStateOnDurationMs
                        userActivity, // userActivity
                        advancedTime,  // lastUserActivityEventDurationMs
                        0); // reducedInteractiveStateOnDurationMs;
    }

    @Test
    public void testOnWakefulnessChangeStarted_inTimeoutOverride_onAwake_After_onDozing() {
        int powerGroupId = 1;
        mWakefulnessSessionObserver.onWakeLockAcquired(
                PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK);

        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                powerGroupId, PowerManagerInternal.WAKEFULNESS_DOZING,
                GO_TO_SLEEP_REASON_TIMEOUT, mTestClock.now());
        // awake after dozing
        advanceTime(10L);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                powerGroupId, PowerManagerInternal.WAKEFULNESS_AWAKE,
                WAKE_REASON_POWER_BUTTON, mTestClock.now());

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logTimeoutOverrideEvent(
                        powerGroupId,  // powerGroupId
                        OVERRIDE_OUTCOME_TIMEOUT_USER_INITIATED_REVERT, // overrideOutcome
                        OVERRIDE_SCREEN_OFF_TIMEOUT_MS, // override timeout ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default timeout ms
    }

    @Test
    public void testOnWakeLockReleased_UserActivityTouch() {
        int powerGroupId = 0;
        mWakefulnessSessionObserver.onWakeLockAcquired(
                PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK);
        advanceTime(5000L);
        mWakefulnessSessionObserver.onWakeLockReleased(
                PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK,
                RELEASE_REASON_USER_ACTIVITY_TOUCH);

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logTimeoutOverrideEvent(
                        powerGroupId, // powerGroupId
                        OVERRIDE_OUTCOME_CANCEL_USER_INTERACTION, // overrideOutcome
                        OVERRIDE_SCREEN_OFF_TIMEOUT_MS, // override timeout ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default timeout ms
    }

    private void advanceTime(long timeMs) {
        mTestClock.fastForward(timeMs);
    }
}
