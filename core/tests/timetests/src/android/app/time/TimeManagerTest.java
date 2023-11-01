/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app.time;

import static android.app.time.Capabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;
import static android.app.time.cts.shell.DeviceConfigKeys.NAMESPACE_SYSTEM_TIME;
import static android.app.time.cts.shell.DeviceConfigKeys.TimeZoneDetector.KEY_TIME_ZONE_DETECTOR_AUTO_DETECTION_ENABLED_DEFAULT;
import static android.app.time.cts.shell.DeviceConfigShellHelper.SYNC_DISABLED_MODE_UNTIL_REBOOT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.app.time.cts.shell.DeviceConfigShellHelper;
import android.app.time.cts.shell.DeviceShellCommandExecutor;
import android.app.time.cts.shell.TimeZoneDetectorShellHelper;
import android.app.time.cts.shell.device.InstrumentationShellCommandExecutor;
import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for non-SDK methods / internal behavior related to {@link TimeManager}.
 * Also see {@link android.app.time.cts.TimeManagerTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TimeManagerTest {

    /**
     * This rule adopts the Shell process permissions, needed because MANAGE_TIME_AND_ZONE_DETECTION
     * and SUGGEST_EXTERNAL_TIME required by {@link TimeManager} are privileged permissions.
     */
    @Rule
    public final AdoptShellPermissionsRule shellPermRule = new AdoptShellPermissionsRule();

    private TimeZoneDetectorShellHelper mTimeZoneDetectorShellHelper;
    private DeviceConfigShellHelper mDeviceConfigShellHelper;
    private DeviceConfigShellHelper.PreTestState mDeviceConfigPreTestState;

    private Context mContext;
    private TimeManager mTimeManager;

    @Before
    public void before() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        DeviceShellCommandExecutor shellCommandExecutor = new InstrumentationShellCommandExecutor(
                instrumentation.getUiAutomation());
        mTimeZoneDetectorShellHelper = new TimeZoneDetectorShellHelper(shellCommandExecutor);
        mDeviceConfigShellHelper = new DeviceConfigShellHelper(shellCommandExecutor);

        // This anticipates a future state where a generally applied target preparer may disable
        // device_config sync for all CTS tests: only suspend syncing if it isn't already suspended,
        // and only resume it if this test suspended it.
        mDeviceConfigPreTestState = mDeviceConfigShellHelper.setSyncModeForTest(
                SYNC_DISABLED_MODE_UNTIL_REBOOT, NAMESPACE_SYSTEM_TIME);

        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mTimeManager = mContext.getSystemService(TimeManager.class);
        assertNotNull(mTimeManager);

        // Avoid running tests when device policy doesn't allow user configuration. If this needs to
        // pass then tests will become more complicated or separate cases broken out.
        int configureAutoDetectionEnabledCapability = mTimeManager.getTimeCapabilitiesAndConfig()
                .getCapabilities().getConfigureAutoDetectionEnabledCapability();
        boolean userRestricted = configureAutoDetectionEnabledCapability == CAPABILITY_NOT_ALLOWED;
        assertFalse(userRestricted);
    }

    @After
    public void after() throws Exception {
        mDeviceConfigShellHelper.restoreDeviceConfigStateForTest(mDeviceConfigPreTestState);
    }

    /**
     * Tests a server flag that can be used to change the "automatic time zone enabled" value
     * for devices where the user hasn't yet expressed a preference. The flag is only intended for
     * use during internal testing and therefore has not been included in CTS; it could be removed
     * in later releases. This test takes ~35s to run because the asynchronous operations involved
     * require sleeps to allow them to complete.
     */
    @Test
    public void testTimeZoneEnabledDefaultFlagBehavior() throws Exception {
        TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                mTimeManager.getTimeZoneCapabilitiesAndConfig();

        TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();

        // Skip this test if the current user is not allowed to alter time detection settings via
        // the TimeManager APIs.
        assumeTrue(capabilities.getConfigureAutoDetectionEnabledCapability()
                == CAPABILITY_POSSESSED);

        // Start with the auto_time_zone_explicit setting empty, but record the value if there is
        // one so that it can be restored.
        boolean isAutoDetectionEnabledExplicit =
                mTimeZoneDetectorShellHelper.isAutoTimeZoneEnabledExplicitly();

        mTimeZoneDetectorShellHelper.clearAutoTimeZoneEnabledExplicitly();
        sleepForAsyncOperation();

        // Record the current time zone and auto detection setting so that it can be restored by the
        // test afterwards.
        boolean initialAutoTzEnabled =
                capabilitiesAndConfig.getConfiguration().isAutoDetectionEnabled();
        TimeZoneState initialTimeZoneState = mTimeManager.getTimeZoneState();

        try {
            // The server flag should be used to control the device's behavior initially because
            // auto_time_zone_explicit is not set.
            boolean newAutoTzEnabled = !initialAutoTzEnabled;
            mDeviceConfigShellHelper.put(NAMESPACE_SYSTEM_TIME,
                    KEY_TIME_ZONE_DETECTOR_AUTO_DETECTION_ENABLED_DEFAULT,
                    Boolean.toString(newAutoTzEnabled));
            sleepForAsyncOperation();
            assertEquals(newAutoTzEnabled, mTimeZoneDetectorShellHelper.isAutoDetectionEnabled());

            mDeviceConfigShellHelper.put(NAMESPACE_SYSTEM_TIME,
                    KEY_TIME_ZONE_DETECTOR_AUTO_DETECTION_ENABLED_DEFAULT,
                    Boolean.toString(initialAutoTzEnabled));
            sleepForAsyncOperation();
            assertEquals(initialAutoTzEnabled,
                    mTimeZoneDetectorShellHelper.isAutoDetectionEnabled());

            // Now simulate the user toggling the auto tz setting twice, which should cause the
            // system to recognize the user has expressed an explicit preference.
            TimeZoneConfiguration config1 = new TimeZoneConfiguration.Builder()
                    .setAutoDetectionEnabled(newAutoTzEnabled)
                    .build();
            mTimeManager.updateTimeZoneConfiguration(config1);
            sleepForAsyncOperation();
            assertEquals(newAutoTzEnabled,
                    mTimeZoneDetectorShellHelper.isAutoDetectionEnabled());

            TimeZoneConfiguration config2 = new TimeZoneConfiguration.Builder()
                    .setAutoDetectionEnabled(initialAutoTzEnabled)
                    .build();
            mTimeManager.updateTimeZoneConfiguration(config2);
            sleepForAsyncOperation();
            assertEquals(initialAutoTzEnabled,
                    mTimeZoneDetectorShellHelper.isAutoDetectionEnabled());

            // Auto tz enabled is now back to initialAutoTzEnabled.

            // Repeat the flag check: Now the server flag should have no effect because they have
            // expressed a preference.
            mDeviceConfigShellHelper.put(NAMESPACE_SYSTEM_TIME,
                    KEY_TIME_ZONE_DETECTOR_AUTO_DETECTION_ENABLED_DEFAULT,
                    Boolean.toString(newAutoTzEnabled));
            sleepForAsyncOperation();
            assertEquals(initialAutoTzEnabled,
                    mTimeZoneDetectorShellHelper.isAutoDetectionEnabled());
        } finally {
            // Restore the device's state (as much as possible).
            if (isAutoDetectionEnabledExplicit) {
                mTimeZoneDetectorShellHelper.setAutoTimeZoneEnabledExplicitly();
            } else {
                mTimeZoneDetectorShellHelper.clearAutoTimeZoneEnabledExplicitly();
            }

            // Restore auto tz and the time zone (if the device started in manual).
            mTimeZoneDetectorShellHelper.setAutoDetectionEnabled(initialAutoTzEnabled);
            if (!initialAutoTzEnabled) {
                // If the device started in "manual" we can restore the time zone to its original
                // state, maybe not confidence exactly.
                mTimeZoneDetectorShellHelper.setTimeZoneState(
                        initialTimeZoneState.getId(),
                        initialTimeZoneState.getUserShouldConfirmId());
            }
            sleepForAsyncOperation();
        }
    }

    /**
     * Sleeps for a length of time sufficient to allow async operations to complete. Many time
     * manager APIs are or could be asynchronous and deal with time, so there are no practical
     * alternatives.
     */
    private static void sleepForAsyncOperation() throws Exception {
        Thread.sleep(5_000);
    }
}
