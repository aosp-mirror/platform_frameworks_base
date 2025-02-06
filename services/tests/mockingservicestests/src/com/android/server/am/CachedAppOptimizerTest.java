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

import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;

import static com.android.server.am.ActivityManagerService.Injector;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManagerInternal;
import android.app.ActivityManagerInternal.FrozenProcessListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.MessageQueue;
import android.os.Process;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.text.TextUtils;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link CachedAppOptimizer}.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:CachedAppOptimizerTest
 */
@Presubmit
public final class CachedAppOptimizerTest {

    private ServiceThread mThread;

    @Mock
    private AppOpsService mAppOpsService;
    private CachedAppOptimizer mCachedAppOptimizerUnderTest;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private CountDownLatch mCountDown;
    private ActivityManagerService mAms;
    private Context mContext;
    private TestFreezer mFreezer;
    private CountDownLatch mFreezeCounter;
    private TestInjector mInjector;
    private TestProcessDependencies mProcessDependencies;

    @Mock
    private PackageManagerInternal mPackageManagerInt;

    // Control whether the freezer mock reports that freezing is enabled or not.
    private boolean mUseFreezer;

    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .addStaticMockFixtures(TestableDeviceConfig::new).build();

    @Before
    public void setUp() {
        System.loadLibrary("mockingservicestestjni");
        mHandlerThread = new HandlerThread("");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mThread = new ServiceThread("TestServiceThread", Process.THREAD_PRIORITY_DEFAULT,
                true /* allowIo */);
        mThread.start();
        mContext = InstrumentationRegistry.getInstrumentation().getContext();

        mUseFreezer = false;
        mFreezer = new TestFreezer();

        mInjector = new TestInjector(mContext);
        mAms = new ActivityManagerService(mInjector, mServiceThreadRule.getThread());
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        mProcessDependencies = new TestProcessDependencies();
        mCachedAppOptimizerUnderTest = new CachedAppOptimizer(mAms,
                new CachedAppOptimizer.PropertyChangedCallbackForTest() {
                    @Override
                    public void onPropertyChanged() {
                        if (mCountDown != null) {
                            mCountDown.countDown();
                        }
                    }
                }, mProcessDependencies);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);

