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

import static android.provider.DeviceConfig.ActivityManager.KEY_COMPACT_ACTION_1;
import static android.provider.DeviceConfig.ActivityManager.KEY_COMPACT_ACTION_2;
import static android.provider.DeviceConfig.ActivityManager.KEY_COMPACT_STATSD_SAMPLE_RATE;
import static android.provider.DeviceConfig.ActivityManager.KEY_COMPACT_THROTTLE_1;
import static android.provider.DeviceConfig.ActivityManager.KEY_COMPACT_THROTTLE_2;
import static android.provider.DeviceConfig.ActivityManager.KEY_COMPACT_THROTTLE_3;
import static android.provider.DeviceConfig.ActivityManager.KEY_COMPACT_THROTTLE_4;
import static android.provider.DeviceConfig.ActivityManager.KEY_USE_COMPACTION;

import static com.android.server.am.ActivityManagerService.Injector;
import static com.android.server.am.AppCompactor.compactActionIntToString;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import android.os.Handler;
import android.os.HandlerThread;
import android.provider.DeviceConfig;
import android.support.test.uiautomator.UiDevice;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.appop.AppOpsService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link AppCompactor}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:AppCompactorTest
 */
@RunWith(AndroidJUnit4.class)
public final class AppCompactorTest {

    private static final String CLEAR_DEVICE_CONFIG_KEY_CMD =
            "device_config delete activity_manager";

    @Mock private AppOpsService mAppOpsService;
    private AppCompactor mCompactorUnderTest;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private CountDownLatch mCountDown;

