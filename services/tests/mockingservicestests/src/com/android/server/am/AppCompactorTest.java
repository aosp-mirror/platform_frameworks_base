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

package com.android.server.am;

import static com.android.server.am.ActivityManagerService.Injector;
import static com.android.server.am.AppCompactor.compactActionIntToString;

import static com.google.common.truth.Truth.assertThat;

import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.text.TextUtils;

import com.android.server.appop.AppOpsService;
import com.android.server.testables.TestableDeviceConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link AppCompactor}.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:AppCompactorTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public final class AppCompactorTest {

    @Mock
    private AppOpsService mAppOpsService;
    private AppCompactor mCompactorUnderTest;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private CountDownLatch mCountDown;

    @Rule
    public TestableDeviceConfig mDeviceConfig = new TestableDeviceConfig();

    @Before
    public void setUp() {
        mHandlerThread = new HandlerThread("");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        ActivityManagerService ams = new ActivityManagerService(new TestInjector());
        mCompactorUnderTest = new AppCompactor(ams,
                new AppCompactor.PropertyChangedCallbackForTest() {
                    @Override
                    public void onPropertyChanged() {
                        if (mCountDown != null) {
                            mCountDown.countDown();
                        }
                    }
                });
    }

    @After
    public void tearDown() {
        mHandlerThread.quit();
        mCountDown = null;
    }

    @Test
    public void init_setsDefaults() {
        mCompactorUnderTest.init();
        assertThat(mCompactorUnderTest.useCompaction()).isEqualTo(
                AppCompactor.DEFAULT_USE_COMPACTION);
        assertThat(mCompactorUnderTest.mCompactActionSome).isEqualTo(
                compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_1));
        assertThat(mCompactorUnderTest.mCompactActionFull).isEqualTo(
                compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_2));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCompactorUnderTest.mStatsdSampleRate).isEqualTo(
                AppCompactor.DEFAULT_STATSD_SAMPLE_RATE);
        assertThat(mCompactorUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6);
        assertThat(mCompactorUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);
        assertThat(mCompactorUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);

        Set<Integer> expected = new HashSet<>();
        for (String s : TextUtils.split(AppCompactor.DEFAULT_COMPACT_PROC_STATE_THROTTLE, ",")) {
            expected.add(Integer.parseInt(s));
        }
        assertThat(mCompactorUnderTest.mProcStateThrottle).containsExactlyElementsIn(expected);
    }

    @Test
    public void init_withDeviceConfigSetsParameters() {
        // When the DeviceConfig already has a flag value stored (note this test will need to
        // change if the default value changes from false).
        assertThat(AppCompactor.DEFAULT_USE_COMPACTION).isFalse();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_USE_COMPACTION, "true", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_ACTION_1,
                Integer.toString((AppCompactor.DEFAULT_COMPACT_ACTION_1 + 1 % 4) + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_ACTION_2,
                Integer.toString((AppCompactor.DEFAULT_COMPACT_ACTION_2 + 1 % 4) + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_1,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_1 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_2,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_2 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_3,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_3 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_4,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_4 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_5,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_5 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_6,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_6 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(AppCompactor.DEFAULT_STATSD_SAMPLE_RATE + 0.1f), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_FULL_RSS_THROTTLE_KB,
                Long.toString(AppCompactor.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB,
                Long.toString(AppCompactor.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE, "1,2,3", false);

        // Then calling init will read and set that flag.
        mCompactorUnderTest.init();
        assertThat(mCompactorUnderTest.useCompaction()).isTrue();
        assertThat(mCompactorUnderTest.mCompactionThread.isAlive()).isTrue();

        assertThat(mCompactorUnderTest.mCompactActionSome).isEqualTo(
                compactActionIntToString((AppCompactor.DEFAULT_COMPACT_ACTION_1 + 1 % 4) + 1));
        assertThat(mCompactorUnderTest.mCompactActionFull).isEqualTo(
                compactActionIntToString((AppCompactor.DEFAULT_COMPACT_ACTION_2 + 1 % 4) + 1));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6 + 1);
        assertThat(mCompactorUnderTest.mStatsdSampleRate).isEqualTo(
                AppCompactor.DEFAULT_STATSD_SAMPLE_RATE + 0.1f);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6 + 1);
        assertThat(mCompactorUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1);
        assertThat(mCompactorUnderTest.mProcStateThrottle).containsExactly(1, 2, 3);
    }

    @Test
    public void useCompaction_listensToDeviceConfigChanges() throws InterruptedException {
        assertThat(mCompactorUnderTest.useCompaction()).isEqualTo(
                AppCompactor.DEFAULT_USE_COMPACTION);
        // When we call init and change some the flag value...
        mCompactorUnderTest.init();
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_USE_COMPACTION, "true", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that new flag value is updated in the implementation.
        assertThat(mCompactorUnderTest.useCompaction()).isTrue();
        assertThat(mCompactorUnderTest.mCompactionThread.isAlive()).isTrue();

        // And again, setting the flag the other way.
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_USE_COMPACTION, "false", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.useCompaction()).isFalse();
    }

    @Test
    public void useCompaction_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        assertThat(mCompactorUnderTest.useCompaction()).isEqualTo(
                AppCompactor.DEFAULT_USE_COMPACTION);
        mCompactorUnderTest.init();

        // When we push an invalid flag value...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_USE_COMPACTION, "foobar", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then we set the default.
        assertThat(mCompactorUnderTest.useCompaction()).isEqualTo(
                AppCompactor.DEFAULT_USE_COMPACTION);
    }

    @Test
    public void compactAction_listensToDeviceConfigChanges() throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override new values for the compaction action with reasonable values...

        // There are four possible values for compactAction[Some|Full].
        for (int i = 1; i < 5; i++) {
            mCountDown = new CountDownLatch(2);
            int expectedSome = (AppCompactor.DEFAULT_COMPACT_ACTION_1 + i) % 4 + 1;
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    AppCompactor.KEY_COMPACT_ACTION_1, Integer.toString(expectedSome), false);
            int expectedFull = (AppCompactor.DEFAULT_COMPACT_ACTION_2 + i) % 4 + 1;
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    AppCompactor.KEY_COMPACT_ACTION_2, Integer.toString(expectedFull), false);
            assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

            // Then the updates are reflected in the flags.
            assertThat(mCompactorUnderTest.mCompactActionSome).isEqualTo(
                    compactActionIntToString(expectedSome));
            assertThat(mCompactorUnderTest.mCompactActionFull).isEqualTo(
                    compactActionIntToString(expectedFull));
        }
    }

    @Test
    public void compactAction_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override new values for the compaction action with bad values ...
        mCountDown = new CountDownLatch(2);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_ACTION_1, "foo", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_ACTION_2, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then the default values are reflected in the flag
        assertThat(mCompactorUnderTest.mCompactActionSome).isEqualTo(
                compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_1));
        assertThat(mCompactorUnderTest.mCompactActionFull).isEqualTo(
                compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_2));

        mCountDown = new CountDownLatch(2);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_ACTION_1, "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_ACTION_2, "", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(mCompactorUnderTest.mCompactActionSome).isEqualTo(
                compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_1));
        assertThat(mCompactorUnderTest.mCompactActionFull).isEqualTo(
                compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_2));
    }

    @Test
    public void compactThrottle_listensToDeviceConfigChanges() throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override new reasonable throttle values after init...
        mCountDown = new CountDownLatch(6);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_1,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_1 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_2,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_2 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_3,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_3 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_4,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_4 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_5,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_5 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_6,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_6 + 1), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then those flags values are reflected in the compactor.
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5 + 1);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6 + 1);
    }

    @Test
    public void compactThrottle_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When one of the throttles is overridden with a bad value...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_1, "foo", false);
        // Then all the throttles have the defaults set.
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6);

        // Repeat for each of the throttle keys.
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_2, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_3, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_4, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_5, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_THROTTLE_6, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCompactorUnderTest.mCompactThrottleBFGS).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCompactorUnderTest.mCompactThrottlePersistent).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_THROTTLE_6);
    }

    @Test
    public void statsdSampleRate_listensToDeviceConfigChanges() throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with a reasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(AppCompactor.DEFAULT_STATSD_SAMPLE_RATE + 0.1f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mStatsdSampleRate).isEqualTo(
                AppCompactor.DEFAULT_STATSD_SAMPLE_RATE + 0.1f);
    }

    @Test
    public void statsdSampleRate_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with an unreasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_STATSD_SAMPLE_RATE, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mStatsdSampleRate).isEqualTo(
                AppCompactor.DEFAULT_STATSD_SAMPLE_RATE);
    }

    @Test
    public void statsdSampleRate_listensToDeviceConfigChangesOutOfRangeValues()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with an value outside of [0..1]...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(-1.0f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then the values is capped in the range.
        assertThat(mCompactorUnderTest.mStatsdSampleRate).isEqualTo(0.0f);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(1.01f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then the values is capped in the range.
        assertThat(mCompactorUnderTest.mStatsdSampleRate).isEqualTo(1.0f);
    }

    @Test
    public void fullCompactionRssThrottleKb_listensToDeviceConfigChanges()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with a reasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_FULL_RSS_THROTTLE_KB,
                Long.toString(AppCompactor.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1);
    }

    @Test
    public void fullCompactionRssThrottleKb_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with an unreasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_FULL_RSS_THROTTLE_KB, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_FULL_RSS_THROTTLE_KB, "-100", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);
    }

    @Test
    public void fullCompactionDeltaRssThrottleKb_listensToDeviceConfigChanges()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with a reasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB,
                Long.toString(AppCompactor.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + 1), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + 1);
    }

    @Test
    public void fullCompactionDeltaRssThrottleKb_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with an unreasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB, "-100", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                AppCompactor.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);
    }

    @Test
    public void procStateThrottle_listensToDeviceConfigChanges()
            throws InterruptedException {
        mCompactorUnderTest.init();
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE, "1,2,3", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mProcStateThrottle).containsExactly(1, 2, 3);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE, "", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mProcStateThrottle).isEmpty();
    }

    @Test
    public void procStateThrottle_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCompactorUnderTest.init();

        Set<Integer> expected = new HashSet<>();
        for (String s : TextUtils.split(AppCompactor.DEFAULT_COMPACT_PROC_STATE_THROTTLE, ",")) {
            expected.add(Integer.parseInt(s));
        }

        // Not numbers
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mProcStateThrottle).containsExactlyElementsIn(expected);
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE, "1,foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mProcStateThrottle).containsExactlyElementsIn(expected);

        // Empty splits
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE, ",", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mProcStateThrottle).containsExactlyElementsIn(expected);
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE, ",,3", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mProcStateThrottle).containsExactlyElementsIn(expected);
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                AppCompactor.KEY_COMPACT_PROC_STATE_THROTTLE, "1,,3", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCompactorUnderTest.mProcStateThrottle).containsExactlyElementsIn(expected);
    }

    private class TestInjector extends Injector {
        @Override
        public AppOpsService getAppOpsService(File file, Handler handler) {
            return mAppOpsService;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandler;
        }
    }
}
