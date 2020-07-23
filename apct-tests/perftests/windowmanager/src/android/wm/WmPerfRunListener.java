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

package android.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.wm.WindowManagerPerfTestBase.executeShellCommand;
import static android.wm.WindowManagerPerfTestBase.runWithShellPermissionIdentity;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManagerPolicyConstants;
import android.wm.WindowManagerPerfTestBase.SettingsSession;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.policy.PhoneWindow;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

import java.util.List;

/** Prepare the preconditions before running performance test. */
public class WmPerfRunListener extends RunListener {

    private static final String OPTION_KILL_BACKGROUND = "kill-bg";
    private static final long KILL_BACKGROUND_WAIT_MS = 3000;

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
                executeShellCommand("cmd overlay enable-exclusive " + navOverlay);
            });

    /** It only executes once before all tests. */
    @Override
    public void testRunStarted(Description description) {
        final Bundle arguments = InstrumentationRegistry.getArguments();

        // Use gesture navigation for consistency.
        mNavigationModeSetting.set(WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL);
        // Keep the device awake during testing.
        mStayOnWhilePluggedInSetting.set(BatteryManager.BATTERY_PLUGGED_ANY);

        runWithShellPermissionIdentity(() -> {
            final ActivityTaskManager atm = mContext.getSystemService(ActivityTaskManager.class);
            atm.removeAllVisibleRecentTasks();
            atm.removeStacksWithActivityTypes(new int[] { ACTIVITY_TYPE_STANDARD,
                    ACTIVITY_TYPE_ASSISTANT, ACTIVITY_TYPE_RECENTS, ACTIVITY_TYPE_UNDEFINED });
        });
        PhoneWindow.sendCloseSystemWindows(mContext, "WmPerfTests");

        if (Boolean.parseBoolean(arguments.getString(OPTION_KILL_BACKGROUND))) {
            runWithShellPermissionIdentity(this::killBackgroundProcesses);
            mWaitPreconditionDoneMs = KILL_BACKGROUND_WAIT_MS;
        }
        // Wait a while for the precondition setup to complete.
        SystemClock.sleep(mWaitPreconditionDoneMs);
    }

    private void killBackgroundProcesses() {
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