    private static void clearDeviceConfig() throws IOException  {
        UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + KEY_USE_COMPACTION);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + KEY_COMPACT_ACTION_1);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + KEY_COMPACT_ACTION_2);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + KEY_COMPACT_THROTTLE_1);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + KEY_COMPACT_THROTTLE_2);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + KEY_COMPACT_THROTTLE_3);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + KEY_COMPACT_THROTTLE_4);
        uiDevice.executeShellCommand(
                CLEAR_DEVICE_CONFIG_KEY_CMD + " " + KEY_COMPACT_STATSD_SAMPLE_RATE);
    }

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        clearDeviceConfig();
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
    public void tearDown() throws IOException {
        mHandlerThread.quit();
        mCountDown = null;
        clearDeviceConfig();
    }

    @Test
    public void init_setsDefaults() {
        mCompactorUnderTest.init();
        assertThat(mCompactorUnderTest.useCompaction(),
                is(mCompactorUnderTest.DEFAULT_USE_COMPACTION));
        assertThat(mCompactorUnderTest.mCompactActionSome, is(
                compactActionIntToString(mCompactorUnderTest.DEFAULT_COMPACT_ACTION_1)));
        assertThat(mCompactorUnderTest.mCompactActionFull, is(
                compactActionIntToString(mCompactorUnderTest.DEFAULT_COMPACT_ACTION_2)));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_1));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_2));
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_3));
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_4));
        assertThat(mCompactorUnderTest.mStatsdSampleRate,
                is(AppCompactor.DEFAULT_STATSD_SAMPLE_RATE));
    }

    @Test
    public void init_withDeviceConfigSetsParameters() {
        // When the DeviceConfig already has a flag value stored (note this test will need to
        // change if the default value changes from false).
        assertThat(mCompactorUnderTest.DEFAULT_USE_COMPACTION, is(false));
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_USE_COMPACTION, "true", false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_ACTION_1,
                Integer.toString((AppCompactor.DEFAULT_COMPACT_ACTION_1 + 1 % 4) + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_ACTION_2,
                Integer.toString((AppCompactor.DEFAULT_COMPACT_ACTION_2 + 1 % 4) + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_1,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_1 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_2,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_2 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_3,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_3 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_4,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_4 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(AppCompactor.DEFAULT_STATSD_SAMPLE_RATE + 0.1f), false);

        // Then calling init will read and set that flag.
        mCompactorUnderTest.init();
        assertThat(mCompactorUnderTest.useCompaction(), is(true));
        assertThat(mCompactorUnderTest.mCompactionThread.isAlive(), is(true));

        assertThat(mCompactorUnderTest.mCompactActionSome,
                is(compactActionIntToString((AppCompactor.DEFAULT_COMPACT_ACTION_1 + 1 % 4) + 1)));
        assertThat(mCompactorUnderTest.mCompactActionFull,
                is(compactActionIntToString((AppCompactor.DEFAULT_COMPACT_ACTION_2 + 1 % 4) + 1)));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_1 + 1));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_2 + 1));
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_3 + 1));
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_4 + 1));
        assertThat(mCompactorUnderTest.mStatsdSampleRate,
                is(AppCompactor.DEFAULT_STATSD_SAMPLE_RATE + 0.1f));
    }

    @Test
    public void useCompaction_listensToDeviceConfigChanges() throws InterruptedException {
        assertThat(mCompactorUnderTest.useCompaction(),
                is(mCompactorUnderTest.DEFAULT_USE_COMPACTION));
        // When we call init and change some the flag value...
        mCompactorUnderTest.init();
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_USE_COMPACTION, "true", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        // Then that new flag value is updated in the implementation.
        assertThat(mCompactorUnderTest.useCompaction(), is(true));
        assertThat(mCompactorUnderTest.mCompactionThread.isAlive(), is(true));

        // And again, setting the flag the other way.
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_USE_COMPACTION, "false", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));
        assertThat(mCompactorUnderTest.useCompaction(), is(false));
    }

    @Test
    public void useCompaction_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        assertThat(mCompactorUnderTest.useCompaction(),
                is(mCompactorUnderTest.DEFAULT_USE_COMPACTION));
        mCompactorUnderTest.init();

        // When we push an invalid flag value...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_USE_COMPACTION, "foobar", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        // Then we set the default.
        assertThat(mCompactorUnderTest.useCompaction(), is(AppCompactor.DEFAULT_USE_COMPACTION));
    }

    @Test
    public void compactAction_listensToDeviceConfigChanges() throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override new values for the compaction action with reasonable values...

        // There are four possible values for compactAction[Some|Full].
        for (int i = 1; i < 5; i++) {
            mCountDown = new CountDownLatch(2);
            int expectedSome = (mCompactorUnderTest.DEFAULT_COMPACT_ACTION_1 + i) % 4 + 1;
            DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                    KEY_COMPACT_ACTION_1, Integer.toString(expectedSome), false);
            int expectedFull = (mCompactorUnderTest.DEFAULT_COMPACT_ACTION_2 + i) % 4 + 1;
            DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                    KEY_COMPACT_ACTION_2, Integer.toString(expectedFull), false);
            assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

            // Then the updates are reflected in the flags.
            assertThat(mCompactorUnderTest.mCompactActionSome,
                    is(compactActionIntToString(expectedSome)));
            assertThat(mCompactorUnderTest.mCompactActionFull,
                    is(compactActionIntToString(expectedFull)));
        }
    }

    @Test
    public void compactAction_listensToDeviceConfigChangesBadValues() throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override new values for the compaction action with bad values ...
        mCountDown = new CountDownLatch(2);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_ACTION_1, "foo", false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_ACTION_2, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        // Then the default values are reflected in the flag
        assertThat(mCompactorUnderTest.mCompactActionSome,
                is(compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_1)));
        assertThat(mCompactorUnderTest.mCompactActionFull,
                is(compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_2)));

        mCountDown = new CountDownLatch(2);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_ACTION_1, "", false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_ACTION_2, "", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        assertThat(mCompactorUnderTest.mCompactActionSome,
                is(compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_1)));
        assertThat(mCompactorUnderTest.mCompactActionFull,
                is(compactActionIntToString(AppCompactor.DEFAULT_COMPACT_ACTION_2)));
    }

    @Test
    public void compactThrottle_listensToDeviceConfigChanges() throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override new reasonable throttle values after init...
        mCountDown = new CountDownLatch(4);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_1,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_1 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_2,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_2 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_3,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_3 + 1), false);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_4,
                Long.toString(AppCompactor.DEFAULT_COMPACT_THROTTLE_4 + 1), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        // Then those flags values are reflected in the compactor.
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_1 + 1));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_2 + 1));
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_3 + 1));
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_4 + 1));
    }

    @Test
    public void compactThrottle_listensToDeviceConfigChangesBadValues()
            throws IOException, InterruptedException {
        mCompactorUnderTest.init();

        // When one of the throttles is overridden with a bad value...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_1, "foo", false);
        // Then all the throttles have the defaults set.
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_1));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_2));
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_3));
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_4));
        clearDeviceConfig();

        // Repeat for each of the throttle keys.
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_2, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_1));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_2));
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_3));
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_4));
        clearDeviceConfig();

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_3, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_1));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_2));
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_3));
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_4));
        clearDeviceConfig();

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_THROTTLE_4, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_1));
        assertThat(mCompactorUnderTest.mCompactThrottleSomeFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_2));
        assertThat(mCompactorUnderTest.mCompactThrottleFullSome,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_3));
        assertThat(mCompactorUnderTest.mCompactThrottleFullFull,
                is(AppCompactor.DEFAULT_COMPACT_THROTTLE_4));
    }

    @Test
    public void statsdSampleRate_listensToDeviceConfigChanges() throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with a reasonable values ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(AppCompactor.DEFAULT_STATSD_SAMPLE_RATE + 0.1f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mStatsdSampleRate,
                is(AppCompactor.DEFAULT_STATSD_SAMPLE_RATE + 0.1f));
    }

    @Test
    public void statsdSanokeRate_listensToDeviceConfigChangesBadValues()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with a reasonable values ...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_STATSD_SAMPLE_RATE, "foo", false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        // Then that override is reflected in the compactor.
        assertThat(mCompactorUnderTest.mStatsdSampleRate,
                is(AppCompactor.DEFAULT_STATSD_SAMPLE_RATE));
    }

    @Test
    public void statsdSanokeRate_listensToDeviceConfigChangesOutOfRangeValues()
            throws InterruptedException {
        mCompactorUnderTest.init();

        // When we override mStatsdSampleRate with an value outside of [0..1]...
        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(-1.0f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        // Then the values is capped in the range.
        assertThat(mCompactorUnderTest.mStatsdSampleRate, is(0.0f));

        mCountDown = new CountDownLatch(1);
        DeviceConfig.setProperty(DeviceConfig.ActivityManager.NAMESPACE,
                KEY_COMPACT_STATSD_SAMPLE_RATE,
                Float.toString(1.01f), false);
        assertThat(mCountDown.await(5, TimeUnit.SECONDS), is(true));

        // Then the values is capped in the range.
        assertThat(mCompactorUnderTest.mStatsdSampleRate, is(1.0f));
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
