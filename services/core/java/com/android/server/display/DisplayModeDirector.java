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

package com.android.server.display;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.os.BackgroundThread;
import com.android.internal.R;
import com.android.server.display.whitebalance.DisplayWhiteBalanceFactory;
import com.android.server.display.whitebalance.AmbientFilter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The DisplayModeDirector is responsible for determining what modes are allowed to be
 * automatically picked by the system based on system-wide and display-specific configuration.
 */
public class DisplayModeDirector {
    private static final String TAG = "DisplayModeDirector";
    private static final boolean DEBUG = false;

    private static final int MSG_ALLOWED_MODES_CHANGED = 1;
    private static final int MSG_BRIGHTNESS_THRESHOLDS_CHANGED = 2;
    private static final int MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED = 3;
    private static final int MSG_REFRESH_RATE_IN_ZONE_CHANGED = 4;

    // Special ID used to indicate that given vote is to be applied globally, rather than to a
    // specific display.
    private static final int GLOBAL_ID = -1;

    // The tolerance within which we consider something approximately equals.
    private static final float EPSILON = 0.001f;

    private final Object mLock = new Object();
    private final Context mContext;

    private final DisplayModeDirectorHandler mHandler;

    // A map from the display ID to the collection of votes and their priority. The latter takes
    // the form of another map from the priority to the vote itself so that each priority is
    // guaranteed to have exactly one vote, which is also easily and efficiently replaceable.
    private final SparseArray<SparseArray<Vote>> mVotesByDisplay;
    // A map from the display ID to the supported modes on that display.
    private final SparseArray<Display.Mode[]> mSupportedModesByDisplay;
    // A map from the display ID to the default mode of that display.
    private final SparseArray<Display.Mode> mDefaultModeByDisplay;

    private final AppRequestObserver mAppRequestObserver;
    private final SettingsObserver mSettingsObserver;
    private final DisplayObserver mDisplayObserver;
    private final BrightnessObserver mBrightnessObserver;

    private final DeviceConfigDisplaySettings mDeviceConfigDisplaySettings;
    private Listener mListener;

    public DisplayModeDirector(@NonNull Context context, @NonNull Handler handler) {
        mContext = context;
        mHandler = new DisplayModeDirectorHandler(handler.getLooper());
        mVotesByDisplay = new SparseArray<>();
        mSupportedModesByDisplay = new SparseArray<>();
        mDefaultModeByDisplay =  new SparseArray<>();
        mAppRequestObserver = new AppRequestObserver();
        mSettingsObserver = new SettingsObserver(context, handler);
        mDisplayObserver = new DisplayObserver(context, handler);
        mBrightnessObserver = new BrightnessObserver(context, handler);
        mDeviceConfigDisplaySettings = new DeviceConfigDisplaySettings();
    }

    /**
     * Tells the DisplayModeDirector to update allowed votes and begin observing relevant system
     * state.
     *
     * This has to be deferred because the object may be constructed before the rest of the system
     * is ready.
     */
    public void start(SensorManager sensorManager) {
        mSettingsObserver.observe();
        mDisplayObserver.observe();
        mSettingsObserver.observe();
        mBrightnessObserver.observe(sensorManager);
        synchronized (mLock) {
            // We may have a listener already registered before the call to start, so go ahead and
            // notify them to pick up our newly initialized state.
            notifyAllowedModesChangedLocked();
        }

    }

    /**
     * Calculates the modes the system is allowed to freely switch between based on global and
     * display-specific constraints.
     *
     * @param displayId The display to query for.
     * @return The IDs of the modes the system is allowed to freely switch between.
     */
    @NonNull
    public int[] getAllowedModes(int displayId) {
        synchronized (mLock) {
            SparseArray<Vote> votes = getVotesLocked(displayId);
            Display.Mode[] modes = mSupportedModesByDisplay.get(displayId);
            Display.Mode defaultMode = mDefaultModeByDisplay.get(displayId);
            if (modes == null || defaultMode == null) {
                Slog.e(TAG, "Asked about unknown display, returning empty allowed set! (id="
                        + displayId + ")");
                return new int[0];
            }
            return getAllowedModesLocked(votes, modes, defaultMode);
        }
    }

    @NonNull
    private SparseArray<Vote> getVotesLocked(int displayId) {
        SparseArray<Vote> displayVotes = mVotesByDisplay.get(displayId);
        final SparseArray<Vote> votes;
        if (displayVotes != null) {
            votes = displayVotes.clone();
        } else {
            votes = new SparseArray<>();
        }

        SparseArray<Vote> globalVotes = mVotesByDisplay.get(GLOBAL_ID);
        if (globalVotes != null) {
            for (int i = 0; i < globalVotes.size(); i++) {
                int priority = globalVotes.keyAt(i);
                if (votes.indexOfKey(priority) < 0) {
                    votes.put(priority, globalVotes.valueAt(i));
                }
            }
        }
        return votes;
    }

