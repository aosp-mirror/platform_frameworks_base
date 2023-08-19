/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.DeviceConfig;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import com.android.frameworks.coretests.aidl.ICmdCallback;
import com.android.frameworks.coretests.aidl.ICmdReceiver;
import com.android.server.power.optimization.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class CpuPowerStatsCollectorValidationTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final int WORK_DURATION_MS = 2000;
    private static final String TEST_PKG = "com.android.coretests.apps.bstatstestapp";
    private static final String TEST_ACTIVITY = TEST_PKG + ".TestActivity";
    private static final String EXTRA_KEY_CMD_RECEIVER = "cmd_receiver";
    private static final int START_ACTIVITY_TIMEOUT_MS = 2000;

    private Context mContext;
    private UiDevice mUiDevice;
    private DeviceConfig.Properties mBackupFlags;
    private int mTestPkgUid;

    @Before
    public void setup() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mTestPkgUid = mContext.getPackageManager().getPackageUid(TEST_PKG, 0);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_STREAMLINED_BATTERY_STATS)
    public void totalTimeInPowerBrackets() throws Exception {
        dumpCpuStats();     // For the side effect of capturing the baseline.

        doSomeWork();

        long duration = 0;
        long[] stats = null;

        String[] cpuStatsDump = dumpCpuStats();
        Pattern durationPattern = Pattern.compile("duration=([0-9]*)");
        Pattern uidPattern = Pattern.compile("UID " + mTestPkgUid + ": \\[([0-9,\\s]*)]");
        for (String line : cpuStatsDump) {
            Matcher durationMatcher = durationPattern.matcher(line);
            if (durationMatcher.find()) {
                duration = Long.parseLong(durationMatcher.group(1));
            }
            Matcher uidMatcher = uidPattern.matcher(line);
            if (uidMatcher.find()) {
                String[] strings = uidMatcher.group(1).split(", ");
                stats = new long[strings.length];
                for (int i = 0; i < strings.length; i++) {
                    stats[i] = Long.parseLong(strings[i]);
                }
            }
        }
        if (stats == null) {
            fail("No CPU stats for " + mTestPkgUid + " (" + TEST_PKG + ")");
        }

        assertThat(duration).isAtLeast(WORK_DURATION_MS);

        long total = Arrays.stream(stats).sum();
        assertThat(total).isAtLeast((long) (WORK_DURATION_MS * 0.8));
    }

    private String[] dumpCpuStats() throws Exception {
        String dump = executeCmdSilent("dumpsys batterystats --sample");
        String[] lines = dump.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("CpuPowerStatsCollector")) {
                return Arrays.copyOfRange(lines, i + 1, lines.length);
            }
        }
        return new String[0];
    }

    private void doSomeWork() throws Exception {
        final ICmdReceiver receiver;
        receiver = ICmdReceiver.Stub.asInterface(startActivity());
        try {
            receiver.doSomeWork(WORK_DURATION_MS);
        } finally {
            receiver.finishHost();
        }
    }

    private IBinder startActivity() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Intent launchIntent = new Intent().setComponent(
                new ComponentName(TEST_PKG, TEST_ACTIVITY));
        final Bundle extras = new Bundle();
        final IBinder[] binders = new IBinder[1];
        extras.putBinder(EXTRA_KEY_CMD_RECEIVER, new ICmdCallback.Stub() {
            @Override
            public void onLaunched(IBinder receiver) {
                binders[0] = receiver;
                latch.countDown();
            }
        });
        launchIntent.putExtras(extras).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(launchIntent);
        if (latch.await(START_ACTIVITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (binders[0] == null) {
                fail("Receiver binder should not be null");
            }
            return binders[0];
        } else {
            fail("Timed out waiting for the test activity to start; testUid=" + mTestPkgUid);
        }
        return null;
    }

    private String executeCmdSilent(String cmd) throws Exception {
        return mUiDevice.executeShellCommand(cmd).trim();
    }
}