        mCachedAppOptimizerUnderTest.init();
        mCachedAppOptimizerUnderTest.mCompactStatsManager.reinit();
    }

    @After
    public void tearDown() {
        mHandlerThread.quit();
        mThread.quit();
        mCountDown = null;
        mFreezeCounter = null;
    }

    private ProcessRecord makeProcessRecord(int pid, int uid, int packageUid, String processName,
            String packageName) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ProcessRecord app = new ProcessRecord(mAms, ai, processName, uid);
        app.setPid(pid);
        app.info.uid = packageUid;
        // Exact value does not mater, it can be any state for which compaction is allowed.
        app.mState.setSetProcState(PROCESS_STATE_BOUND_FOREGROUND_SERVICE);
        app.mState.setSetAdj(899);
        app.mState.setCurAdj(940);
        return app;
    }

    @Test
    public void init_setsDefaults() {
        synchronized (mCachedAppOptimizerUnderTest.mPhenotypeFlagLock) {
            assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isEqualTo(
                    CachedAppOptimizer.DEFAULT_USE_COMPACTION);
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
            assertThat(mCachedAppOptimizerUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                    CachedAppOptimizer.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);
            assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleMinOomAdj).isEqualTo(
                    CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ);
            assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleMaxOomAdj).isEqualTo(
                    CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ);
        }


        Set<Integer> expected = new HashSet<>();
        for (String s : TextUtils.split(
                CachedAppOptimizer.DEFAULT_COMPACT_PROC_STATE_THROTTLE, ",")) {
            expected.add(Integer.parseInt(s));
        }
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle)
                .containsExactlyElementsIn(expected);

        Assume.assumeTrue(mAms.isAppFreezerSupported());
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_FREEZER);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void init_withDeviceConfigSetsParameters() {
        // When the DeviceConfig already has a flag value stored (note this test will need to
        // change if the default value changes from false).
        assertThat(CachedAppOptimizer.DEFAULT_USE_COMPACTION).isTrue();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_COMPACTION, "true", false);
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
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_MIN_OOM_ADJ,
                Long.toString(
                        CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ + 10), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_MAX_OOM_ADJ,
                Long.toString(
                    CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ - 10), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_PROC_STATE_THROTTLE, "1,2,3", false);
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isFalse();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                CachedAppOptimizer.KEY_USE_FREEZER, CachedAppOptimizer.DEFAULT_USE_FREEZER
                        ? "false" : "true", false);

        // Then calling init will read and set that flag.
        mCachedAppOptimizerUnderTest.init();
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.mCachedAppOptimizerThread.isAlive()).isTrue();

        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mFullDeltaRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleMinOomAdj).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ + 10);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleMaxOomAdj).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ - 10);
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f);
        assertThat(mCachedAppOptimizerUnderTest.mFreezerStatsdSampleRate).isEqualTo(
                CachedAppOptimizer.DEFAULT_STATSD_SAMPLE_RATE + 0.1f);
        assertThat(mCachedAppOptimizerUnderTest.mFullAnonRssThrottleKb).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB + 1);
        assertThat(mCachedAppOptimizerUnderTest.mProcStateThrottle).containsExactly(1, 2, 3);

        Assume.assumeTrue(mAms.isAppFreezerSupported());
        if (mAms.isAppFreezerSupported()) {
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
        Assume.assumeTrue(mAms.isAppFreezerSupported());

        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isFalse();

        // The freezer DeviceConfig property is read at boot only
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                CachedAppOptimizer.KEY_USE_FREEZER, "true", false);
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isTrue();
        mCountDown = new CountDownLatch(1);

        // No notifications should get to the cached app optimizer.
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isFalse();

        // The flag value has to be set correctly.
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isTrue();
        // The cached app optimizer thread must be running.
        assertThat(mCachedAppOptimizerUnderTest.mCachedAppOptimizerThread.isAlive()).isTrue();

        // Set the flag the other way without rebooting. It shall not change.
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                CachedAppOptimizer.KEY_USE_FREEZER, "false", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isTrue();

        // Now, set the flag to false and restart the cached app optimizer
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                CachedAppOptimizer.KEY_USE_FREEZER, "false", false);
        mCachedAppOptimizerUnderTest.init();

        // The flag value has to be set correctly.
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isFalse();
    }

    @Test
    public void useCompaction_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isEqualTo(
                CachedAppOptimizer.DEFAULT_USE_COMPACTION);

        // When we push an invalid flag value...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_USE_COMPACTION, "foobar", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();

        // Invalid value is mapped to false
        assertThat(mCachedAppOptimizerUnderTest.useCompaction()).isEqualTo(false);
    }

    @Test
    public void useFreeze_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        Assume.assumeTrue(mAms.isAppFreezerSupported());
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isFalse();

        // When we push an invalid flag value...
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                CachedAppOptimizer.KEY_USE_FREEZER, "foobar", false);

        mCachedAppOptimizerUnderTest.init();

        // DeviceConfig treats invalid value as false
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isFalse();
    }

    @Test
    public void compactThrottle_listensToDeviceConfigChanges() throws InterruptedException {
        // When we override new reasonable throttle values after init...
        mCountDown = new CountDownLatch(8);
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
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_MIN_OOM_ADJ,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ + 1), false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                CachedAppOptimizer.KEY_COMPACT_THROTTLE_MAX_OOM_ADJ,
                Long.toString(CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ - 1), false);
        assertThat(mCountDown.await(7, TimeUnit.SECONDS)).isTrue();

        // Then those flags values are reflected in the compactor.
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_1 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleSomeFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_2 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullSome).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_3 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleFullFull).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_4 + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleMinOomAdj).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ + 1);
        assertThat(mCachedAppOptimizerUnderTest.mCompactThrottleMaxOomAdj).isEqualTo(
                CachedAppOptimizer.DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ - 1);
    }

    @Test
    public void compactThrottle_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
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
    }

    @Test
    public void statsdSampleRate_listensToDeviceConfigChanges() throws InterruptedException {
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

    @SuppressWarnings("GuardedBy")
    @Test
    public void processWithDeltaRSSTooSmall_notFullCompacted() throws Exception {
        // Initialize CachedAppOptimizer and set flags to (1) enable compaction, (2) set RSS
        // throttle to 12000.
        setFlag(CachedAppOptimizer.KEY_USE_COMPACTION, "true", true);
        setFlag(CachedAppOptimizer.KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB, "12000", false);
        initActivityManagerService();

        // Simulate RSS anon memory larger than throttle.
        long[] rssBefore1 =
                new long[]{/*totalRSS*/ 10000, /*fileRSS*/ 10000, /*anonRSS*/ 12000, /*swap*/
                        10000};
        long[] rssAfter1 =
                new long[]{/*totalRSS*/ 9000, /*fileRSS*/ 9000, /*anonRSS*/ 11000, /*swap*/9000};
        // Delta between rssAfter1 and rssBefore2 is below threshold (500).
        long[] rssBefore2 =
                new long[]{/*totalRSS*/ 9500, /*fileRSS*/ 9500, /*anonRSS*/ 11500, /*swap*/9500};
        long[] rssAfter2 =
                new long[]{/*totalRSS*/ 8000, /*fileRSS*/ 8000, /*anonRSS*/ 9000, /*swap*/8000};
        // Delta between rssAfter1 and rssBefore3 is above threshold (13000).
        long[] rssBefore3 =
                new long[]{/*totalRSS*/ 10000, /*fileRSS*/ 18000, /*anonRSS*/ 13000, /*swap*/ 7000};
        long[] rssAfter3 =
                new long[]{/*totalRSS*/ 10000, /*fileRSS*/ 11000, /*anonRSS*/ 10000, /*swap*/ 6000};
        long[] valuesAfter = {};
        // Process that passes properties.
        int pid = 1;
        ProcessRecord processRecord = makeProcessRecord(pid, 2, 3, "p1", "app1");

        // GIVEN we simulate RSS memory before above thresholds and it is the first time 'p1' is
        // compacted.
        mProcessDependencies.setRss(rssBefore1);
        mProcessDependencies.setRssAfterCompaction(rssAfter1); //
        // WHEN we try to run compaction
        mCachedAppOptimizerUnderTest.compactApp(processRecord,
                CachedAppOptimizer.CompactProfile.FULL, CachedAppOptimizer.CompactSource.APP,
                false);
        waitForHandler();
        // THEN process IS compacted.
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsManager.getLastCompactionStats(pid))
                .isNotNull();
        valuesAfter = mCachedAppOptimizerUnderTest.mCompactStatsManager.getLastCompactionStats(pid)
                .getRssAfterCompaction();
        assertThat(valuesAfter).isEqualTo(rssAfter1);

        // WHEN delta is below threshold (500).
        mProcessDependencies.setRss(rssBefore2);
        mProcessDependencies.setRssAfterCompaction(rssAfter2);
        // This is to avoid throttle of compacting too soon.
        processRecord.mOptRecord.setLastCompactTime(
                processRecord.mOptRecord.getLastCompactTime() - 10_000);
        // WHEN we try to run compaction.
        mCachedAppOptimizerUnderTest.compactApp(processRecord,
                CachedAppOptimizer.CompactProfile.FULL, CachedAppOptimizer.CompactSource.APP,
                false);
        waitForHandler();
        // THEN process IS NOT compacted - values after compaction for process 1 should remain the
        // same as from the last compaction.
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsManager.
                getLastCompactionStats(pid)).isNotNull();
        valuesAfter = mCachedAppOptimizerUnderTest.mCompactStatsManager.
                getLastCompactionStats(pid).getRssAfterCompaction();
        assertThat(valuesAfter).isEqualTo(rssAfter1);

        // WHEN delta is above threshold (13000).
        mProcessDependencies.setRss(rssBefore3);
        mProcessDependencies.setRssAfterCompaction(rssAfter3);
        // This is to avoid throttle of compacting too soon.
        processRecord.mOptRecord.setLastCompactTime(
                processRecord.mOptRecord.getLastCompactTime() - 10_000);
        // WHEN we try to run compaction
        mCachedAppOptimizerUnderTest.compactApp(processRecord,
                CachedAppOptimizer.CompactProfile.FULL, CachedAppOptimizer.CompactSource.APP,
                false);
        waitForHandler();
        // THEN process IS compacted - values after compaction for process 1 should be updated.
        assertThat(mCachedAppOptimizerUnderTest.
                mCompactStatsManager.getLastCompactionStats(pid)).isNotNull();
        valuesAfter = mCachedAppOptimizerUnderTest.
                mCompactStatsManager.getLastCompactionStats(pid).getRssAfterCompaction();
        assertThat(valuesAfter).isEqualTo(rssAfter3);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void processWithAnonRSSTooSmall_notFullCompacted() throws Exception {
        // Initialize CachedAppOptimizer and set flags to (1) enable compaction, (2) set RSS
        // throttle to 8000.

        setFlag(CachedAppOptimizer.KEY_USE_COMPACTION, "true", true);
        setFlag(CachedAppOptimizer.KEY_COMPACT_FULL_RSS_THROTTLE_KB, "8000", false);
        initActivityManagerService();

        // Simulate RSS anon memory larger than throttle.
        long[] rssBelowThreshold =
                new long[]{/*Total RSS*/ 10000, /*File RSS*/ 10000, /*Anon RSS*/ 7000, /*Swap*/
                        10000};
        long[] rssBelowThresholdAfter =
                new long[]{/*Total RSS*/ 9000, /*File RSS*/ 7000, /*Anon RSS*/ 4000, /*Swap*/
                        8000};
        long[] rssAboveThreshold =
                new long[]{/*Total RSS*/ 10000, /*File RSS*/ 10000, /*Anon RSS*/ 9000, /*Swap*/
                        10000};
        long[] rssAboveThresholdAfter =
                new long[]{/*Total RSS*/ 8000, /*File RSS*/ 9000, /*Anon RSS*/ 6000, /*Swap*/5000};
        // Process that passes properties.
        int pid = 1;
        ProcessRecord processRecord =
                makeProcessRecord(pid, 2, 3, "p1",
                        "app1");

        // GIVEN we simulate RSS memory before below threshold.
        mProcessDependencies.setRss(rssBelowThreshold);
        mProcessDependencies.setRssAfterCompaction(rssBelowThresholdAfter);
        // WHEN we try to run compaction
        mCachedAppOptimizerUnderTest.compactApp(processRecord,
                CachedAppOptimizer.CompactProfile.FULL, CachedAppOptimizer.CompactSource.APP,
                false);
        waitForHandler();
        // THEN process IS NOT compacted.
        assertThat(mCachedAppOptimizerUnderTest.
                mCompactStatsManager.getLastCompactionStats(pid)).isNull();

        // GIVEN we simulate RSS memory before above threshold.
        mProcessDependencies.setRss(rssAboveThreshold);
        mProcessDependencies.setRssAfterCompaction(rssAboveThresholdAfter);
        // WHEN we try to run compaction
        mCachedAppOptimizerUnderTest.compactApp(processRecord,
                CachedAppOptimizer.CompactProfile.FULL, CachedAppOptimizer.CompactSource.APP,
                false);
        waitForHandler();
        // THEN process IS compacted.
        assertThat(mCachedAppOptimizerUnderTest.
                mCompactStatsManager.getLastCompactionStats(pid)).isNotNull();
        long[] valuesAfter = mCachedAppOptimizerUnderTest.mCompactStatsManager.
                getLastCompactionStats(pid).getRssAfterCompaction();
        assertThat(valuesAfter).isEqualTo(rssAboveThresholdAfter);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void processWithOomAdjTooSmall_notFullCompacted() throws Exception {
        // Initialize CachedAppOptimizer and set flags to (1) enable compaction, (2) set Min and
        // Max OOM_Adj throttles.
        setFlag(CachedAppOptimizer.KEY_USE_COMPACTION, "true", true);
        setFlag(CachedAppOptimizer.KEY_COMPACT_THROTTLE_MIN_OOM_ADJ, Long.toString(920), true);
        setFlag(CachedAppOptimizer.KEY_COMPACT_THROTTLE_MAX_OOM_ADJ, Long.toString(950), true);
        initActivityManagerService();

        // Simulate RSS memory for which compaction should occur.
        long[] rssBefore =
                new long[]{/*Total RSS*/ 15000, /*File RSS*/ 15000, /*Anon RSS*/ 15000,
                        /*Swap*/ 10000};
        long[] rssAfter =
                new long[]{/*Total RSS*/ 8000, /*File RSS*/ 9000, /*Anon RSS*/ 6000, /*Swap*/
                        5000};
        // Process that passes properties.
        int pid = 1;
        ProcessRecord processRecord =
                makeProcessRecord(pid, 2, 3, "p1", "app1");
        mProcessDependencies.setRss(rssBefore);
        mProcessDependencies.setRssAfterCompaction(rssAfter);

        // When moving within cached state
        mCachedAppOptimizerUnderTest.onProcessFrozen(processRecord);
        waitForHandler();
        // THEN process IS compacted.
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsManager
                .getLastCompactionStats(pid)).isNotNull();
        long[] valuesAfter = mCachedAppOptimizerUnderTest.mCompactStatsManager
                .getLastCompactionStats(pid)
                .getRssAfterCompaction();
        assertThat(valuesAfter).isEqualTo(rssAfter);
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void process_forceCompacted() throws Exception {

        setFlag(CachedAppOptimizer.KEY_USE_COMPACTION, "true", true);
        setFlag(CachedAppOptimizer.KEY_COMPACT_THROTTLE_MIN_OOM_ADJ, Long.toString(920), true);
        setFlag(CachedAppOptimizer.KEY_COMPACT_THROTTLE_MAX_OOM_ADJ, Long.toString(950), true);
        initActivityManagerService();

        long[] rssBefore = new long[] {/*Total RSS*/ 15000, /*File RSS*/ 15000, /*Anon RSS*/ 15000,
                /*Swap*/ 10000};
        long[] rssAfter = new long[] {
                /*Total RSS*/ 8000, /*File RSS*/ 9000, /*Anon RSS*/ 6000, /*Swap*/ 5000};
        // Process that passes properties.
        int pid = 1;
        ProcessRecord processRecord = makeProcessRecord(pid, 2, 3, "p1", "app1");
        mProcessDependencies.setRss(rssBefore);
        mProcessDependencies.setRssAfterCompaction(rssAfter);

        // Use an OOM Adjust value that usually avoids compaction
        processRecord.mState.setSetAdj(100);
        processRecord.mState.setCurAdj(100);

        // Compact process full
        mCachedAppOptimizerUnderTest.compactApp(processRecord,
                CachedAppOptimizer.CompactProfile.FULL, CachedAppOptimizer.CompactSource.APP,
                false);
        waitForHandler();
        // the process is not compacted
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsManager.
                getLastCompactionStats(pid)).isNull();

        // Compact process some
        mCachedAppOptimizerUnderTest.compactApp(processRecord,
                CachedAppOptimizer.CompactProfile.SOME, CachedAppOptimizer.CompactSource.APP,
                false);
        waitForHandler();
        // the process is not compacted
        assertThat(mCachedAppOptimizerUnderTest.mCompactStatsManager
                .getLastCompactionStats(pid)).isNull();

        processRecord.mState.setSetAdj(100);
        processRecord.mState.setCurAdj(100);

        // We force a full compaction
        mCachedAppOptimizerUnderTest.compactApp(processRecord,
                CachedAppOptimizer.CompactProfile.FULL, CachedAppOptimizer.CompactSource.APP,
                true);
        waitForHandler();
        // then process is compacted.
        assertThat(mCachedAppOptimizerUnderTest
                .mCompactStatsManager.getLastCompactionStats(pid)).isNotNull();

        mCachedAppOptimizerUnderTest.mCompactStatsManager.getLastCompactionStats().clear();

        if (CachedAppOptimizer.ENABLE_SHARED_AND_CODE_COMPACT) {
            // We force a some compaction
            mCachedAppOptimizerUnderTest.compactApp(processRecord,
                    CachedAppOptimizer.CompactProfile.SOME, CachedAppOptimizer.CompactSource.APP,
                    true);
            waitForHandler();
            // then process is compacted.
            CachedAppOptimizer.CompactProfile executedCompactProfile =
                    processRecord.mOptRecord.getLastCompactProfile();
            assertThat(executedCompactProfile).isEqualTo(CachedAppOptimizer.CompactProfile.SOME);
        }
    }

    @Test
    public void testFreezerDelegator() throws Exception {
        mUseFreezer = true;
        mProcessDependencies.setRss(new long[] {
                    0 /*total_rss*/,
                    0 /*file*/,
                    0 /*anon*/,
                    0 /*swap*/,
                    0 /*shmem*/
                });

        // Force the system to use the freezer
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                CachedAppOptimizer.KEY_USE_FREEZER, "true", false);
        mCachedAppOptimizerUnderTest.init();
        initActivityManagerService();

        assertTrue(mAms.isAppFreezerSupported());
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isTrue();

        int pid = 10000;
        int uid = 2;
        int pkgUid = 3;
        ProcessRecord app = makeProcessRecord(pid, uid, pkgUid, "p1", "app1");

        mFreezeCounter = new CountDownLatch(1);
        mCachedAppOptimizerUnderTest.forceFreezeForTest(app, true);
        assertTrue(mFreezeCounter.await(5, TimeUnit.SECONDS));

        mFreezeCounter = new CountDownLatch(1);
        mCachedAppOptimizerUnderTest.forceFreezeForTest(app, false);
        assertTrue(mFreezeCounter.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testFrozenNotifier() throws Exception {
        mUseFreezer = true;
        mProcessDependencies.setRss(new long[] {
                    0 /*total_rss*/,
                    0 /*file*/,
                    0 /*anon*/,
                    0 /*swap*/,
                    0 /*shmem*/
                });

        // Force the system to use the freezer
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                CachedAppOptimizer.KEY_USE_FREEZER, "true", false);
        mCachedAppOptimizerUnderTest.init();
        initActivityManagerService();

        assertTrue(mAms.isAppFreezerSupported());
        assertThat(mCachedAppOptimizerUnderTest.useFreezer()).isTrue();

        int pid = 10000;
        int uid = 2;
        int pkgUid = 3;
        ProcessRecord app = makeProcessRecord(pid, uid, pkgUid, "p1", "app1");
        assertNotNull(app.mOptRecord);

        FrozenProcessListener listener = new FrozenProcessListener() {
                @Override
                public void onProcessFrozen(int pid) {
                    mFreezeCounter.countDown();
                }
                @Override
                public void onProcessUnfrozen(int pid) {
                    mFreezeCounter.countDown();
                }
            };
        mCachedAppOptimizerUnderTest.addFrozenProcessListener(app, directExecutor(), listener);

        mFreezeCounter = new CountDownLatch(2);
        mCachedAppOptimizerUnderTest.forceFreezeForTest(app, true);
        assertTrue(mFreezeCounter.await(5, TimeUnit.SECONDS));

        mFreezeCounter = new CountDownLatch(2);
        mCachedAppOptimizerUnderTest.forceFreezeForTest(app, false);
        assertTrue(mFreezeCounter.await(5, TimeUnit.SECONDS));
    }

    private void setFlag(String key, String value, boolean defaultValue) throws Exception {
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, key, value, defaultValue);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void waitForHandler() {
        Idle idle = new Idle();
        mCachedAppOptimizerUnderTest.mCompactionHandler.getLooper().getQueue().addIdleHandler(idle);
        mCachedAppOptimizerUnderTest.mCompactionHandler.post(() -> { });
        idle.waitForIdle();
    }

    private void initActivityManagerService() {
        mAms = new ActivityManagerService(mInjector, mServiceThreadRule.getThread());
        mAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        mAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        mAms.mAtmInternal = spy(mAms.mActivityTaskManager.getAtmInternal());
        mAms.mPackageManagerInt = mPackageManagerInt;
    }

    private static final class Idle implements MessageQueue.IdleHandler {
        private boolean mIdle;

        @Override
        public boolean queueIdle() {
            synchronized (this) {
                mIdle = true;
                notifyAll();
            }
            return false;
        }

        public synchronized void waitForIdle() {
            while (!mIdle) {
                try {
                    // Wait with a timeout of 10s.
                    wait(10000);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private class TestInjector extends Injector {

        TestInjector(Context context) {
            super(context);
        }

        @Override
        public AppOpsService getAppOpsService(File recentAccessesFile, File storageFile,
                Handler handler) {
            return mAppOpsService;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandler;
        }

        @Override
        public Freezer getFreezer() {
            return mFreezer;
        }
    }

    // Test implementation for ProcessDependencies.
    private static final class TestProcessDependencies
            implements CachedAppOptimizer.ProcessDependencies {
        private long[] mRss;
        private long[] mRssAfterCompaction;

        @Override
        public long[] getRss(int pid) {
            return mRss;
        }

        @Override
        public void performCompaction(CachedAppOptimizer.CompactProfile profile, int pid)
                throws IOException {
            mRss = mRssAfterCompaction;
        }

        public void setRss(long[] newValues) {
            mRss = newValues;
        }

        public void setRssAfterCompaction(long[] newValues) {
            mRssAfterCompaction = newValues;
        }
    }

    // Intercept Freezer calls.
    private class TestFreezer extends Freezer {
        @Override
        public void setProcessFrozen(int pid, int uid, boolean frozen) {
            mFreezeCounter.countDown();
        }

        @Override
        public int freezeBinder(int pid, boolean freeze, int timeoutMs) {
            return 0;
        }

        @Override
        public int getBinderFreezeInfo(int pid) {
            return 0;
        }

        @Override
        public boolean isFreezerSupported() {
            return mUseFreezer;
        }
    }
}