    @NonNull
    private int[] getAllowedModesLocked(@NonNull SparseArray<Vote> votes,
            @NonNull Display.Mode[] modes, @NonNull Display.Mode defaultMode) {
        int lowestConsideredPriority = Vote.MIN_PRIORITY;
        while (lowestConsideredPriority <= Vote.MAX_PRIORITY) {
            float minRefreshRate = 0f;
            float maxRefreshRate = Float.POSITIVE_INFINITY;
            int height = Vote.INVALID_SIZE;
            int width = Vote.INVALID_SIZE;

            for (int priority = Vote.MAX_PRIORITY;
                    priority >= lowestConsideredPriority;
                    priority--) {
                Vote vote = votes.get(priority);
                if (vote == null) {
                    continue;
                }
                // For refresh rates, just use the tightest bounds of all the votes
                minRefreshRate = Math.max(minRefreshRate, vote.minRefreshRate);
                maxRefreshRate = Math.min(maxRefreshRate, vote.maxRefreshRate);
                // For display size, use only the first vote we come across (i.e. the highest
                // priority vote that includes the width / height).
                if (height == Vote.INVALID_SIZE && width == Vote.INVALID_SIZE
                        && vote.height > 0 && vote.width > 0) {
                    width = vote.width;
                    height = vote.height;
                }
            }

            // If we don't have anything specifying the width / height of the display, just use the
            // default width and height. We don't want these switching out from underneath us since
            // it's a pretty disruptive behavior.
            if (height == Vote.INVALID_SIZE || width == Vote.INVALID_SIZE) {
                width = defaultMode.getPhysicalWidth();
                height = defaultMode.getPhysicalHeight();
            }

            int[] availableModes =
                    filterModes(modes, width, height, minRefreshRate, maxRefreshRate);
            if (availableModes.length > 0) {
                if (DEBUG) {
                    Slog.w(TAG, "Found available modes=" + Arrays.toString(availableModes)
                            + " with lowest priority considered "
                            + Vote.priorityToString(lowestConsideredPriority)
                            + " and constraints: "
                            + "width=" + width
                            + ", height=" + height
                            + ", minRefreshRate=" + minRefreshRate
                            + ", maxRefreshRate=" + maxRefreshRate);
                }
                return availableModes;
            }

            if (DEBUG) {
                Slog.w(TAG, "Couldn't find available modes with lowest priority set to "
                        + Vote.priorityToString(lowestConsideredPriority)
                        + " and with the following constraints: "
                        + "width=" + width
                        + ", height=" + height
                        + ", minRefreshRate=" + minRefreshRate
                        + ", maxRefreshRate=" + maxRefreshRate);
            }
            // If we haven't found anything with the current set of votes, drop the current lowest
            // priority vote.
            lowestConsideredPriority++;
        }

        // If we still haven't found anything that matches our current set of votes, just fall back
        // to the default mode.
        return new int[] { defaultMode.getModeId() };
    }

    private int[] filterModes(Display.Mode[] supportedModes,
            int width, int height, float minRefreshRate, float maxRefreshRate) {
        ArrayList<Display.Mode> availableModes = new ArrayList<>();
        for (Display.Mode mode : supportedModes) {
            if (mode.getPhysicalWidth() != width || mode.getPhysicalHeight() != height) {
                if (DEBUG) {
                    Slog.w(TAG, "Discarding mode " + mode.getModeId() + ", wrong size"
                            + ": desiredWidth=" + width
                            + ": desiredHeight=" + height
                            + ": actualWidth=" + mode.getPhysicalWidth()
                            + ": actualHeight=" + mode.getPhysicalHeight());
                }
                continue;
            }
            final float refreshRate = mode.getRefreshRate();
            // Some refresh rates are calculated based on frame timings, so they aren't *exactly*
            // equal to expected refresh rate. Given that, we apply a bit of tolerance to this
            // comparison.
            if (refreshRate < (minRefreshRate - EPSILON)
                    || refreshRate > (maxRefreshRate + EPSILON)) {
                if (DEBUG) {
                    Slog.w(TAG, "Discarding mode " + mode.getModeId()
                            + ", outside refresh rate bounds"
                            + ": minRefreshRate=" + minRefreshRate
                            + ", maxRefreshRate=" + maxRefreshRate
                            + ", modeRefreshRate=" + refreshRate);
                }
                continue;
            }
            availableModes.add(mode);
        }
        final int size = availableModes.size();
        int[] availableModeIds = new int[size];
        for (int i = 0; i < size; i++) {
            availableModeIds[i] = availableModes.get(i).getModeId();
        }
        return availableModeIds;
    }

    /**
     * Gets the observer responsible for application display mode requests.
     */
    @NonNull
    public AppRequestObserver getAppRequestObserver() {
        // We don't need to lock here because mAppRequestObserver is a final field, which is
        // guaranteed to be visible on all threads after construction.
        return mAppRequestObserver;
    }

    /**
     * Sets the listener for changes to allowed display modes.
     */
    public void setListener(@Nullable Listener listener) {
        synchronized (mLock) {
            mListener = listener;
        }
    }

