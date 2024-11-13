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

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON;
import static android.os.PowerManager.GO_TO_SLEEP_REASON_TIMEOUT;
import static android.os.PowerManager.WAKE_REASON_POWER_BUTTON;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.DEFAULT_DISPLAY_GROUP;

import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_UNKNOWN;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_USER_ACTIVITY_TOUCH;
import static com.android.server.power.WakefulnessSessionObserver.OFF_REASON_POWER_BUTTON;
import static com.android.server.power.WakefulnessSessionObserver.OVERRIDE_OUTCOME_CANCEL_POWER_BUTTON;
import static com.android.server.power.WakefulnessSessionObserver.OVERRIDE_OUTCOME_CANCEL_USER_INTERACTION;
import static com.android.server.power.WakefulnessSessionObserver.OVERRIDE_OUTCOME_TIMEOUT_SUCCESS;
import static com.android.server.power.WakefulnessSessionObserver.OVERRIDE_OUTCOME_TIMEOUT_USER_INITIATED_REVERT;
import static com.android.server.power.WakefulnessSessionObserver.POLICY_REASON_BRIGHT_INITIATED_REVERT;
import static com.android.server.power.WakefulnessSessionObserver.POLICY_REASON_BRIGHT_UNDIM;
import static com.android.server.power.WakefulnessSessionObserver.POLICY_REASON_OFF_POWER_BUTTON;
import static com.android.server.power.WakefulnessSessionObserver.POLICY_REASON_OFF_TIMEOUT;

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
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

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
    private static final int DISPLAY_PORT = 0xFF;
    private static final long DISPLAY_MODEL = 0xEEEEEEEEL;
    private WakefulnessSessionObserver mWakefulnessSessionObserver;
    private Context mContext;
    private OffsettableClock mTestClock;
    @Mock
    private WakefulnessSessionObserver.WakefulnessSessionFrameworkStatsLogger
            mWakefulnessSessionFrameworkStatsLogger;
    @Mock
    private DisplayManagerInternal mDisplayManagerInternal;

    private TestHandler mHandler;
    @Before
    public void setUp() {
        mTestClock = new OffsettableClock.Stopped();

        MockitoAnnotations.initMocks(this);
        mContext = spy(new ContextWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext()));
        doReturn(mContext).when(mContext).getApplicationContext();

        final Resources res = spy(mContext.getResources());
        doReturn(OVERRIDE_SCREEN_OFF_TIMEOUT_MS).when(res).getInteger(
                R.integer.config_screenTimeoutOverride);
        when(mContext.getResources()).thenReturn(res);
        FakeSettingsProvider.clearSettingsProvider();
        MockContentResolver mockContentResolver = new MockContentResolver();
        mockContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mockContentResolver);
        Settings.System.putIntForUser(mockContentResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                DEFAULT_SCREEN_OFF_TIMEOUT_MS, UserHandle.USER_CURRENT);

        final DisplayInfo info = new DisplayInfo();
        info.address = DisplayAddress.fromPortAndModel(DISPLAY_PORT, DISPLAY_MODEL);
        mHandler = new TestHandler(null);
        mWakefulnessSessionObserver = new WakefulnessSessionObserver(
                mContext, new WakefulnessSessionObserver.Injector() {
                    @Override
                    WakefulnessSessionObserver.WakefulnessSessionFrameworkStatsLogger
                            getWakefulnessSessionFrameworkStatsLogger() {
                        return mWakefulnessSessionFrameworkStatsLogger;
                    }
                    @Override
                    WakefulnessSessionObserver.Clock getClock() {
                        return mTestClock::now;
                    }
                    @Override
                    Handler getHandler() {
                        return mHandler;
                    }
                    @Override
                    DisplayManagerInternal getDisplayManagerInternal() {
                        when(mDisplayManagerInternal.getDisplayInfo(DEFAULT_DISPLAY))
                                .thenReturn(info);
                        return mDisplayManagerInternal;
                    }
                }
        );
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

    @Test
    public void testOnScreenPolicyUpdate_OffByTimeout() {
        int userActivity = PowerManager.USER_ACTIVITY_EVENT_ATTENTION;
        long userActivityTimestamp = mTestClock.now();
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTimestamp, DEFAULT_DISPLAY_GROUP, userActivity);
        mWakefulnessSessionObserver.onScreenPolicyUpdate(
                mTestClock.now(), DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                DEFAULT_DISPLAY_GROUP, PowerManagerInternal.WAKEFULNESS_AWAKE,
                WAKE_REASON_POWER_BUTTON, mTestClock.now());
        int advancedTime = 5;
        advanceTime(advancedTime);
        mWakefulnessSessionObserver.onScreenPolicyUpdate(mTestClock.now(), DEFAULT_DISPLAY_GROUP,
                POLICY_OFF);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                DEFAULT_DISPLAY_GROUP, PowerManagerInternal.WAKEFULNESS_ASLEEP,
                GO_TO_SLEEP_REASON_TIMEOUT, mTestClock.now());

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logDimEvent(
                        DISPLAY_PORT, // physical display port id
                        POLICY_REASON_OFF_TIMEOUT, // policy reason
                        userActivity, // last user activity event
                        advancedTime, // last user activity timestamp
                        advancedTime, // dim duration ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default Timeout Ms
    }

    @Test
    public void testOnScreenPolicyUpdate_NoLogging_NotDefaultDisplayGroup() {
        int powerGroupId = 1;
        int userActivity = PowerManager.USER_ACTIVITY_EVENT_ATTENTION;
        long userActivityTimestamp = mTestClock.now();
        int advancedTime = 5;
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTimestamp, powerGroupId, userActivity);
        mWakefulnessSessionObserver.onScreenPolicyUpdate(
                mTestClock.now(), powerGroupId, POLICY_DIM);
        advanceTime(advancedTime);
        mWakefulnessSessionObserver.onScreenPolicyUpdate(mTestClock.now(), powerGroupId,
                POLICY_OFF);

        verify(mWakefulnessSessionFrameworkStatsLogger, never())
                .logDimEvent(
                        DISPLAY_PORT, // physical display port id
                        POLICY_REASON_OFF_TIMEOUT, // policy reason
                        userActivity, // last user activity event
                        advancedTime, // last user activity timestamp
                        advancedTime, // dim duration ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default Timeout Ms
    }

    @Test
    public void testOnScreenPolicyUpdate_OffByPowerButton() {
        // ----- initialize start -----
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                DEFAULT_DISPLAY_GROUP, PowerManagerInternal.WAKEFULNESS_AWAKE,
                WAKE_REASON_POWER_BUTTON, mTestClock.now());

        int userActivity = PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY;
        long userActivityTimestamp = mTestClock.now();
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTimestamp, DEFAULT_DISPLAY_GROUP, userActivity);
        mWakefulnessSessionObserver.onScreenPolicyUpdate(
                mTestClock.now(), DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        // ----- initialize end -----

        int dimDuration = 500;
        advanceTime(dimDuration);
        int userActivityDuration = dimDuration;
        mWakefulnessSessionObserver.notifyUserActivity(
                mTestClock.now(), DEFAULT_DISPLAY_GROUP, PowerManager.USER_ACTIVITY_EVENT_BUTTON);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                DEFAULT_DISPLAY_GROUP, PowerManagerInternal.WAKEFULNESS_ASLEEP,
                GO_TO_SLEEP_REASON_POWER_BUTTON, mTestClock.now());

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logDimEvent(
                        DISPLAY_PORT, // physical display port id
                        POLICY_REASON_OFF_POWER_BUTTON, // policy reason
                        userActivity, // last user activity event
                        userActivityDuration, // last user activity timestamp
                        dimDuration, // dim duration ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default Timeout Ms
        assertThat(mHandler.getPendingMessages()).isEmpty();
    }

    @Test
    public void testOnScreenPolicyUpdate_Undim() {
        // ----- initialize start -----
        int userActivity = PowerManager.USER_ACTIVITY_EVENT_TOUCH;
        long userActivityTimestamp = mTestClock.now();
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTimestamp, DEFAULT_DISPLAY_GROUP, userActivity);
        mWakefulnessSessionObserver.onScreenPolicyUpdate(
                mTestClock.now(), DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mWakefulnessSessionObserver.mPowerGroups.get(DEFAULT_DISPLAY_GROUP).mIsInteractive = true;
        // ----- initialize end -----

        int dimDurationMs = 5;
        advanceTime(dimDurationMs);
        mWakefulnessSessionObserver.onScreenPolicyUpdate(
                mTestClock.now(), DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        int expectedLastUserActivityTimeMs = (int) (mTestClock.now() - userActivityTimestamp);

        mHandler.flush();
        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logDimEvent(
                        DISPLAY_PORT, // physical display port id
                        POLICY_REASON_BRIGHT_UNDIM, // policy reason
                        userActivity, // last user activity event
                        expectedLastUserActivityTimeMs, // last user activity timestamp
                        dimDurationMs, // dim duration ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default Timeout Ms
    }

    @Test
    public void testOnScreenPolicyUpdate_BrightInitiatedRevert() {
        // ----- initialize start -----
        mWakefulnessSessionObserver.onScreenPolicyUpdate(
                mTestClock.now(), DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        int dimDurationMs = 500;
        advanceTime(dimDurationMs);
        int userActivity = PowerManager.USER_ACTIVITY_EVENT_BUTTON;
        long userActivityTimestamp = mTestClock.now();
        mWakefulnessSessionObserver.notifyUserActivity(
                userActivityTimestamp, DEFAULT_DISPLAY_GROUP, userActivity);
        int userActivityTime = 5;
        advanceTime(userActivityTime);
        dimDurationMs += userActivityTime;
        mWakefulnessSessionObserver.onScreenPolicyUpdate(
                mTestClock.now(), DEFAULT_DISPLAY_GROUP, POLICY_OFF);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                DEFAULT_DISPLAY_GROUP, PowerManagerInternal.WAKEFULNESS_ASLEEP,
                GO_TO_SLEEP_REASON_POWER_BUTTON, mTestClock.now());

        mWakefulnessSessionObserver.mPowerGroups.get(DEFAULT_DISPLAY_GROUP)
                .mPastDimDurationMs = dimDurationMs;
        // ----- initialize end -----

        int advancedTime = 5;
        advanceTime(advancedTime); // shorter than 5000 ms
        userActivityTime += advancedTime;
        mWakefulnessSessionObserver.onScreenPolicyUpdate(mTestClock.now(), DEFAULT_DISPLAY_GROUP,
                POLICY_BRIGHT);
        mWakefulnessSessionObserver.onWakefulnessChangeStarted(
                DEFAULT_DISPLAY_GROUP, PowerManagerInternal.WAKEFULNESS_AWAKE,
                WAKE_REASON_POWER_BUTTON, mTestClock.now());

        verify(mWakefulnessSessionFrameworkStatsLogger)
                .logDimEvent(
                        DISPLAY_PORT, // physical display port id
                        POLICY_REASON_BRIGHT_INITIATED_REVERT, // policy reason
                        userActivity, // last user activity event
                        userActivityTime, // last user activity timestamp
                        dimDurationMs, // dim duration ms
                        DEFAULT_SCREEN_OFF_TIMEOUT_MS); // default Timeout Ms
    }

    private void advanceTime(long timeMs) {
        mTestClock.fastForward(timeMs);
    }
}
