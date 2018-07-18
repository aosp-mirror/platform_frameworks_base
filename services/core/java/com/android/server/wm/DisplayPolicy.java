/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.WindowManagerPolicyConstants.ACTION_HDMI_PLUGGED;
import static android.view.WindowManagerPolicyConstants.EXTRA_HDMI_PLUGGED_STATE;
import static com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs.LID_ABSENT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREEN_ON;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.Intent;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.policy.WindowManagerPolicy.ScreenOnListener;
import com.android.server.policy.WindowManagerPolicy.WindowManagerFuncs;

import java.io.PrintWriter;

/**
 * The policy that provides the basic behaviors and states of a display to show UI.
 */
public class DisplayPolicy {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayPolicy" : TAG_WM;

    private final WindowManagerService mService;
    private final Object mLock;

    private final boolean mCarDockEnablesAccelerometer;
    private final boolean mDeskDockEnablesAccelerometer;

    private volatile int mLidState = LID_ABSENT;
    private volatile int mDockMode = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private volatile boolean mHdmiPlugged;

    private volatile boolean mHasNavigationBar;
    // Can the navigation bar ever move to the side?
    private volatile boolean mNavigationBarCanMove;

    // Written by vr manager thread, only read in this class.
    private volatile boolean mPersistentVrModeEnabled;

    private volatile boolean mAwake;
    private volatile boolean mScreenOnEarly;
    private volatile boolean mScreenOnFully;
    private volatile ScreenOnListener mScreenOnListener;

    private volatile boolean mKeyguardDrawComplete;
    private volatile boolean mWindowManagerDrawComplete;

    DisplayPolicy(WindowManagerService service) {
        mService = service;
        mLock = service.getWindowManagerLock();
        mCarDockEnablesAccelerometer = service.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_carDockEnablesAccelerometer);
        mDeskDockEnablesAccelerometer = service.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_deskDockEnablesAccelerometer);
    }

    void configure(int width, int height, int shortSizeDp) {
        // Allow the navigation bar to move on non-square small devices (phones).
        mNavigationBarCanMove = width != height && shortSizeDp < 600;

        mHasNavigationBar = mService.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);

        // Allow a system property to override this. Used by the emulator.
        // See also hasNavigationBar().
        String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
        if ("1".equals(navBarOverride)) {
            mHasNavigationBar = false;
        } else if ("0".equals(navBarOverride)) {
            mHasNavigationBar = true;
        }
    }

    public void setHdmiPlugged(boolean plugged) {
        setHdmiPlugged(plugged, false /* force */);
    }

    public void setHdmiPlugged(boolean plugged, boolean force) {
        if (force || mHdmiPlugged != plugged) {
            mHdmiPlugged = plugged;
            mService.updateRotation(true /* alwaysSendConfiguration */, true /* forceRelayout */);
            final Intent intent = new Intent(ACTION_HDMI_PLUGGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_HDMI_PLUGGED_STATE, plugged);
            mService.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    boolean isHdmiPlugged() {
        return mHdmiPlugged;
    }

    boolean isCarDockEnablesAccelerometer() {
        return mCarDockEnablesAccelerometer;
    }

    boolean isDeskDockEnablesAccelerometer() {
        return mDeskDockEnablesAccelerometer;
    }

    public void setPersistentVrModeEnabled(boolean persistentVrModeEnabled) {
        mPersistentVrModeEnabled = persistentVrModeEnabled;
    }

    public boolean isPersistentVrModeEnabled() {
        return mPersistentVrModeEnabled;
    }

    public void setDockMode(int dockMode) {
        mDockMode = dockMode;
    }

    public int getDockMode() {
        return mDockMode;
    }

    public boolean hasNavigationBar() {
        return mHasNavigationBar;
    }

    public boolean navigationBarCanMove() {
        return mNavigationBarCanMove;
    }

    public void setLidState(int lidState) {
        mLidState = lidState;
    }

    public int getLidState() {
        return mLidState;
    }

    public void setAwake(boolean awake) {
        mAwake = awake;
    }

    public boolean isAwake() {
        return mAwake;
    }

    public boolean isScreenOnEarly() {
        return mScreenOnEarly;
    }

    public boolean isScreenOnFully() {
        return mScreenOnFully;
    }

    public boolean isKeyguardDrawComplete() {
        return mKeyguardDrawComplete;
    }

    public boolean isWindowManagerDrawComplete() {
        return mWindowManagerDrawComplete;
    }

    public ScreenOnListener getScreenOnListener() {
        return mScreenOnListener;
    }

    public void screenTurnedOn(ScreenOnListener screenOnListener) {
        synchronized (mLock) {
            mScreenOnEarly = true;
            mScreenOnFully = false;
            mKeyguardDrawComplete = false;
            mWindowManagerDrawComplete = false;
            mScreenOnListener = screenOnListener;
        }
    }

    public void screenTurnedOff() {
        synchronized (mLock) {
            mScreenOnEarly = false;
            mScreenOnFully = false;
            mKeyguardDrawComplete = false;
            mWindowManagerDrawComplete = false;
            mScreenOnListener = null;
        }
    }

    /** Return false if we are not awake yet or we have already informed of this event. */
    public boolean finishKeyguardDrawn() {
        synchronized (mLock) {
            if (!mScreenOnEarly || mKeyguardDrawComplete) {
                return false;
            }

            mKeyguardDrawComplete = true;
            mWindowManagerDrawComplete = false;
        }
        return true;
    }

    /** Return false if screen is not turned on or we did already handle this case earlier. */
    public boolean finishWindowsDrawn() {
        synchronized (mLock) {
            if (!mScreenOnEarly || mWindowManagerDrawComplete) {
                return false;
            }

            mWindowManagerDrawComplete = true;
        }
        return true;
    }

    /** Return false if it is not ready to turn on. */
    public boolean finishScreenTurningOn() {
        synchronized (mLock) {
            if (DEBUG_SCREEN_ON) Slog.d(TAG,
                    "finishScreenTurningOn: mAwake=" + mAwake
                            + ", mScreenOnEarly=" + mScreenOnEarly
                            + ", mScreenOnFully=" + mScreenOnFully
                            + ", mKeyguardDrawComplete=" + mKeyguardDrawComplete
                            + ", mWindowManagerDrawComplete=" + mWindowManagerDrawComplete);

            if (mScreenOnFully || !mScreenOnEarly || !mWindowManagerDrawComplete
                    || (mAwake && !mKeyguardDrawComplete)) {
                return false;
            }

            if (DEBUG_SCREEN_ON) Slog.i(TAG, "Finished screen turning on...");
            mScreenOnListener = null;
            mScreenOnFully = true;
        }
        return true;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayPolicy");
        pw.print(prefix + "  mCarDockEnablesAccelerometer=" + mCarDockEnablesAccelerometer);
        pw.println(" mDeskDockEnablesAccelerometer=" + mDeskDockEnablesAccelerometer);
        pw.print(prefix + "  mDockMode=" + Intent.dockStateToString(mDockMode));
        pw.println(" mLidState=" + WindowManagerFuncs.lidStateToString(mLidState));
        pw.print(prefix + "  mAwake=" + mAwake);
        pw.print(" mScreenOnEarly=" + mScreenOnEarly);
        pw.println(" mScreenOnFully=" + mScreenOnFully);
        pw.print(prefix + "  mKeyguardDrawComplete=" + mKeyguardDrawComplete);
        pw.println(" mWindowManagerDrawComplete=" + mWindowManagerDrawComplete);
        pw.println(prefix + "  mHdmiPlugged=" + mHdmiPlugged);
    }
}
