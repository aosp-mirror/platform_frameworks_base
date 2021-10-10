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

package android.perftests.utils;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.perftests.utils.WindowPerfTestBase.executeShellCommand;
import static android.perftests.utils.WindowPerfTestBase.runWithShellPermissionIdentity;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.perftests.utils.WindowPerfTestBase.SettingsSession;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManagerPolicyConstants;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.policy.PhoneWindow;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import java.util.List;

/** Prepare the preconditions before running performance test. */
public class WindowPerfRunPreconditionBase extends RunListener {
    protected final String mTag = getClass().getSimpleName();

    private static final String ARGUMENT_LOG_ONLY = "log";
    private static final String ARGUMENT_KILL_BACKGROUND = "kill-bg";
    private static final String ARGUMENT_PROFILING_ITERATIONS = "profiling-iterations";
    private static final String ARGUMENT_PROFILING_SAMPLING = "profiling-sampling";
    private static final String DEFAULT_PROFILING_ITERATIONS = "0";
    private static final String DEFAULT_PROFILING_SAMPLING_US = "10";
    private static final long KILL_BACKGROUND_WAIT_MS = 3000;

    /** The requested iterations to run with method profiling. */
    static int sProfilingIterations;

    /** The interval of sample profiling in microseconds. */
    static int sSamplingIntervalUs;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private long mWaitPreconditionDoneMs = 500;

    private final SettingsSession<Integer> mStayOnWhilePluggedInSetting = new SettingsSession<>(
            Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0),
            value -> executeShellCommand(String.format("settings put global %s %d",
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN, value)));

    private final SettingsSession<Integer> mNavigationModeSetting = new SettingsSession<>(
            mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_navBarInteractionMode),
            value -> {
                final String navOverlay;
                switch (value) {
                    case WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON:
                        navOverlay = WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON_OVERLAY;
                        break;
                    case WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON:
                        navOverlay = WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON_OVERLAY;
                        break;
                    case WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL:
                    default:
                        navOverlay = WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL_OVERLAY;
                        break;
                }
                executeShellCommand("cmd overlay enable-exclusive --category " + navOverlay);
            });

    /** It only executes once before all tests. */
    @Override
    public void testRunStarted(Description description) {
        final Bundle arguments = InstrumentationRegistry.getArguments();
        // If true, it only logs the method names without running.
        final boolean skip = Boolean.parseBoolean(arguments.getString(ARGUMENT_LOG_ONLY, "false"));
        Log.i(mTag, "arguments=" + arguments);
        if (skip) {
            return;
        }
        sProfilingIterations = Integer.parseInt(
                arguments.getString(ARGUMENT_PROFILING_ITERATIONS, DEFAULT_PROFILING_ITERATIONS));
        sSamplingIntervalUs = Integer.parseInt(
                arguments.getString(ARGUMENT_PROFILING_SAMPLING, DEFAULT_PROFILING_SAMPLING_US));

        // Use same navigation mode (gesture navigation) across all devices and tests
        // for consistency.
        mNavigationModeSetting.set(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL);
        // Keep the device awake during testing.
        mStayOnWhilePluggedInSetting.set(BatteryManager.BATTERY_PLUGGED_ANY);

        runWithShellPermissionIdentity(() -> {
            final ActivityTaskManager atm = mContext.getSystemService(ActivityTaskManager.class);
            atm.removeAllVisibleRecentTasks();
            atm.removeRootTasksWithActivityTypes(new int[] { ACTIVITY_TYPE_STANDARD,
                    ACTIVITY_TYPE_ASSISTANT, ACTIVITY_TYPE_RECENTS, ACTIVITY_TYPE_UNDEFINED });
        });
        PhoneWindow.sendCloseSystemWindows(mContext, mTag);

        if (Boolean.parseBoolean(arguments.getString(ARGUMENT_KILL_BACKGROUND))) {
            runWithShellPermissionIdentity(this::killBackgroundProcesses);
            mWaitPreconditionDoneMs = KILL_BACKGROUND_WAIT_MS;
        }
        // Wait a while for the precondition setup to complete.
        SystemClock.sleep(mWaitPreconditionDoneMs);
    }

    private void killBackgroundProcesses() {
        Log.i(mTag, "Killing background processes...");
        final ActivityManager am = mContext.getSystemService(ActivityManager.class);
        final List<RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) {
            return;
        }
        for (RunningAppProcessInfo processInfo : processes) {
            if (processInfo.importanceReasonCode == RunningAppProcessInfo.REASON_UNKNOWN
                    && processInfo.importance > RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                for (String pkg : processInfo.pkgList) {
                    am.forceStopPackage(pkg);
                }
            }
        }
    }

    /** It only executes once after all tests. */
    @Override
    public void testRunFinished(Result result) {
        mNavigationModeSetting.close();
        mStayOnWhilePluggedInSetting.close();
    }
}
