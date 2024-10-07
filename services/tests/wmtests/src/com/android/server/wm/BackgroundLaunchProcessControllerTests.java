/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.ActivityTaskManagerService.ACTIVITY_BG_START_GRACE_PERIOD_MS;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_ALLOW;
import static com.android.server.wm.ActivityTaskManagerService.APP_SWITCH_DISALLOW;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_BOUND_BY_FOREGROUND;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_FOREGROUND;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_GRACE_PERIOD;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_PERMISSION;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_TOKEN;
import static com.android.server.wm.BackgroundActivityStartController.BAL_ALLOW_VISIBLE_WINDOW;
import static com.android.server.wm.BackgroundActivityStartController.BAL_BLOCK;

import static com.google.common.truth.Truth.assertThat;

import android.app.BackgroundStartPrivileges;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.server.wm.BackgroundActivityStartController.BalVerdict;
import com.android.window.flags.Flags;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;


/**
 * Tests for the {@link BackgroundLaunchProcessController} class.
 *
 * Build/Install/Run:
 * atest WmTests:BackgroundLaunchProcessControllerTests
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class BackgroundLaunchProcessControllerTests {


    @ClassRule public static final SetFlagsRule.ClassRule mClassRule = new SetFlagsRule.ClassRule();
    @Rule public final SetFlagsRule mSetFlagsRule = mClassRule.createSetFlagsRule();

    Set<IBinder> mActivityStartAllowed = new HashSet<>();
    Set<Integer> mHasActiveVisibleWindow = new HashSet<>();

    BackgroundActivityStartCallback mCallback = new BackgroundActivityStartCallback() {
        @Override
        public boolean isActivityStartAllowed(Collection<IBinder> tokens, int uid,
                String packageName) {
            for (IBinder token : tokens) {
                if (token == null || mActivityStartAllowed.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean canCloseSystemDialogs(Collection<IBinder> tokens, int uid) {
            return false;
        }
    };
    BackgroundLaunchProcessController mController = new BackgroundLaunchProcessController(
            mHasActiveVisibleWindow::contains, mCallback);

    int mPid = 123;
    int mUid = 234;
    String mPackageName = "package.name";
    int mAppSwitchState = APP_SWITCH_DISALLOW;
    BackgroundLaunchProcessController.BalCheckConfiguration mBalCheckConfiguration =
            new BackgroundLaunchProcessController.BalCheckConfiguration(
                    /* isCheckingForFgsStarts */ false,
                    /* checkVisibility */ true,
                    /* checkOtherExemptions */ true,
                    ACTIVITY_BG_START_GRACE_PERIOD_MS);
    boolean mHasActivityInVisibleTask = false;
    boolean mHasBackgroundActivityStartPrivileges = false;
    long mLastStopAppSwitchesTime = 0L;
    long mLastActivityLaunchTime = 0L;
    long mLastActivityFinishTime = 0L;

    @Test
    public void testNothingAllows() {
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_BLOCK);
    }

    @Test
    public void testInstrumenting() {
        mHasBackgroundActivityStartPrivileges = true;
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_PERMISSION);
    }

    @Test
    @DisableFlags(Flags.FLAG_BAL_IMPROVED_METRICS)
    public void testAllowedByTokenNoCallbackOld() {
        mController = new BackgroundLaunchProcessController(mHasActiveVisibleWindow::contains,
                null);
        Binder token = new Binder();
        mActivityStartAllowed.add(token);
        mController.addOrUpdateAllowBackgroundStartPrivileges(token,
                BackgroundStartPrivileges.ALLOW_BAL);
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_PERMISSION);
    }

    @Test
    @EnableFlags(Flags.FLAG_BAL_IMPROVED_METRICS)
    public void testAllowedByTokenNoCallback() {
        mController = new BackgroundLaunchProcessController(mHasActiveVisibleWindow::contains,
                null);
        Binder token = new Binder();
        mActivityStartAllowed.add(token);
        mController.addOrUpdateAllowBackgroundStartPrivileges(token,
                BackgroundStartPrivileges.ALLOW_BAL);
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_TOKEN);
    }

    @Test
    @DisableFlags(Flags.FLAG_BAL_IMPROVED_METRICS)
    public void testAllowedByTokenOld() {
        Binder token = new Binder();
        mActivityStartAllowed.add(token);
        mController.addOrUpdateAllowBackgroundStartPrivileges(token,
                BackgroundStartPrivileges.ALLOW_BAL);
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_PERMISSION);
    }

    @Test
    @EnableFlags(Flags.FLAG_BAL_IMPROVED_METRICS)
    public void testAllowedByToken() {
        Binder token = new Binder();
        mActivityStartAllowed.add(token);
        mController.addOrUpdateAllowBackgroundStartPrivileges(token,
                BackgroundStartPrivileges.ALLOW_BAL);
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_TOKEN);
    }

    @Test
    @DisableFlags(Flags.FLAG_BAL_IMPROVED_METRICS)
    public void testBoundByForegroundOld() {
        mAppSwitchState = APP_SWITCH_ALLOW;
        mController.addBoundClientUid(999, "visible.package", Context.BIND_ALLOW_ACTIVITY_STARTS);
        mHasActiveVisibleWindow.add(999);
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_VISIBLE_WINDOW);
    }

    @Test
    @EnableFlags(Flags.FLAG_BAL_IMPROVED_METRICS)
    public void testBoundByForeground() {
        mAppSwitchState = APP_SWITCH_ALLOW;
        mController.addBoundClientUid(999, "visible.package", Context.BIND_ALLOW_ACTIVITY_STARTS);
        mHasActiveVisibleWindow.add(999);
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_BOUND_BY_FOREGROUND);
    }

    @Test
    public void testForegroundTask() {
        mAppSwitchState = APP_SWITCH_ALLOW;
        mHasActivityInVisibleTask = true;
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_FOREGROUND);
    }

    @Test
    public void testGracePeriod() {
        mAppSwitchState = APP_SWITCH_ALLOW;
        long now = System.currentTimeMillis();
        mLastStopAppSwitchesTime = now - 10000;
        mLastActivityLaunchTime = now - 9000;
        mLastActivityFinishTime = now - 100;
        BalVerdict balVerdict = mController.areBackgroundActivityStartsAllowed(
                mPid, mUid, mPackageName,
                mAppSwitchState, mBalCheckConfiguration,
                mHasActivityInVisibleTask, mHasBackgroundActivityStartPrivileges,
                mLastStopAppSwitchesTime, mLastActivityLaunchTime,
                mLastActivityFinishTime);
        assertThat(balVerdict.getCode()).isEqualTo(BAL_ALLOW_GRACE_PERIOD);
    }
}
