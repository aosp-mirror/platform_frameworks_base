/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.keyguard;

import android.annotation.IntDef;
import android.app.IWallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Trace;
import android.util.DisplayMetrics;

import androidx.annotation.Nullable;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;

/**
 * Tracks the wakefulness lifecycle, including why we're waking or sleeping.
 */
@SysUISingleton
public class WakefulnessLifecycle extends Lifecycle<WakefulnessLifecycle.Observer> implements
        Dumpable {

    @IntDef(prefix = { "WAKEFULNESS_" }, value = {
            WAKEFULNESS_ASLEEP,
            WAKEFULNESS_WAKING,
            WAKEFULNESS_AWAKE,
            WAKEFULNESS_GOING_TO_SLEEP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Wakefulness {}

    public static final int WAKEFULNESS_ASLEEP = 0;
    public static final int WAKEFULNESS_WAKING = 1;
    public static final int WAKEFULNESS_AWAKE = 2;
    public static final int WAKEFULNESS_GOING_TO_SLEEP = 3;

    private final Context mContext;
    private final DisplayMetrics mDisplayMetrics;

    @Nullable
    private final IWallpaperManager mWallpaperManagerService;

    private int mWakefulness = WAKEFULNESS_AWAKE;

    private @PowerManager.WakeReason int mLastWakeReason = PowerManager.WAKE_REASON_UNKNOWN;

    @Nullable
    private Point mLastWakeOriginLocation = null;

    private @PowerManager.GoToSleepReason int mLastSleepReason =
            PowerManager.GO_TO_SLEEP_REASON_MIN;

    @Nullable
    private Point mLastSleepOriginLocation = null;

    @Inject
    public WakefulnessLifecycle(
            Context context,
            @Nullable IWallpaperManager wallpaperManagerService,
            DumpManager dumpManager) {
        mContext = context;
        mDisplayMetrics = context.getResources().getDisplayMetrics();
        mWallpaperManagerService = wallpaperManagerService;

        dumpManager.registerDumpable(getClass().getSimpleName(), this);
    }

    public @Wakefulness int getWakefulness() {
        return mWakefulness;
    }

    /**
     * Returns the most recent reason the device woke up. This is one of PowerManager.WAKE_REASON_*.
     */
    public @PowerManager.WakeReason int getLastWakeReason() {
        return mLastWakeReason;
    }

    /**
     * Returns the most recent reason the device went to sleep up. This is one of
     * PowerManager.GO_TO_SLEEP_REASON_*.
     */
    public @PowerManager.GoToSleepReason int getLastSleepReason() {
        return mLastSleepReason;
    }

    public void dispatchStartedWakingUp(@PowerManager.WakeReason int pmWakeReason) {
        if (getWakefulness() == WAKEFULNESS_WAKING) {
            return;
        }
        setWakefulness(WAKEFULNESS_WAKING);
        mLastWakeReason = pmWakeReason;
        updateLastWakeOriginLocation();

        if (mWallpaperManagerService != null) {
            try {
                mWallpaperManagerService.notifyWakingUp(
                        mLastWakeOriginLocation.x, mLastWakeOriginLocation.y, new Bundle());
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        dispatch(Observer::onStartedWakingUp);
    }

    public void dispatchFinishedWakingUp() {
        if (getWakefulness() == WAKEFULNESS_AWAKE) {
            return;
        }
        setWakefulness(WAKEFULNESS_AWAKE);
        dispatch(Observer::onFinishedWakingUp);
        dispatch(Observer::onPostFinishedWakingUp);
    }

    public void dispatchStartedGoingToSleep(@PowerManager.GoToSleepReason int pmSleepReason) {
        if (getWakefulness() == WAKEFULNESS_GOING_TO_SLEEP) {
            return;
        }
        setWakefulness(WAKEFULNESS_GOING_TO_SLEEP);
        mLastSleepReason = pmSleepReason;
        updateLastSleepOriginLocation();

        if (mWallpaperManagerService != null) {
            try {
                mWallpaperManagerService.notifyGoingToSleep(
                        mLastSleepOriginLocation.x, mLastSleepOriginLocation.y, new Bundle());
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        dispatch(Observer::onStartedGoingToSleep);
    }

    public void dispatchFinishedGoingToSleep() {
        if (getWakefulness() == WAKEFULNESS_ASLEEP) {
            return;
        }
        setWakefulness(WAKEFULNESS_ASLEEP);
        dispatch(Observer::onFinishedGoingToSleep);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("WakefulnessLifecycle:");
        pw.println("  mWakefulness=" + mWakefulness);
    }

    private void setWakefulness(@Wakefulness int wakefulness) {
        mWakefulness = wakefulness;
        Trace.traceCounter(Trace.TRACE_TAG_APP, "wakefulness", wakefulness);
    }

    private void updateLastWakeOriginLocation() {
        mLastWakeOriginLocation = null;

        switch (mLastWakeReason) {
            case PowerManager.WAKE_REASON_POWER_BUTTON:
                mLastWakeOriginLocation = getPowerButtonOrigin();
                break;
            default:
                mLastWakeOriginLocation = getDefaultWakeSleepOrigin();
                break;
        }
    }

    private void updateLastSleepOriginLocation() {
        mLastSleepOriginLocation = null;

        switch (mLastSleepReason) {
            case PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON:
                mLastSleepOriginLocation = getPowerButtonOrigin();
                break;
            default:
                mLastSleepOriginLocation = getDefaultWakeSleepOrigin();
                break;
        }
    }

    /**
     * Returns the point on the screen closest to the physical power button.
     */
    private Point getPowerButtonOrigin() {
        final boolean isPortrait = mContext.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;

        if (isPortrait) {
            return new Point(
                    mDisplayMetrics.widthPixels,
                    mContext.getResources().getDimensionPixelSize(
                            R.dimen.physical_power_button_center_screen_location_y));
        } else {
            return new Point(
                    mContext.getResources().getDimensionPixelSize(
                            R.dimen.physical_power_button_center_screen_location_y),
                    mDisplayMetrics.heightPixels);
        }
    }

    /**
     * Returns the point on the screen used as the default origin for wake/sleep events. This is the
     * middle-bottom of the screen.
     */
    private Point getDefaultWakeSleepOrigin() {
        return new Point(mDisplayMetrics.widthPixels / 2, mDisplayMetrics.heightPixels);
    }

    public interface Observer {
        default void onStartedWakingUp() {}
        default void onFinishedWakingUp() {}

        /**
         * Called after the finished waking up call, ensuring it's after all the other listeners,
         * reacting to {@link #onFinishedWakingUp()}
         */
        default void onPostFinishedWakingUp() {}
        default void onStartedGoingToSleep() {}
        default void onFinishedGoingToSleep() {}
    }
}
