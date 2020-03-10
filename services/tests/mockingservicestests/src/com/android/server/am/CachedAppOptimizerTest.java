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
import static com.android.server.am.CachedAppOptimizer.compactActionIntToString;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.text.TextUtils;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.ServiceThread;
import com.android.server.appop.AppOpsService;
import com.android.server.testables.TestableDeviceConfig;

import org.junit.After;
import org.junit.Assume;
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
 * Tests for {@link CachedAppOptimizer}.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:CachedAppOptimizerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public final class CachedAppOptimizerTest {

    private ServiceThread mThread;

    @Mock
    private AppOpsService mAppOpsService;
    private CachedAppOptimizer mCachedAppOptimizerUnderTest;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private CountDownLatch mCountDown;

    @Rule
    public TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    @Before
    public void setUp() {
        mHandlerThread = new HandlerThread("");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mThread = new ServiceThread("TestServiceThread", Process.THREAD_PRIORITY_DEFAULT,
                true /* allowIo */);
        mThread.start();

        ActivityManagerService ams = new ActivityManagerService(
                new TestInjector(InstrumentationRegistry.getInstrumentation().getContext()),
                mThread);
        mCachedAppOptimizerUnderTest = new CachedAppOptimizer(ams,
                new CachedAppOptimizer.PropertyChangedCallbackForTest() {
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
        mThread.quit();
        mCountDown = null;
    }

    @Test
    public void init_setsDefaults() {
        mCachedAppOptimizerUnderTest.init();
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_COMPACTION);
        assertThat(mCachedAppOptimizerUnderTest.mCompactActionSome).isEqualTo(
                compactActionIntToString(CachedAppOptimizer.DEFAULT_COMPACT_ACTION_1));
        assertThat(mCachedAppOptimizerUnderTest.mCompactActionFull).isEqualTo(
                compactActionIntToString(CachedAppOptimizer.DEFAULT_COMPACT_ACTION_2));
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE);
        assertThat(mCachedAppOptimizerUnderTest.mFreezerStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE);
        assertThat(mCachedAppOptimizerUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6);
        assertThat(mCachedAppOptimizerUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);
        assertThat(mCachedAppOptimizerUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_FREEZER);

        Set<Integer> expected = new HashSet<>();
        for (String s : TextUtils.split(
                CachedAppOptimizer.DEFAULT_COMPACT_PROC_STATE_THROTTLE, ",")) {
            expected.add(Integer.parseInt(s));
        }
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle)
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void init_withDeviceConfigSetsParameters() {
        // When the DeviceConfig already has a flag value stored (note this test will need to
        // change if the default value changes from false).
        assertThat(CachedAppOptimizer.DEFAULT_USE_COMPACTION).isFalse();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_COMPACTION, "true", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_ACTION_1,
                Integer.toString((CachedAppOptimizer.DEFAULT_COMPACT_ACTION_1 + 1 % 4) + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_ACTION_2,
                Integer.toString((CachedAppOptimizer.DEFAULT_COMPACT_ACTION_2 + 1 % 4) + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_1,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_2,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_3,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_4,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_5,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_6,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_FREEZER_STATSD_SAMPLE_RATE,
                Float.toString(CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_FULL_RSS_THROTTLE_KB,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB,
                Long.toString(
                        CachedAppOptimizer.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, "1,2,3", false);
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_FREEZER);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_FREEZER, CachedAppOptimizer.DEFAULT_USE_FREEZER
                ?  "false" : "true" , false);

        // Then calling init will read and set that flag.
        mCachedAppOptimizerUnderTest.init();
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCachedAppOptimizerThread.isAlive()).isTrue();

        assertThat(mCachedAppOptimizerUnderTest.mCompactActionSome).isEqualTo(
                compactActionIntToString(
                        (CachedAppOptimizer.DEFAULT_COMPACT_ACTION_1 + 1 % 4) + 1));
        assertThat(mCachedAppOptimizerUnderTest.mCompactActionFull).isEqualTo(
                compactActionIntToString(
                        (CachedAppOptimizer.DEFAULT_COMPACT_ACTION_2 + 1 % 4) + 1));
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f);
        assertThat(mCachedAppOptimizerUnderTest.mFreezerStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1);
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle).containsExactly(1, 2, 3);

        if (mCachedAppOptimizerUnderTest.isFreezerSupported()) {
            if (CachedAppOptimizer.DEFAULT_USE_FREEZER) {
                assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isFalse();
            } else {
                assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isTrue();
            }
        }
    }

    @Test
    public void useCompaction_listensToDeviceConfigChanges() throws InterruptedException {
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_COMPACTION);
        // When we call init and change some the flag value...
        mCachedAppOptimizerUnderTest.init();
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_COMPACTION, "true", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that new flag value is updated in the implementation.
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCachedAppOptimizerThread.isAlive()).isTrue();

        // And again, setting the flag the other way.
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_COMPACTION, "false", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isFalse();
    }

    @Test
    public void useFreeze_doesNotListenToDeviceConfigChanges() throws InterruptedException {
        Assume.assumeTrue(mCachedAppOptimizerUnderTest.isFreezerSupported());

        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_FREEZER);

        // The freezer DeviceConfig property is read at boot only
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_FREEZER, "true", false);
        mCachedAppOptimizerUnderTest.init();
        mCountDown = new CountDownLatch(1);

        // No notifications should get to the cached app optimizer.
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isFalse();

        // The flag value has to be set correctly.
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isTrue();
        // The cached app optimizer thread must be running.
        assertThat(mCachedAppOptimizerUnderTest.mCachedAppOptimizerThread.isAlive()).isTrue();

        // Set the flag the other way without rebooting. It shall not change.
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_FREEZER, "false", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isTrue();


        // Now, set the flag to false and restart the cached app optimizer
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_FREEZER, "false", false);
        mCachedAppOptimizerUnderTest.init();

        // The flag value has to be set correctly.
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isFalse();
    }

    @Test
    public void useCompaction_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_COMPACTION);
        mCachedAppOptimizerUnderTest.init();

        // When we push an invalid flag value...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_COMPACTION, "foobar", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then we set the default.
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_COMPACTION);
    }

    @Test
    public void useFreeze_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_FREEZER);

        // When we push an invalid flag value...
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_FREEZER, "foobar", false);

        mCachedAppOptimizerUnderTest.init();

        // Then we set the default.
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_FREEZER);
    }

    @Test
    public void compactAction_listensToDeviceConfigChanges() throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override new values for the compaction action with reasonable values...

        // There are four possible values for compactAction[Some|Full].
        for (int i = 1; i < 5; i++) {
            mCountDown = new CountDownLatch(2);
            int expectedSome = (CachedAppOptimizer.DEFAULT_COMPACT_ACTION_1 + i) % 4 + 1;
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    CachedAppOptimizer.KEY_COMPACT_ACTION_1, Integer.toString(expectedSome), false);
            int expectedFull = (CachedAppOptimizer.DEFAULT_COMPACT_ACTION_2 + i) % 4 + 1;
            DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    CachedAppOptimizer.KEY_COMPACT_ACTION_2, Integer.toString(expectedFull), false);
            assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

            // Then the updates are reflected in the flags.
            assertThat(mCachedAppOptimizerUnderTest.mCompactActionSome).isEqualTo(
                    compactActionIntToString(expectedSome));
            assertThat(mCachedAppOptimizerUnderTest.mCompactActionFull).isEqualTo(
                    compactActionIntToString(expectedFull));
        }
    }

    @Test
    public void compactAction_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override new values for the compaction action with bad values ...
        mCountDown = new CountDownLatch(2);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_ACTION_1, "foo", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_ACTION_2, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then the default values are reflected in the flag
        assertThat(mCachedAppOptimizerUnderTest.mCompactActionSome).isEqualTo(
                compactActionIntToString(CachedAppOptimizer.DEFAULT_COMPACT_ACTION_1));
        assertThat(mCachedAppOptimizerUnderTest.mCompactActionFull).isEqualTo(
                compactActionIntToString(CachedAppOptimizer.DEFAULT_COMPACT_ACTION_2));

        mCountDown = new CountDownLatch(2);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_ACTION_1, "", false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_ACTION_2, "", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(mCachedAppOptimizerUnderTest.mCompactActionSome).isEqualTo(
                compactActionIntToString(CachedAppOptimizer.DEFAULT_COMPACT_ACTION_1));
        assertThat(mCachedAppOptimizerUnderTest.mCompactActionFull).isEqualTo(
                compactActionIntToString(CachedAppOptimizer.DEFAULT_COMPACT_ACTION_2));
    }

    @Test
    public void compactThrottle_listensToDeviceConfigChanges() throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override new reasonable throttle values after init...
        mCountDown = new CountDownLatch(6);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_1,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_2,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_3,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_4,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_5,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_6,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6 + 1), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then those flags values are reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6 + 1);
    }

    @Test
    public void compactThrottle_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When one of the throttles is overridden with a bad value...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_1, "foo", false);
        // Then all the throttles have the defaults set.
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6);

        // Repeat for each of the throttle keys.
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_2, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_3, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_4, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_5, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_6, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleBFGS).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_5);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottlePersistent).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_6);
    }

    @Test
    public void statsdSampleRate_listensToDeviceConfigChanges() throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override mCompactStatsdSampleRate with a reasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f);

        // When we override mFreezerStatsdSampleRate with a reasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_FREEZER_STATSD_SAMPLE_RATE,
                Float.toString(CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mFreezerStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f);
    }

    @Test
    public void statsdSampleRate_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override mCompactStatsdSampleRate with an unreasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_STATSD_SAMPLE_RATE, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE);

        // When we override mFreezerStatsdSampleRate with an unreasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_FREEZER_STATSD_SAMPLE_RATE, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the freezer.
        assertThat(mCachedAppOptimizerUnderTest.mFreezerStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE);
    }

    @Test
    public void statsdSampleRate_listensToDeviceConfigChangesOutOfRangeValues()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override mCompactStatsdSampleRate with an value outside of [0..1]...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(-1.0f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then the values is capped in the range.
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsdSampleRate).isEqualTo(0.0f);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(1.01f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then the values is capped in the range.
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsdSampleRate).isEqualTo(1.0f);

        // When we override mFreezerStatsdSampleRate with an value outside of [0..1]...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_FREEZER_STATSD_SAMPLE_RATE,
                Float.toString(-1.0f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then the values is capped in the range.
        assertThat(mCachedAppOptimizerUnderTest.mFreezerStatsdSampleRate).isEqualTo(0.0f);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_FREEZER_STATSD_SAMPLE_RATE,
                Float.toString(1.01f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then the values is capped in the range.
        assertThat(mCachedAppOptimizerUnderTest.mFreezerStatsdSampleRate).isEqualTo(1.0f);
    }

    @Test
    public void fullCompactionRssThrottleKb_listensToDeviceConfigChanges()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override mStatsdSampleRate with a reasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_FULL_RSS_THROTTLE_KB,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1);
    }

    @Test
    public void fullCompactionRssThrottleKb_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override mStatsdSampleRate with an unreasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_FULL_RSS_THROTTLE_KB, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_FULL_RSS_THROTTLE_KB, "-100", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);
    }

    @Test
    public void fullCompactionDeltaRssThrottleKb_listensToDeviceConfigChanges()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override mStatsdSampleRate with a reasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB,
                Long.toString(
                        CachedAppOptimizer.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + 1), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + 1);
    }

    @Test
    public void fullCompactionDeltaRssThrottleKb_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        // When we override mStatsdSampleRate with an unreasonable value ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB, "-100", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Then that override is reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);
    }

    @Test
    public void procStateThrottle_listensToDeviceConfigChanges()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, "1,2,3", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle).containsExactly(1, 2, 3);

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, "", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle).isEmpty();
    }

    @Test
    public void procStateThrottle_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCachedAppOptimizerUnderTest.init();

        Set<Integer> expected = new HashSet<>();
        for (String s : TextUtils.split(
                CachedAppOptimizer.DEFAULT_COMPACT_PROC_STATE_THROTTLE, ",")) {
            expected.add(Integer.parseInt(s));
        }

        // Not numbers
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle)
                .containsExactlyElementsIn(expected);
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, "1,foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle)
                .containsExactlyElementsIn(expected);

        // Empty splits
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, ",", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle)
                .containsExactlyElementsIn(expected);
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, ",,3", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle)
                .containsExactlyElementsIn(expected);
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, "1,,3", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle)
                .containsExactlyElementsIn(expected);
    }

    private class TestInjector extends Injector {

        TestInjector(Context context) {
            super(context);
        }

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