    /**
     * Print the object's state and debug information into the given stream.
     *
     * @param pw The stream to dump information to.
     */
    public void dump(PrintWriter pw) {
        pw.println("DisplayModeDirector");
        synchronized (mLock) {
            pw.println("  mSupportedModesByDisplay:");
            for (int i = 0; i < mSupportedModesByDisplay.size(); i++) {
                final int id = mSupportedModesByDisplay.keyAt(i);
                final Display.Mode[] modes = mSupportedModesByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + Arrays.toString(modes));
            }
            pw.println("  mDefaultModeByDisplay:");
            for (int i = 0; i < mDefaultModeByDisplay.size(); i++) {
                final int id = mDefaultModeByDisplay.keyAt(i);
                final Display.Mode mode = mDefaultModeByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + mode);
            }
            pw.println("  mVotesByDisplay:");
            for (int i = 0; i < mVotesByDisplay.size(); i++) {
                pw.println("    " + mVotesByDisplay.keyAt(i) + ":");
                SparseArray<Vote> votes = mVotesByDisplay.valueAt(i);
                for (int p = Vote.MAX_PRIORITY; p >= Vote.MIN_PRIORITY; p--) {
                    Vote vote = votes.get(p);
                    if (vote == null) {
                        continue;
                    }
                    pw.println("      " + Vote.priorityToString(p) + " -> " + vote);
                }
            }
            mSettingsObserver.dumpLocked(pw);
            mAppRequestObserver.dumpLocked(pw);
            mBrightnessObserver.dumpLocked(pw);
        }
    }

    private void updateVoteLocked(int priority, Vote vote) {
        updateVoteLocked(GLOBAL_ID, priority, vote);
    }

    private void updateVoteLocked(int displayId, int priority, Vote vote) {
        if (DEBUG) {
            Slog.i(TAG, "updateVoteLocked(displayId=" + displayId
                    + ", priority=" + Vote.priorityToString(priority)
                    + ", vote=" + vote + ")");
        }
        if (priority < Vote.MIN_PRIORITY || priority > Vote.MAX_PRIORITY) {
            Slog.w(TAG, "Received a vote with an invalid priority, ignoring:"
                    + " priority=" + Vote.priorityToString(priority)
                    + ", vote=" + vote, new Throwable());
            return;
        }
        final SparseArray<Vote> votes = getOrCreateVotesByDisplay(displayId);

        Vote currentVote = votes.get(priority);
        if (vote != null) {
            votes.put(priority, vote);
        } else {
            votes.remove(priority);
        }

        if (votes.size() == 0) {
            if (DEBUG) {
                Slog.i(TAG, "No votes left for display " + displayId + ", removing.");
            }
            mVotesByDisplay.remove(displayId);
        }

        notifyAllowedModesChangedLocked();
    }

    private void notifyAllowedModesChangedLocked() {
        if (mListener != null && !mHandler.hasMessages(MSG_ALLOWED_MODES_CHANGED)) {
            // We need to post this to a handler to avoid calling out while holding the lock
            // since we know there are things that both listen for changes as well as provide
            // information. If we did call out while holding the lock, then there's no guaranteed
            // lock order and we run the real of risk deadlock.
            Message msg = mHandler.obtainMessage(MSG_ALLOWED_MODES_CHANGED, mListener);
            msg.sendToTarget();
        }
    }

    private SparseArray<Vote> getOrCreateVotesByDisplay(int displayId) {
        int index = mVotesByDisplay.indexOfKey(displayId);
        if (mVotesByDisplay.indexOfKey(displayId) >= 0) {
            return mVotesByDisplay.get(displayId);
        } else {
            SparseArray<Vote> votes = new SparseArray<>();
            mVotesByDisplay.put(displayId, votes);
            return votes;
        }
    }

    /**
     * Listens for changes to display mode coordination.
     */
    public interface Listener {
        /**
         * Called when the allowed display modes may have changed.
         */
        void onAllowedDisplayModesChanged();
    }

    private final class DisplayModeDirectorHandler extends Handler {
        DisplayModeDirectorHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ALLOWED_MODES_CHANGED:
                    Listener listener = (Listener) msg.obj;
                    listener.onAllowedDisplayModesChanged();
                    break;

                case MSG_BRIGHTNESS_THRESHOLDS_CHANGED:
                    Pair<int[], int[]> thresholds = (Pair<int[], int[]>) msg.obj;

                    if (thresholds != null) {
                        mBrightnessObserver.onDeviceConfigThresholdsChanged(
                                thresholds.first, thresholds.second);
                    } else {
                        mBrightnessObserver.onDeviceConfigThresholdsChanged(null, null);
                    }
                    break;

                case MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED:
                    Float defaultPeakRefreshRate = (Float) msg.obj;
                    mSettingsObserver.onDeviceConfigDefaultPeakRefreshRateChanged(
                            defaultPeakRefreshRate);
                    break;

                case MSG_REFRESH_RATE_IN_ZONE_CHANGED:
                    int refreshRateInZone = msg.arg1;
                    mBrightnessObserver.onDeviceConfigRefreshRateInZoneChanged(
                            refreshRateInZone);
                    break;
            }
        }
    }

    private static final class Vote {
        // LOW_BRIGHTNESS votes for a single refresh rate like [60,60], [90,90] or null.
        // If the higher voters result is a range, it will fix the rate to a single choice.
        // It's used to avoid rate switch in certain conditions.
        public static final int PRIORITY_LOW_BRIGHTNESS = 0;

        // SETTING_MIN_REFRESH_RATE is used to propose a lower bound of display refresh rate.
        // It votes [MIN_REFRESH_RATE, Float.POSITIVE_INFINITY]
        public static final int PRIORITY_USER_SETTING_MIN_REFRESH_RATE = 1;

        // We split the app request into different priorities in case we can satisfy one desire
        // without the other.

        // Application can specify preferred refresh rate with below attrs.
        // @see android.view.WindowManager.LayoutParams#preferredRefreshRate
        // @see android.view.WindowManager.LayoutParams#preferredDisplayModeId
        // System also forces some apps like blacklisted app to run at a lower refresh rate.
        // @see android.R.array#config_highRefreshRateBlacklist
        public static final int PRIORITY_APP_REQUEST_REFRESH_RATE = 2;
        public static final int PRIORITY_APP_REQUEST_SIZE = 3;

        // SETTING_PEAK_REFRESH_RATE has a high priority and will restrict the bounds of the rest
        // of low priority voters. It votes [0, max(PEAK, MIN)]
        public static final int PRIORITY_USER_SETTING_PEAK_REFRESH_RATE = 4;

        // LOW_POWER_MODE force display to [0, 60HZ] if Settings.Global.LOW_POWER_MODE is on.
        public static final int PRIORITY_LOW_POWER_MODE = 5;

        // Whenever a new priority is added, remember to update MIN_PRIORITY and/or MAX_PRIORITY as
        // appropriate, as well as priorityToString.

        public static final int MIN_PRIORITY = PRIORITY_LOW_BRIGHTNESS;
        public static final int MAX_PRIORITY = PRIORITY_LOW_POWER_MODE;

        /**
         * A value signifying an invalid width or height in a vote.
         */
        public static final int INVALID_SIZE = -1;

        /**
         * The requested width of the display in pixels, or INVALID_SIZE;
         */
        public final int width;
        /**
         * The requested height of the display in pixels, or INVALID_SIZE;
         */
        public final int height;

        /**
         * The lowest desired refresh rate.
         */
        public final float minRefreshRate;
        /**
         * The highest desired refresh rate.
         */
        public final float maxRefreshRate;

        public static Vote forRefreshRates(float minRefreshRate, float maxRefreshRate) {
            return new Vote(INVALID_SIZE, INVALID_SIZE, minRefreshRate, maxRefreshRate);
        }

        public static Vote forSize(int width, int height) {
            return new Vote(width, height, 0f, Float.POSITIVE_INFINITY);
        }

        private Vote(int width, int height,
                float minRefreshRate, float maxRefreshRate) {
            this.width = width;
            this.height = height;
            this.minRefreshRate = minRefreshRate;
            this.maxRefreshRate = maxRefreshRate;
        }

        public static String priorityToString(int priority) {
            switch (priority) {
                case PRIORITY_LOW_BRIGHTNESS:
                    return "PRIORITY_LOW_BRIGHTNESS";
                case PRIORITY_USER_SETTING_MIN_REFRESH_RATE:
                    return "PRIORITY_USER_SETTING_MIN_REFRESH_RATE";
                case PRIORITY_APP_REQUEST_REFRESH_RATE:
                    return "PRIORITY_APP_REQUEST_REFRESH_RATE";
                case PRIORITY_APP_REQUEST_SIZE:
                    return "PRIORITY_APP_REQUEST_SIZE";
                case PRIORITY_USER_SETTING_PEAK_REFRESH_RATE:
                    return "PRIORITY_USER_SETTING_PEAK_REFRESH_RATE";
                case PRIORITY_LOW_POWER_MODE:
                    return "PRIORITY_LOW_POWER_MODE";
                default:
                    return Integer.toString(priority);
            }
        }

        @Override
        public String toString() {
            return "Vote{"
                + "width=" + width
                + ", height=" + height
                + ", minRefreshRate=" + minRefreshRate
                + ", maxRefreshRate=" + maxRefreshRate
                + "}";
        }
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri mPeakRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE);
        private final Uri mMinRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.MIN_REFRESH_RATE);
        private final Uri mLowPowerModeSetting =
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE);

        private final Context mContext;
        private float mDefaultPeakRefreshRate;

        SettingsObserver(@NonNull Context context, @NonNull Handler handler) {
            super(handler);
            mContext = context;
            mDefaultPeakRefreshRate = (float) context.getResources().getInteger(
                    R.integer.config_defaultPeakRefreshRate);
        }

        public void observe() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(mPeakRefreshRateSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mMinRefreshRateSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mLowPowerModeSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);

            Float deviceConfigDefaultPeakRefresh =
                    mDeviceConfigDisplaySettings.getDefaultPeakRefreshRate();
            if (deviceConfigDefaultPeakRefresh != null) {
                mDefaultPeakRefreshRate = deviceConfigDefaultPeakRefresh;
            }

            synchronized (mLock) {
                updateRefreshRateSettingLocked();
                updateLowPowerModeSettingLocked();
            }
        }

        public void onDeviceConfigDefaultPeakRefreshRateChanged(Float defaultPeakRefreshRate) {
            if (defaultPeakRefreshRate == null) {
                defaultPeakRefreshRate = (float) mContext.getResources().getInteger(
                        R.integer.config_defaultPeakRefreshRate);
            }

            if (mDefaultPeakRefreshRate != defaultPeakRefreshRate) {
                synchronized (mLock) {
                    mDefaultPeakRefreshRate = defaultPeakRefreshRate;
                    updateRefreshRateSettingLocked();
                }
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                if (mPeakRefreshRateSetting.equals(uri)
                        || mMinRefreshRateSetting.equals(uri)) {
                    updateRefreshRateSettingLocked();
                } else if (mLowPowerModeSetting.equals(uri)) {
                    updateLowPowerModeSettingLocked();
                }
            }
        }

        private void updateLowPowerModeSettingLocked() {
            boolean inLowPowerMode = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.LOW_POWER_MODE, 0 /*default*/) != 0;
            final Vote vote;
            if (inLowPowerMode) {
                vote = Vote.forRefreshRates(0f, 60f);
            } else {
                vote = null;
            }
            updateVoteLocked(Vote.PRIORITY_LOW_POWER_MODE, vote);
            mBrightnessObserver.onLowPowerModeEnabledLocked(inLowPowerMode);
        }

        private void updateRefreshRateSettingLocked() {
            float minRefreshRate = Settings.System.getFloat(mContext.getContentResolver(),
                    Settings.System.MIN_REFRESH_RATE, 0f);
            float peakRefreshRate = Settings.System.getFloat(mContext.getContentResolver(),
                    Settings.System.PEAK_REFRESH_RATE, mDefaultPeakRefreshRate);

            updateVoteLocked(Vote.PRIORITY_USER_SETTING_PEAK_REFRESH_RATE,
                    Vote.forRefreshRates(0f, Math.max(minRefreshRate, peakRefreshRate)));
            updateVoteLocked(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                    Vote.forRefreshRates(minRefreshRate, Float.POSITIVE_INFINITY));

            mBrightnessObserver.onRefreshRateSettingChangedLocked(minRefreshRate, peakRefreshRate);
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  SettingsObserver");
            pw.println("    mDefaultPeakRefreshRate: " + mDefaultPeakRefreshRate);
        }
    }

    final class AppRequestObserver {
        private SparseArray<Display.Mode> mAppRequestedModeByDisplay;

        AppRequestObserver() {
            mAppRequestedModeByDisplay = new SparseArray<>();
        }

        public void setAppRequestedMode(int displayId, int modeId) {
            synchronized (mLock) {
                setAppRequestedModeLocked(displayId, modeId);
            }
        }

        private void setAppRequestedModeLocked(int displayId, int modeId) {
            final Display.Mode requestedMode = findModeByIdLocked(displayId, modeId);
            if (Objects.equals(requestedMode, mAppRequestedModeByDisplay.get(displayId))) {
                return;
            }

            final Vote refreshRateVote;
            final Vote sizeVote;
            if (requestedMode != null) {
                mAppRequestedModeByDisplay.put(displayId, requestedMode);
                float refreshRate = requestedMode.getRefreshRate();
                refreshRateVote = Vote.forRefreshRates(refreshRate, refreshRate);
                sizeVote = Vote.forSize(requestedMode.getPhysicalWidth(),
                        requestedMode.getPhysicalHeight());
            } else {
                mAppRequestedModeByDisplay.remove(displayId);
                refreshRateVote = null;
                sizeVote = null;
            }

            updateVoteLocked(displayId, Vote.PRIORITY_APP_REQUEST_REFRESH_RATE, refreshRateVote);
            updateVoteLocked(displayId, Vote.PRIORITY_APP_REQUEST_SIZE, sizeVote);
            return;
        }

        private Display.Mode findModeByIdLocked(int displayId, int modeId) {
            Display.Mode[] modes = mSupportedModesByDisplay.get(displayId);
            if (modes == null) {
                return null;
            }
            for (Display.Mode mode : modes) {
                if (mode.getModeId() == modeId) {
                    return mode;
                }
            }
            return null;
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  AppRequestObserver");
            pw.println("    mAppRequestedModeByDisplay:");
            for (int i = 0; i < mAppRequestedModeByDisplay.size(); i++) {
                final int id = mAppRequestedModeByDisplay.keyAt(i);
                final Display.Mode mode = mAppRequestedModeByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + mode);
            }
        }
    }

    private final class DisplayObserver implements DisplayManager.DisplayListener {
        // Note that we can never call into DisplayManager or any of the non-POD classes it
        // returns, while holding mLock since it may call into DMS, which might be simultaneously
        // calling into us already holding its own lock.
        private final Context mContext;
        private final Handler mHandler;

        DisplayObserver(Context context, Handler handler) {
            mContext = context;
            mHandler = handler;
        }

        public void observe() {
            DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            dm.registerDisplayListener(this, mHandler);

            // Populate existing displays
            SparseArray<Display.Mode[]> modes = new SparseArray<>();
            SparseArray<Display.Mode> defaultModes = new SparseArray<>();
            DisplayInfo info = new DisplayInfo();
            Display[] displays = dm.getDisplays();
            for (Display d : displays) {
                final int displayId = d.getDisplayId();
                d.getDisplayInfo(info);
                modes.put(displayId, info.supportedModes);
                defaultModes.put(displayId, info.getDefaultMode());
            }
            synchronized (mLock) {
                final int size = modes.size();
                for (int i = 0; i < size; i++) {
                    mSupportedModesByDisplay.put(modes.keyAt(i), modes.valueAt(i));
                    mDefaultModeByDisplay.put(defaultModes.keyAt(i), defaultModes.valueAt(i));
                }
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            updateDisplayModes(displayId);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                mSupportedModesByDisplay.remove(displayId);
                mDefaultModeByDisplay.remove(displayId);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updateDisplayModes(displayId);
        }

        private void updateDisplayModes(int displayId) {
            Display d = mContext.getSystemService(DisplayManager.class).getDisplay(displayId);
            if (d == null) {
                // We can occasionally get a display added or changed event for a display that was
                // subsequently removed, which means this returns null. Check this case and bail
                // out early; if it gets re-attached we'll eventually get another call back for it.
                return;
            }
            DisplayInfo info = new DisplayInfo();
            d.getDisplayInfo(info);
            boolean changed = false;
            synchronized (mLock) {
                if (!Arrays.equals(mSupportedModesByDisplay.get(displayId), info.supportedModes)) {
                    mSupportedModesByDisplay.put(displayId, info.supportedModes);
                    changed = true;
                }
                if (!Objects.equals(mDefaultModeByDisplay.get(displayId), info.getDefaultMode())) {
                    changed = true;
                    mDefaultModeByDisplay.put(displayId, info.getDefaultMode());
                }
                if (changed) {
                    notifyAllowedModesChangedLocked();
                }
            }
        }
    }

    /**
     * This class manages brightness threshold for switching between 60 hz and higher refresh rate.
     * See more information at the definition of
     * {@link R.array#config_brightnessThresholdsOfPeakRefreshRate} and
     * {@link R.array#config_ambientThresholdsOfPeakRefreshRate}.
     */
    private class BrightnessObserver extends ContentObserver {
        private final Uri mDisplayBrightnessSetting =
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);

        private final static int LIGHT_SENSOR_RATE_MS = 250;
        private int[] mDisplayBrightnessThresholds;
        private int[] mAmbientBrightnessThresholds;
        // valid threshold if any item from the array >= 0
        private boolean mShouldObserveDisplayChange;
        private boolean mShouldObserveAmbientChange;

        private SensorManager mSensorManager;
        private Sensor mLightSensor;
        private LightSensorEventListener mLightSensorListener = new LightSensorEventListener();
        // Take it as low brightness before valid sensor data comes
        private float mAmbientLux = -1.0f;
        private AmbientFilter mAmbientFilter;

        private final Context mContext;
        private final ScreenStateReceiver mScreenStateReceiver;

        // Enable light sensor only when mShouldObserveAmbientChange is true, screen is on, peak
        // refresh rate changeable and low power mode off. After initialization, these states will
        // be updated from the same handler thread.
        private boolean mScreenOn = false;
        private boolean mRefreshRateChangeable = false;
        private boolean mLowPowerModeEnabled = false;

        private int mRefreshRateInZone;

        BrightnessObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
            mScreenStateReceiver = new ScreenStateReceiver(mContext);
            mDisplayBrightnessThresholds = context.getResources().getIntArray(
                    R.array.config_brightnessThresholdsOfPeakRefreshRate);
            mAmbientBrightnessThresholds = context.getResources().getIntArray(
                    R.array.config_ambientThresholdsOfPeakRefreshRate);

            if (mDisplayBrightnessThresholds.length != mAmbientBrightnessThresholds.length) {
                throw new RuntimeException("display brightness threshold array and ambient "
                        + "brightness threshold array have different length");
            }
        }

        public void observe(SensorManager sensorManager) {
            mSensorManager = sensorManager;

            // DeviceConfig is accessible after system ready.
            int[] brightnessThresholds = mDeviceConfigDisplaySettings.getBrightnessThresholds();
            int[] ambientThresholds = mDeviceConfigDisplaySettings.getAmbientThresholds();

            if (brightnessThresholds != null && ambientThresholds != null
                    && brightnessThresholds.length == ambientThresholds.length) {
                mDisplayBrightnessThresholds = brightnessThresholds;
                mAmbientBrightnessThresholds = ambientThresholds;
            }

            mRefreshRateInZone = mDeviceConfigDisplaySettings.getRefreshRateInZone();
            restartObserver();
            mDeviceConfigDisplaySettings.startListening();
        }

        public void onRefreshRateSettingChangedLocked(float min, float max) {
            boolean changeable = (max - min > 1f && max > 60f);
            if (mRefreshRateChangeable != changeable) {
                mRefreshRateChangeable = changeable;
                updateSensorStatus();
                if (!changeable) {
                    // Revoke previous vote from BrightnessObserver
                    updateVoteLocked(Vote.PRIORITY_LOW_BRIGHTNESS, null);
                }
            }
        }

        public void onLowPowerModeEnabledLocked(boolean b) {
            if (mLowPowerModeEnabled != b) {
                mLowPowerModeEnabled = b;
                updateSensorStatus();
            }
        }

        public void onDeviceConfigThresholdsChanged(int[] brightnessThresholds,
                int[] ambientThresholds) {
            if (brightnessThresholds != null && ambientThresholds != null
                    && brightnessThresholds.length == ambientThresholds.length) {
                mDisplayBrightnessThresholds = brightnessThresholds;
                mAmbientBrightnessThresholds = ambientThresholds;
            } else {
                // Invalid or empty. Use device default.
                mDisplayBrightnessThresholds = mContext.getResources().getIntArray(
                        R.array.config_brightnessThresholdsOfPeakRefreshRate);
                mAmbientBrightnessThresholds = mContext.getResources().getIntArray(
                        R.array.config_ambientThresholdsOfPeakRefreshRate);
            }
            restartObserver();
        }

        public void onDeviceConfigRefreshRateInZoneChanged(int refreshRate) {
            if (refreshRate != mRefreshRateInZone) {
                mRefreshRateInZone = refreshRate;
                restartObserver();
            }
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  BrightnessObserver");
            pw.println("    mRefreshRateInZone: " + mRefreshRateInZone);

            for (int d: mDisplayBrightnessThresholds) {
                pw.println("    mDisplayBrightnessThreshold: " + d);
            }

            for (int d: mAmbientBrightnessThresholds) {
                pw.println("    mAmbientBrightnessThreshold: " + d);
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                if (mRefreshRateChangeable) {
                    onBrightnessChangedLocked();
                }
            }
        }

        private void restartObserver() {
            mShouldObserveDisplayChange = checkShouldObserve(mDisplayBrightnessThresholds);
            mShouldObserveAmbientChange = checkShouldObserve(mAmbientBrightnessThresholds);

            final ContentResolver cr = mContext.getContentResolver();
            if (mShouldObserveDisplayChange) {
                // Content Service does not check if an listener has already been registered.
                // To ensure only one listener is registered, force an unregistration first.
                cr.unregisterContentObserver(this);
                cr.registerContentObserver(mDisplayBrightnessSetting,
                        false /*notifyDescendants*/, this, UserHandle.USER_SYSTEM);
            } else {
                cr.unregisterContentObserver(this);
            }

            if (mShouldObserveAmbientChange) {
                Resources resources = mContext.getResources();
                String lightSensorType = resources.getString(
                        com.android.internal.R.string.config_displayLightSensorType);

                Sensor lightSensor = null;
                if (!TextUtils.isEmpty(lightSensorType)) {
                    List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ALL);
                    for (int i = 0; i < sensors.size(); i++) {
                        Sensor sensor = sensors.get(i);
                        if (lightSensorType.equals(sensor.getStringType())) {
                            lightSensor = sensor;
                            break;
                        }
                    }
                }

                if (lightSensor == null) {
                    lightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                }

                if (lightSensor != null) {
                    final Resources res = mContext.getResources();

                    mAmbientFilter = DisplayWhiteBalanceFactory.createBrightnessFilter(res);
                    mLightSensor = lightSensor;

                    // Intent.ACTION_SCREEN_ON is not sticky. Check current screen status.
                    if (mContext.getSystemService(PowerManager.class).isInteractive()) {
                        onScreenOn(true);
                    }
                    mScreenStateReceiver.register();
                }
            } else {
                mAmbientFilter = null;
                mLightSensor = null;
                mScreenStateReceiver.unregister();
            }

            if (mRefreshRateChangeable) {
                updateSensorStatus();
                synchronized (mLock) {
                    onBrightnessChangedLocked();
                }
            }
        }

        /**
         * Checks to see if at least one value is positive, in which case it is necessary to listen
         * to value changes.
         */
        private boolean checkShouldObserve(int[] a) {
            if (mRefreshRateInZone <= 0) {
                return false;
            }

            for (int d: a) {
                if (d >= 0) {
                    return true;
                }
            }

            return false;
        }

        private boolean isInsideZone(int brightness, float lux) {
            for (int i = 0; i < mDisplayBrightnessThresholds.length; i++) {
                int disp = mDisplayBrightnessThresholds[i];
                int ambi = mAmbientBrightnessThresholds[i];

                if (disp >= 0 && ambi >= 0) {
                    if (brightness <= disp && mAmbientLux <= ambi) {
                        return true;
                    }
                } else if (disp >= 0) {
                    if (brightness <= disp) {
                        return true;
                    }
                } else if (ambi >= 0) {
                    if (mAmbientLux <= ambi) {
                        return true;
                    }
                }
            }

            return false;
        }

        private void onBrightnessChangedLocked() {
            int brightness = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);

            Vote vote = null;
            boolean insideZone = isInsideZone(brightness, mAmbientLux);
            if (insideZone) {
                vote = Vote.forRefreshRates(mRefreshRateInZone, mRefreshRateInZone);
            }

            if (DEBUG) {
                Slog.d(TAG, "Display brightness " + brightness + ", ambient lux " +  mAmbientLux +
                        ", Vote " + vote);
            }
            updateVoteLocked(Vote.PRIORITY_LOW_BRIGHTNESS, vote);
        }

        private void onScreenOn(boolean on) {
            // Not check mShouldObserveAmbientChange because Screen status receiver is registered
            // only when it is true.
            if (mScreenOn != on) {
                mScreenOn = on;
                updateSensorStatus();
            }
        }

        private void updateSensorStatus() {
            if (mSensorManager == null || mLightSensorListener == null) {
                return;
            }

            if (mShouldObserveAmbientChange && mScreenOn && !mLowPowerModeEnabled
                    && mRefreshRateChangeable) {
                mSensorManager.registerListener(mLightSensorListener,
                        mLightSensor, LIGHT_SENSOR_RATE_MS * 1000, mHandler);
            } else {
                mLightSensorListener.removeCallbacks();
                mSensorManager.unregisterListener(mLightSensorListener);
            }
        }

        private final class LightSensorEventListener implements SensorEventListener {
            final private static int INJECT_EVENTS_INTERVAL_MS = LIGHT_SENSOR_RATE_MS;
            private float mLastSensorData;

            @Override
            public void onSensorChanged(SensorEvent event) {
                mLastSensorData = event.values[0];
                if (DEBUG) {
                    Slog.d(TAG, "On sensor changed: " + mLastSensorData);
                }

                boolean zoneChanged = isDifferentZone(mLastSensorData, mAmbientLux);
                if (zoneChanged && mLastSensorData < mAmbientLux) {
                    // Easier to see flicker at lower brightness environment. Forget the history to
                    // get immediate response.
                    mAmbientFilter.clear();
                }

                long now = SystemClock.uptimeMillis();
                mAmbientFilter.addValue(now, mLastSensorData);

                mHandler.removeCallbacks(mInjectSensorEventRunnable);
                processSensorData(now);

                if (zoneChanged && mLastSensorData > mAmbientLux) {
                    // Sensor may not report new event if there is no brightness change.
                    // Need to keep querying the temporal filter for the latest estimation,
                    // until enter in higher lux zone or is interrupted by a new sensor event.
                    mHandler.postDelayed(mInjectSensorEventRunnable, INJECT_EVENTS_INTERVAL_MS);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Not used.
            }

            public void removeCallbacks() {
                mHandler.removeCallbacks(mInjectSensorEventRunnable);
            }

            private void processSensorData(long now) {
                mAmbientLux = mAmbientFilter.getEstimate(now);

                synchronized (mLock) {
                    onBrightnessChangedLocked();
                }
            }

            private boolean isDifferentZone(float lux1, float lux2) {
                for (int z = 0; z < mAmbientBrightnessThresholds.length; z++) {
                    final float boundary = mAmbientBrightnessThresholds[z];

                    // Test each boundary. See if the current value and the new value are at
                    // different sides.
                    if ((lux1 <= boundary && lux2 > boundary)
                            || (lux1 > boundary && lux2 <= boundary)) {
                        return true;
                    }
                }

                return false;
            }

            private Runnable mInjectSensorEventRunnable = new Runnable() {
                @Override
                public void run() {
                    long now = SystemClock.uptimeMillis();
                    // No need to really inject the last event into a temporal filter.
                    processSensorData(now);

                    // Inject next event if there is a possible zone change.
                    if (isDifferentZone(mLastSensorData, mAmbientLux)) {
                        mHandler.postDelayed(mInjectSensorEventRunnable, INJECT_EVENTS_INTERVAL_MS);
                    }
                }
            };
        };

        private final class ScreenStateReceiver extends BroadcastReceiver {
            final Context mContext;
            boolean mRegistered;

            public ScreenStateReceiver(Context context) {
                mContext = context;
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                onScreenOn(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
            }

            public void register() {
                if (!mRegistered) {
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(Intent.ACTION_SCREEN_OFF);
                    filter.addAction(Intent.ACTION_SCREEN_ON);
                    filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
                    mContext.registerReceiver(this, filter, null, mHandler);
                    mRegistered = true;
                }
            }

            public void unregister() {
                if (mRegistered) {
                    mContext.unregisterReceiver(this);
                    mRegistered = false;
                }
            }
        }
    }

    private class DeviceConfigDisplaySettings implements DeviceConfig.OnPropertiesChangedListener {
        public DeviceConfigDisplaySettings() {
        }

        public void startListening() {
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    BackgroundThread.getExecutor(), this);
        }

        /*
         * Return null if no such property or wrong format (not comma separated integers).
         */
        public int[] getBrightnessThresholds() {
            return getIntArrayProperty(
                    DisplayManager.DeviceConfig.
                            KEY_PEAK_REFRESH_RATE_DISPLAY_BRIGHTNESS_THRESHOLDS);
        }

        /*
         * Return null if no such property or wrong format (not comma separated integers).
         */
        public int[] getAmbientThresholds() {
            return getIntArrayProperty(
                    DisplayManager.DeviceConfig.
                            KEY_PEAK_REFRESH_RATE_AMBIENT_BRIGHTNESS_THRESHOLDS);
        }

        /*
         * Return null if no such property
         */
        public Float getDefaultPeakRefreshRate() {
            float defaultPeakRefreshRate = DeviceConfig.getFloat(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    DisplayManager.DeviceConfig.KEY_PEAK_REFRESH_RATE_DEFAULT, -1);

            if (defaultPeakRefreshRate == -1) {
                return null;
            }
            return defaultPeakRefreshRate;
        }

        public int getRefreshRateInZone() {
            int defaultRefreshRateInZone = mContext.getResources().getInteger(
                    R.integer.config_defaultRefreshRateInZone);

            int refreshRate = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_ZONE,
                    defaultRefreshRateInZone);

            return refreshRate;
        }

        @Override
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            int[] brightnessThresholds = getBrightnessThresholds();
            int[] ambientThresholds = getAmbientThresholds();
            Float defaultPeakRefreshRate = getDefaultPeakRefreshRate();
            int refreshRateInZone = getRefreshRateInZone();

            mHandler.obtainMessage(MSG_BRIGHTNESS_THRESHOLDS_CHANGED,
                    new Pair<int[], int[]>(brightnessThresholds, ambientThresholds))
                    .sendToTarget();
            mHandler.obtainMessage(MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED,
                    defaultPeakRefreshRate).sendToTarget();
            mHandler.obtainMessage(MSG_REFRESH_RATE_IN_ZONE_CHANGED, refreshRateInZone,
                    0).sendToTarget();
        }

        private int[] getIntArrayProperty(String prop) {
            String strArray = DeviceConfig.getString(DeviceConfig.NAMESPACE_DISPLAY_MANAGER, prop,
                    null);

            if (strArray != null) {
                return parseIntArray(strArray);
            }

            return null;
        }

        private int[] parseIntArray(@NonNull String strArray) {
            String[] items = strArray.split(",");
            int[] array = new int[items.length];

            try {
                for (int i = 0; i < array.length; i++) {
                    array[i] = Integer.parseInt(items[i]);
                }
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Incorrect format for array: '" + strArray + "'", e);
                array = null;
            }

            return array;
        }
    }

}
