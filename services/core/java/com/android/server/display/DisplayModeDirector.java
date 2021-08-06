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

import static android.hardware.display.DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.RefreshRateLimitation;
import android.hardware.display.DisplayManagerInternal.RefreshRateRange;
import android.hardware.fingerprint.IUdfpsHbmListener;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.display.utils.AmbientFilter;
import com.android.server.display.utils.AmbientFilterFactory;
import com.android.server.sensors.SensorManagerInternal;
import com.android.server.sensors.SensorManagerInternal.ProximityActiveListener;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.utils.DeviceConfigInterface;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;


/**
 * The DisplayModeDirector is responsible for determining what modes are allowed to be automatically
 * picked by the system based on system-wide and display-specific configuration.
 */
public class DisplayModeDirector {
    private static final String TAG = "DisplayModeDirector";
    private boolean mLoggingEnabled;

    private static final int MSG_REFRESH_RATE_RANGE_CHANGED = 1;
    private static final int MSG_LOW_BRIGHTNESS_THRESHOLDS_CHANGED = 2;
    private static final int MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED = 3;
    private static final int MSG_REFRESH_RATE_IN_LOW_ZONE_CHANGED = 4;
    private static final int MSG_REFRESH_RATE_IN_HIGH_ZONE_CHANGED = 5;
    private static final int MSG_HIGH_BRIGHTNESS_THRESHOLDS_CHANGED = 6;

    // Special ID used to indicate that given vote is to be applied globally, rather than to a
    // specific display.
    private static final int GLOBAL_ID = -1;

    private static final int INVALID_DISPLAY_MODE_ID = -1;

    private static final float FLOAT_TOLERANCE = RefreshRateRange.FLOAT_TOLERANCE;

    private final Object mLock = new Object();
    private final Context mContext;

    private final DisplayModeDirectorHandler mHandler;
    private final Injector mInjector;

    private final AppRequestObserver mAppRequestObserver;
    private final SettingsObserver mSettingsObserver;
    private final DisplayObserver mDisplayObserver;
    private final UdfpsObserver mUdfpsObserver;
    private final SensorObserver mSensorObserver;
    private final HbmObserver mHbmObserver;
    private final DeviceConfigInterface mDeviceConfig;
    private final DeviceConfigDisplaySettings mDeviceConfigDisplaySettings;

    // A map from the display ID to the collection of votes and their priority. The latter takes
    // the form of another map from the priority to the vote itself so that each priority is
    // guaranteed to have exactly one vote, which is also easily and efficiently replaceable.
    private SparseArray<SparseArray<Vote>> mVotesByDisplay;
    // A map from the display ID to the supported modes on that display.
    private SparseArray<Display.Mode[]> mSupportedModesByDisplay;
    // A map from the display ID to the default mode of that display.
    private SparseArray<Display.Mode> mDefaultModeByDisplay;

    private BrightnessObserver mBrightnessObserver;

    private DesiredDisplayModeSpecsListener mDesiredDisplayModeSpecsListener;

    private boolean mAlwaysRespectAppRequest;

    /**
     * The allowed refresh rate switching type. This is used by SurfaceFlinger.
     */
    @DisplayManager.SwitchingType
    private int mModeSwitchingType = DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS;

    public DisplayModeDirector(@NonNull Context context, @NonNull Handler handler) {
        this(context, handler, new RealInjector(context));
    }

    public DisplayModeDirector(@NonNull Context context, @NonNull Handler handler,
            @NonNull Injector injector) {
        mContext = context;
        mHandler = new DisplayModeDirectorHandler(handler.getLooper());
        mInjector = injector;
        mVotesByDisplay = new SparseArray<>();
        mSupportedModesByDisplay = new SparseArray<>();
        mDefaultModeByDisplay = new SparseArray<>();
        mAppRequestObserver = new AppRequestObserver();
        mSettingsObserver = new SettingsObserver(context, handler);
        mDisplayObserver = new DisplayObserver(context, handler);
        mBrightnessObserver = new BrightnessObserver(context, handler);
        mUdfpsObserver = new UdfpsObserver();
        final BallotBox ballotBox = (displayId, priority, vote) -> {
            synchronized (mLock) {
                updateVoteLocked(displayId, priority, vote);
            }
        };
        mSensorObserver = new SensorObserver(context, ballotBox, injector);
        mHbmObserver = new HbmObserver(injector, ballotBox, BackgroundThread.getHandler());
        mDeviceConfigDisplaySettings = new DeviceConfigDisplaySettings();
        mDeviceConfig = injector.getDeviceConfig();
        mAlwaysRespectAppRequest = false;
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
        mBrightnessObserver.observe(sensorManager);
        mSensorObserver.observe();
        mHbmObserver.observe();
        synchronized (mLock) {
            // We may have a listener already registered before the call to start, so go ahead and
            // notify them to pick up our newly initialized state.
            notifyDesiredDisplayModeSpecsChangedLocked();
        }
    }

    /**
     * Same as {@link #start(SensorManager)}, but for observers that need to be delayed even more,
     * for example until SystemUI is ready.
     */
    public void onBootCompleted() {
        // UDFPS observer registers a listener with SystemUI which might not be ready until the
        // system is fully booted.
        mUdfpsObserver.observe();
    }

    public void setLoggingEnabled(boolean loggingEnabled) {
        if (mLoggingEnabled == loggingEnabled) {
            return;
        }
        mLoggingEnabled = loggingEnabled;
        mBrightnessObserver.setLoggingEnabled(loggingEnabled);
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

    private static final class VoteSummary {
        public float minRefreshRate;
        public float maxRefreshRate;
        public int width;
        public int height;
        public boolean disableRefreshRateSwitching;
        public float baseModeRefreshRate;

        VoteSummary() {
            reset();
        }

        public void reset() {
            minRefreshRate = 0f;
            maxRefreshRate = Float.POSITIVE_INFINITY;
            width = Vote.INVALID_SIZE;
            height = Vote.INVALID_SIZE;
            disableRefreshRateSwitching = false;
            baseModeRefreshRate = 0f;
        }
    }

    // VoteSummary is returned as an output param to cut down a bit on the number of temporary
    // objects.
    private void summarizeVotes(
            SparseArray<Vote> votes,
            int lowestConsideredPriority,
            int highestConsideredPriority,
            /*out*/ VoteSummary summary) {
        summary.reset();
        for (int priority = highestConsideredPriority;
                priority >= lowestConsideredPriority;
                priority--) {
            Vote vote = votes.get(priority);
            if (vote == null) {
                continue;
            }
            // For refresh rates, just use the tightest bounds of all the votes
            summary.minRefreshRate = Math.max(summary.minRefreshRate, vote.refreshRateRange.min);
            summary.maxRefreshRate = Math.min(summary.maxRefreshRate, vote.refreshRateRange.max);
            // For display size, disable refresh rate switching and base mode refresh rate use only
            // the first vote we come across (i.e. the highest priority vote that includes the
            // attribute).
            if (summary.height == Vote.INVALID_SIZE && summary.width == Vote.INVALID_SIZE
                    && vote.height > 0 && vote.width > 0) {
                summary.width = vote.width;
                summary.height = vote.height;
            }
            if (!summary.disableRefreshRateSwitching && vote.disableRefreshRateSwitching) {
                summary.disableRefreshRateSwitching = true;
            }
            if (summary.baseModeRefreshRate == 0f && vote.baseModeRefreshRate > 0f) {
                summary.baseModeRefreshRate = vote.baseModeRefreshRate;
            }
        }
    }

    /**
     * Calculates the refresh rate ranges and display modes that the system is allowed to freely
     * switch between based on global and display-specific constraints.
     *
     * @param displayId The display to query for.
     * @return The ID of the default mode the system should use, and the refresh rate range the
     * system is allowed to switch between.
     */
    @NonNull
    public DesiredDisplayModeSpecs getDesiredDisplayModeSpecs(int displayId) {
        synchronized (mLock) {
            SparseArray<Vote> votes = getVotesLocked(displayId);
            Display.Mode[] modes = mSupportedModesByDisplay.get(displayId);
            Display.Mode defaultMode = mDefaultModeByDisplay.get(displayId);
            if (modes == null || defaultMode == null) {
                Slog.e(TAG,
                        "Asked about unknown display, returning empty display mode specs!"
                                + "(id=" + displayId + ")");
                return new DesiredDisplayModeSpecs();
            }

            ArrayList<Display.Mode> availableModes = new ArrayList<>();
            availableModes.add(defaultMode);
            VoteSummary primarySummary = new VoteSummary();
            int lowestConsideredPriority = Vote.MIN_PRIORITY;
            int highestConsideredPriority = Vote.MAX_PRIORITY;

            if (mAlwaysRespectAppRequest) {
                lowestConsideredPriority = Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE;
                highestConsideredPriority = Vote.PRIORITY_APP_REQUEST_SIZE;
            }

            // We try to find a range of priorities which define a non-empty set of allowed display
            // modes. Each time we fail we increase the lowest priority.
            while (lowestConsideredPriority <= highestConsideredPriority) {
                summarizeVotes(
                        votes, lowestConsideredPriority, highestConsideredPriority, primarySummary);

                // If we don't have anything specifying the width / height of the display, just use
                // the default width and height. We don't want these switching out from underneath
                // us since it's a pretty disruptive behavior.
                if (primarySummary.height == Vote.INVALID_SIZE
                        || primarySummary.width == Vote.INVALID_SIZE) {
                    primarySummary.width = defaultMode.getPhysicalWidth();
                    primarySummary.height = defaultMode.getPhysicalHeight();
                }

                availableModes = filterModes(modes, primarySummary);
                if (!availableModes.isEmpty()) {
                    if (mLoggingEnabled) {
                        Slog.w(TAG, "Found available modes=" + availableModes
                                + " with lowest priority considered "
                                + Vote.priorityToString(lowestConsideredPriority)
                                + " and constraints: "
                                + "width=" + primarySummary.width
                                + ", height=" + primarySummary.height
                                + ", minRefreshRate=" + primarySummary.minRefreshRate
                                + ", maxRefreshRate=" + primarySummary.maxRefreshRate
                                + ", disableRefreshRateSwitching="
                                + primarySummary.disableRefreshRateSwitching
                                + ", baseModeRefreshRate=" + primarySummary.baseModeRefreshRate);
                    }
                    break;
                }

                if (mLoggingEnabled) {
                    Slog.w(TAG, "Couldn't find available modes with lowest priority set to "
                            + Vote.priorityToString(lowestConsideredPriority)
                            + " and with the following constraints: "
                            + "width=" + primarySummary.width
                            + ", height=" + primarySummary.height
                            + ", minRefreshRate=" + primarySummary.minRefreshRate
                            + ", maxRefreshRate=" + primarySummary.maxRefreshRate
                            + ", disableRefreshRateSwitching="
                            + primarySummary.disableRefreshRateSwitching
                            + ", baseModeRefreshRate=" + primarySummary.baseModeRefreshRate);
                }

                // If we haven't found anything with the current set of votes, drop the
                // current lowest priority vote.
                lowestConsideredPriority++;
            }

            VoteSummary appRequestSummary = new VoteSummary();
            summarizeVotes(
                    votes,
                    Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF,
                    Vote.MAX_PRIORITY,
                    appRequestSummary);
            appRequestSummary.minRefreshRate =
                    Math.min(appRequestSummary.minRefreshRate, primarySummary.minRefreshRate);
            appRequestSummary.maxRefreshRate =
                    Math.max(appRequestSummary.maxRefreshRate, primarySummary.maxRefreshRate);
            if (mLoggingEnabled) {
                Slog.i(TAG,
                        String.format("App request range: [%.0f %.0f]",
                                appRequestSummary.minRefreshRate,
                                appRequestSummary.maxRefreshRate));
            }

            // Select the base mode id based on the base mode refresh rate, if available, since this
            // will be the mode id the app voted for.
            Display.Mode baseMode = null;
            for (Display.Mode availableMode : availableModes) {
                if (primarySummary.baseModeRefreshRate
                        >= availableMode.getRefreshRate() - FLOAT_TOLERANCE
                        && primarySummary.baseModeRefreshRate
                        <= availableMode.getRefreshRate() + FLOAT_TOLERANCE) {
                    baseMode = availableMode;
                }
            }

            // Select the default mode if available. This is important because SurfaceFlinger
            // can do only seamless switches by default. Some devices (e.g. TV) don't support
            // seamless switching so the mode we select here won't be changed.
            if (baseMode == null) {
                for (Display.Mode availableMode : availableModes) {
                    if (availableMode.getModeId() == defaultMode.getModeId()) {
                        baseMode = defaultMode;
                        break;
                    }
                }
            }

            // If the application requests a display mode by setting
            // LayoutParams.preferredDisplayModeId, it will be the only available mode and it'll
            // be stored as baseModeId.
            if (baseMode == null && !availableModes.isEmpty()) {
                baseMode = availableModes.get(0);
            }

            if (baseMode == null) {
                Slog.w(TAG, "Can't find a set of allowed modes which satisfies the votes. Falling"
                        + " back to the default mode. Display = " + displayId + ", votes = " + votes
                        + ", supported modes = " + Arrays.toString(modes));

                float fps = defaultMode.getRefreshRate();
                return new DesiredDisplayModeSpecs(defaultMode.getModeId(),
                        /*allowGroupSwitching */ false,
                        new RefreshRateRange(fps, fps),
                        new RefreshRateRange(fps, fps));
            }

            if (mModeSwitchingType == DisplayManager.SWITCHING_TYPE_NONE
                    || primarySummary.disableRefreshRateSwitching) {
                float fps = baseMode.getRefreshRate();
                primarySummary.minRefreshRate = primarySummary.maxRefreshRate = fps;
                if (mModeSwitchingType == DisplayManager.SWITCHING_TYPE_NONE) {
                    appRequestSummary.minRefreshRate = appRequestSummary.maxRefreshRate = fps;
                }
            }

            boolean allowGroupSwitching =
                    mModeSwitchingType == DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS;

            return new DesiredDisplayModeSpecs(baseMode.getModeId(),
                    allowGroupSwitching,
                    new RefreshRateRange(
                            primarySummary.minRefreshRate, primarySummary.maxRefreshRate),
                    new RefreshRateRange(
                            appRequestSummary.minRefreshRate, appRequestSummary.maxRefreshRate));
        }
    }

    private ArrayList<Display.Mode> filterModes(Display.Mode[] supportedModes,
            VoteSummary summary) {
        ArrayList<Display.Mode> availableModes = new ArrayList<>();
        boolean missingBaseModeRefreshRate = summary.baseModeRefreshRate > 0f;
        for (Display.Mode mode : supportedModes) {
            if (mode.getPhysicalWidth() != summary.width
                    || mode.getPhysicalHeight() != summary.height) {
                if (mLoggingEnabled) {
                    Slog.w(TAG, "Discarding mode " + mode.getModeId() + ", wrong size"
                            + ": desiredWidth=" + summary.width
                            + ": desiredHeight=" + summary.height
                            + ": actualWidth=" + mode.getPhysicalWidth()
                            + ": actualHeight=" + mode.getPhysicalHeight());
                }
                continue;
            }
            final float refreshRate = mode.getRefreshRate();
            // Some refresh rates are calculated based on frame timings, so they aren't *exactly*
            // equal to expected refresh rate. Given that, we apply a bit of tolerance to this
            // comparison.
            if (refreshRate < (summary.minRefreshRate - FLOAT_TOLERANCE)
                    || refreshRate > (summary.maxRefreshRate + FLOAT_TOLERANCE)) {
                if (mLoggingEnabled) {
                    Slog.w(TAG, "Discarding mode " + mode.getModeId()
                            + ", outside refresh rate bounds"
                            + ": minRefreshRate=" + summary.minRefreshRate
                            + ", maxRefreshRate=" + summary.maxRefreshRate
                            + ", modeRefreshRate=" + refreshRate);
                }
                continue;
            }
            availableModes.add(mode);
            if (mode.getRefreshRate() >= summary.baseModeRefreshRate - FLOAT_TOLERANCE
                    && mode.getRefreshRate() <= summary.baseModeRefreshRate + FLOAT_TOLERANCE) {
                missingBaseModeRefreshRate = false;
            }
        }
        if (missingBaseModeRefreshRate) {
            return new ArrayList<>();
        }

        return availableModes;
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
     * Sets the desiredDisplayModeSpecsListener for changes to display mode and refresh rate ranges.
     */
    public void setDesiredDisplayModeSpecsListener(
            @Nullable DesiredDisplayModeSpecsListener desiredDisplayModeSpecsListener) {
        synchronized (mLock) {
            mDesiredDisplayModeSpecsListener = desiredDisplayModeSpecsListener;
        }
    }

    /**
     * When enabled the app requested display mode is always selected and all
     * other votes will be ignored. This is used for testing purposes.
     */
    public void setShouldAlwaysRespectAppRequestedMode(boolean enabled) {
        synchronized (mLock) {
            mAlwaysRespectAppRequest = enabled;
        }
    }

    /**
     * Returns whether we are running in a mode which always selects the app requested display mode
     * and ignores user settings and policies for low brightness, low battery etc.
     */
    public boolean shouldAlwaysRespectAppRequestedMode() {
        synchronized (mLock) {
            return mAlwaysRespectAppRequest;
        }
    }

    /**
     * Sets the display mode switching type.
     * @param newType
     */
    public void setModeSwitchingType(@DisplayManager.SwitchingType int newType) {
        synchronized (mLock) {
            if (newType != mModeSwitchingType) {
                mModeSwitchingType = newType;
                notifyDesiredDisplayModeSpecsChangedLocked();
            }
        }
    }

    /**
     * Returns the display mode switching type.
     */
    @DisplayManager.SwitchingType
    public int getModeSwitchingType() {
        synchronized (mLock) {
            return mModeSwitchingType;
        }
    }

    /**
     * Retrieve the Vote for the given display and priority. Intended only for testing purposes.
     *
     * @param displayId the display to query for
     * @param priority the priority of the vote to return
     * @return the vote corresponding to the given {@code displayId} and {@code priority},
     *         or {@code null} if there isn't one
     */
    @VisibleForTesting
    @Nullable
    Vote getVote(int displayId, int priority) {
        synchronized (mLock) {
            SparseArray<Vote> votes = getVotesLocked(displayId);
            return votes.get(priority);
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
            pw.println("  mModeSwitchingType: " + switchingTypeToString(mModeSwitchingType));
            pw.println("  mAlwaysRespectAppRequest: " + mAlwaysRespectAppRequest);
            mSettingsObserver.dumpLocked(pw);
            mAppRequestObserver.dumpLocked(pw);
            mBrightnessObserver.dumpLocked(pw);
            mUdfpsObserver.dumpLocked(pw);
            mSensorObserver.dumpLocked(pw);
            mHbmObserver.dumpLocked(pw);
        }
    }

    private void updateVoteLocked(int priority, Vote vote) {
        updateVoteLocked(GLOBAL_ID, priority, vote);
    }

    private void updateVoteLocked(int displayId, int priority, Vote vote) {
        if (mLoggingEnabled) {
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

        if (vote != null) {
            votes.put(priority, vote);
        } else {
            votes.remove(priority);
        }

        if (votes.size() == 0) {
            if (mLoggingEnabled) {
                Slog.i(TAG, "No votes left for display " + displayId + ", removing.");
            }
            mVotesByDisplay.remove(displayId);
        }

        notifyDesiredDisplayModeSpecsChangedLocked();
    }

    private void notifyDesiredDisplayModeSpecsChangedLocked() {
        if (mDesiredDisplayModeSpecsListener != null
                && !mHandler.hasMessages(MSG_REFRESH_RATE_RANGE_CHANGED)) {
            // We need to post this to a handler to avoid calling out while holding the lock
            // since we know there are things that both listen for changes as well as provide
            // information. If we did call out while holding the lock, then there's no
            // guaranteed lock order and we run the real of risk deadlock.
            Message msg = mHandler.obtainMessage(
                    MSG_REFRESH_RATE_RANGE_CHANGED, mDesiredDisplayModeSpecsListener);
            msg.sendToTarget();
        }
    }

    private SparseArray<Vote> getOrCreateVotesByDisplay(int displayId) {
        if (mVotesByDisplay.indexOfKey(displayId) >= 0) {
            return mVotesByDisplay.get(displayId);
        } else {
            SparseArray<Vote> votes = new SparseArray<>();
            mVotesByDisplay.put(displayId, votes);
            return votes;
        }
    }

    private static String switchingTypeToString(@DisplayManager.SwitchingType int type) {
        switch (type) {
            case DisplayManager.SWITCHING_TYPE_NONE:
                return "SWITCHING_TYPE_NONE";
            case DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS:
                return "SWITCHING_TYPE_WITHIN_GROUPS";
            case DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS:
                return "SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS";
            default:
                return "Unknown SwitchingType " + type;
        }
    }

    @VisibleForTesting
    void injectSupportedModesByDisplay(SparseArray<Display.Mode[]> supportedModesByDisplay) {
        mSupportedModesByDisplay = supportedModesByDisplay;
    }

    @VisibleForTesting
    void injectDefaultModeByDisplay(SparseArray<Display.Mode> defaultModeByDisplay) {
        mDefaultModeByDisplay = defaultModeByDisplay;
    }

    @VisibleForTesting
    void injectVotesByDisplay(SparseArray<SparseArray<Vote>> votesByDisplay) {
        mVotesByDisplay = votesByDisplay;
    }

    @VisibleForTesting
    void injectBrightnessObserver(BrightnessObserver brightnessObserver) {
        mBrightnessObserver = brightnessObserver;
    }

    @VisibleForTesting
    BrightnessObserver getBrightnessObserver() {
        return mBrightnessObserver;
    }

    @VisibleForTesting
    SettingsObserver getSettingsObserver() {
        return mSettingsObserver;
    }

    @VisibleForTesting
    UdfpsObserver getUdpfsObserver() {
        return mUdfpsObserver;
    }


    @VisibleForTesting
    DesiredDisplayModeSpecs getDesiredDisplayModeSpecsWithInjectedFpsSettings(
            float minRefreshRate, float peakRefreshRate, float defaultRefreshRate) {
        synchronized (mLock) {
            mSettingsObserver.updateRefreshRateSettingLocked(
                    minRefreshRate, peakRefreshRate, defaultRefreshRate);
            return getDesiredDisplayModeSpecs(Display.DEFAULT_DISPLAY);
        }
    }

    /**
     * Listens for changes refresh rate coordination.
     */
    public interface DesiredDisplayModeSpecsListener {
        /**
         * Called when the refresh rate range may have changed.
         */
        void onDesiredDisplayModeSpecsChanged();
    }

    private final class DisplayModeDirectorHandler extends Handler {
        DisplayModeDirectorHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOW_BRIGHTNESS_THRESHOLDS_CHANGED: {
                    Pair<int[], int[]> thresholds = (Pair<int[], int[]>) msg.obj;
                    mBrightnessObserver.onDeviceConfigLowBrightnessThresholdsChanged(
                            thresholds.first, thresholds.second);
                    break;
                }

                case MSG_REFRESH_RATE_IN_LOW_ZONE_CHANGED: {
                    int refreshRateInZone = msg.arg1;
                    mBrightnessObserver.onDeviceConfigRefreshRateInLowZoneChanged(
                            refreshRateInZone);
                    break;
                }

                case MSG_HIGH_BRIGHTNESS_THRESHOLDS_CHANGED: {
                    Pair<int[], int[]> thresholds = (Pair<int[], int[]>) msg.obj;

                    mBrightnessObserver.onDeviceConfigHighBrightnessThresholdsChanged(
                            thresholds.first, thresholds.second);

                    break;
                }

                case MSG_REFRESH_RATE_IN_HIGH_ZONE_CHANGED: {
                    int refreshRateInZone = msg.arg1;
                    mBrightnessObserver.onDeviceConfigRefreshRateInHighZoneChanged(
                            refreshRateInZone);
                    break;
                }

                case MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED:
                    Float defaultPeakRefreshRate = (Float) msg.obj;
                    mSettingsObserver.onDeviceConfigDefaultPeakRefreshRateChanged(
                            defaultPeakRefreshRate);
                    break;

                case MSG_REFRESH_RATE_RANGE_CHANGED:
                    DesiredDisplayModeSpecsListener desiredDisplayModeSpecsListener =
                            (DesiredDisplayModeSpecsListener) msg.obj;
                    desiredDisplayModeSpecsListener.onDesiredDisplayModeSpecsChanged();
                    break;
            }
        }
    }

    /**
     * Information about the desired display mode to be set by the system. Includes the base
     * mode ID and the primary and app request refresh rate ranges.
     *
     * We have this class in addition to SurfaceControl.DesiredDisplayConfigSpecs to make clear the
     * distinction between the config ID / physical index that
     * SurfaceControl.DesiredDisplayConfigSpecs uses, and the mode ID used here.
     */
    public static final class DesiredDisplayModeSpecs {

        /**
         * Base mode ID. This is what system defaults to for all other settings, or
         * if the refresh rate range is not available.
         */
        public int baseModeId;

        /**
         * If true this will allow switching between modes in different display configuration
         * groups. This way the user may see visual interruptions when the display mode changes.
         */
        public boolean allowGroupSwitching;

        /**
         * The primary refresh rate range.
         */
        public final RefreshRateRange primaryRefreshRateRange;
        /**
         * The app request refresh rate range. Lower priority considerations won't be included in
         * this range, allowing SurfaceFlinger to consider additional refresh rates for apps that
         * call setFrameRate(). This range will be greater than or equal to the primary refresh rate
         * range, never smaller.
         */
        public final RefreshRateRange appRequestRefreshRateRange;

        public DesiredDisplayModeSpecs() {
            primaryRefreshRateRange = new RefreshRateRange();
            appRequestRefreshRateRange = new RefreshRateRange();
        }

        public DesiredDisplayModeSpecs(int baseModeId,
                boolean allowGroupSwitching,
                @NonNull RefreshRateRange primaryRefreshRateRange,
                @NonNull RefreshRateRange appRequestRefreshRateRange) {
            this.baseModeId = baseModeId;
            this.allowGroupSwitching = allowGroupSwitching;
            this.primaryRefreshRateRange = primaryRefreshRateRange;
            this.appRequestRefreshRateRange = appRequestRefreshRateRange;
        }

        /**
         * Returns a string representation of the object.
         */
        @Override
        public String toString() {
            return String.format("baseModeId=%d allowGroupSwitching=%b"
                            + " primaryRefreshRateRange=[%.0f %.0f]"
                            + " appRequestRefreshRateRange=[%.0f %.0f]",
                    baseModeId, allowGroupSwitching, primaryRefreshRateRange.min,
                    primaryRefreshRateRange.max, appRequestRefreshRateRange.min,
                    appRequestRefreshRateRange.max);
        }
        /**
         * Checks whether the two objects have the same values.
         */
        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }

            if (!(other instanceof DesiredDisplayModeSpecs)) {
                return false;
            }

            DesiredDisplayModeSpecs desiredDisplayModeSpecs = (DesiredDisplayModeSpecs) other;

            if (baseModeId != desiredDisplayModeSpecs.baseModeId) {
                return false;
            }
            if (allowGroupSwitching != desiredDisplayModeSpecs.allowGroupSwitching) {
                return false;
            }
            if (!primaryRefreshRateRange.equals(desiredDisplayModeSpecs.primaryRefreshRateRange)) {
                return false;
            }
            if (!appRequestRefreshRateRange.equals(
                        desiredDisplayModeSpecs.appRequestRefreshRateRange)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseModeId, allowGroupSwitching, primaryRefreshRateRange,
                    appRequestRefreshRateRange);
        }

        /**
         * Copy values from the other object.
         */
        public void copyFrom(DesiredDisplayModeSpecs other) {
            baseModeId = other.baseModeId;
            allowGroupSwitching = other.allowGroupSwitching;
            primaryRefreshRateRange.min = other.primaryRefreshRateRange.min;
            primaryRefreshRateRange.max = other.primaryRefreshRateRange.max;
            appRequestRefreshRateRange.min = other.appRequestRefreshRateRange.min;
            appRequestRefreshRateRange.max = other.appRequestRefreshRateRange.max;
        }
    }

    @VisibleForTesting
    static final class Vote {
        // DEFAULT_FRAME_RATE votes for [0, DEFAULT]. As the lowest priority vote, it's overridden
        // by all other considerations. It acts to set a default frame rate for a device.
        public static final int PRIORITY_DEFAULT_REFRESH_RATE = 0;

        // PRIORITY_FLICKER_REFRESH_RATE votes for a single refresh rate like [60,60], [90,90] or
        // null. It is used to set a preferred refresh rate value in case the higher priority votes
        // result is a range.
        public static final int PRIORITY_FLICKER_REFRESH_RATE = 1;

        // SETTING_MIN_REFRESH_RATE is used to propose a lower bound of display refresh rate.
        // It votes [MIN_REFRESH_RATE, Float.POSITIVE_INFINITY]
        public static final int PRIORITY_USER_SETTING_MIN_REFRESH_RATE = 2;

        // APP_REQUEST_REFRESH_RATE_RANGE is used to for internal apps to limit the refresh
        // rate in certain cases, mostly to preserve power.
        // @see android.view.WindowManager.LayoutParams#preferredMinRefreshRate
        // @see android.view.WindowManager.LayoutParams#preferredMaxRefreshRate
        // It votes to [preferredMinRefreshRate, preferredMaxRefreshRate].
        public static final int PRIORITY_APP_REQUEST_REFRESH_RATE_RANGE = 3;

        // We split the app request into different priorities in case we can satisfy one desire
        // without the other.

        // Application can specify preferred refresh rate with below attrs.
        // @see android.view.WindowManager.LayoutParams#preferredRefreshRate
        // @see android.view.WindowManager.LayoutParams#preferredDisplayModeId
        // These translates into votes for the base mode refresh rate and resolution to be
        // used by SurfaceFlinger as the policy of choosing the display mode. The system also
        // forces some apps like denylisted app to run at a lower refresh rate.
        // @see android.R.array#config_highRefreshRateBlacklist
        // The preferred refresh rate is set on the main surface of the app outside of
        // DisplayModeDirector.
        // @see com.android.server.wm.WindowState#updateFrameRateSelectionPriorityIfNeeded
        public static final int PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE = 4;
        public static final int PRIORITY_APP_REQUEST_SIZE = 5;

        // SETTING_PEAK_REFRESH_RATE has a high priority and will restrict the bounds of the rest
        // of low priority voters. It votes [0, max(PEAK, MIN)]
        public static final int PRIORITY_USER_SETTING_PEAK_REFRESH_RATE = 6;

        // LOW_POWER_MODE force display to [0, 60HZ] if Settings.Global.LOW_POWER_MODE is on.
        public static final int PRIORITY_LOW_POWER_MODE = 7;

        // PRIORITY_FLICKER_REFRESH_RATE_SWITCH votes for disabling refresh rate switching. If the
        // higher priority voters' result is a range, it will fix the rate to a single choice.
        // It's used to avoid refresh rate switches in certain conditions which may result in the
        // user seeing the display flickering when the switches occur.
        public static final int PRIORITY_FLICKER_REFRESH_RATE_SWITCH = 8;

        // High-brightness-mode may need a specific range of refresh-rates to function properly.
        public static final int PRIORITY_HIGH_BRIGHTNESS_MODE = 9;

        // The proximity sensor needs the refresh rate to be locked in order to function, so this is
        // set to a high priority.
        public static final int PRIORITY_PROXIMITY = 10;

        // The Under-Display Fingerprint Sensor (UDFPS) needs the refresh rate to be locked in order
        // to function, so this needs to be the highest priority of all votes.
        public static final int PRIORITY_UDFPS = 11;

        // Whenever a new priority is added, remember to update MIN_PRIORITY, MAX_PRIORITY, and
        // APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF, as well as priorityToString.

        public static final int MIN_PRIORITY = PRIORITY_DEFAULT_REFRESH_RATE;
        public static final int MAX_PRIORITY = PRIORITY_UDFPS;

        // The cutoff for the app request refresh rate range. Votes with priorities lower than this
        // value will not be considered when constructing the app request refresh rate range.
        public static final int APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF =
                PRIORITY_APP_REQUEST_REFRESH_RATE_RANGE;

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
         * Information about the min and max refresh rate DM would like to set the display to.
         */
        public final RefreshRateRange refreshRateRange;

        /**
         * Whether refresh rate switching should be disabled (i.e. the refresh rate range is
         * a single value).
         */
        public final boolean disableRefreshRateSwitching;

        /**
         * The base mode refresh rate to be used for this display. This would be used when deciding
         * the base mode id.
         */
        public final float baseModeRefreshRate;

        public static Vote forRefreshRates(float minRefreshRate, float maxRefreshRate) {
            return new Vote(INVALID_SIZE, INVALID_SIZE, minRefreshRate, maxRefreshRate,
                    minRefreshRate == maxRefreshRate, 0f);
        }

        public static Vote forSize(int width, int height) {
            return new Vote(width, height, 0f, Float.POSITIVE_INFINITY, false,
                    0f);
        }

        public static Vote forDisableRefreshRateSwitching() {
            return new Vote(INVALID_SIZE, INVALID_SIZE, 0f, Float.POSITIVE_INFINITY, true,
                    0f);
        }

        public static Vote forBaseModeRefreshRate(float baseModeRefreshRate) {
            return new Vote(INVALID_SIZE, INVALID_SIZE, 0f, Float.POSITIVE_INFINITY, false,
                    baseModeRefreshRate);
        }

        private Vote(int width, int height,
                float minRefreshRate, float maxRefreshRate,
                boolean disableRefreshRateSwitching,
                float baseModeRefreshRate) {
            this.width = width;
            this.height = height;
            this.refreshRateRange =
                    new RefreshRateRange(minRefreshRate, maxRefreshRate);
            this.disableRefreshRateSwitching = disableRefreshRateSwitching;
            this.baseModeRefreshRate = baseModeRefreshRate;
        }

        public static String priorityToString(int priority) {
            switch (priority) {
                case PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE:
                    return "PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE";
                case PRIORITY_APP_REQUEST_REFRESH_RATE_RANGE:
                    return "PRIORITY_APP_REQUEST_REFRESH_RATE_RANGE";
                case PRIORITY_APP_REQUEST_SIZE:
                    return "PRIORITY_APP_REQUEST_SIZE";
                case PRIORITY_DEFAULT_REFRESH_RATE:
                    return "PRIORITY_DEFAULT_REFRESH_RATE";
                case PRIORITY_FLICKER_REFRESH_RATE:
                    return "PRIORITY_FLICKER_REFRESH_RATE";
                case PRIORITY_FLICKER_REFRESH_RATE_SWITCH:
                    return "PRIORITY_FLICKER_REFRESH_RATE_SWITCH";
                case PRIORITY_HIGH_BRIGHTNESS_MODE:
                    return "PRIORITY_HIGH_BRIGHTNESS_MODE";
                case PRIORITY_PROXIMITY:
                    return "PRIORITY_PROXIMITY";
                case PRIORITY_LOW_POWER_MODE:
                    return "PRIORITY_LOW_POWER_MODE";
                case PRIORITY_UDFPS:
                    return "PRIORITY_UDFPS";
                case PRIORITY_USER_SETTING_MIN_REFRESH_RATE:
                    return "PRIORITY_USER_SETTING_MIN_REFRESH_RATE";
                case PRIORITY_USER_SETTING_PEAK_REFRESH_RATE:
                    return "PRIORITY_USER_SETTING_PEAK_REFRESH_RATE";
                default:
                    return Integer.toString(priority);
            }
        }

        @Override
        public String toString() {
            return "Vote{"
                + "width=" + width + ", height=" + height
                + ", minRefreshRate=" + refreshRateRange.min
                + ", maxRefreshRate=" + refreshRateRange.max
                + ", disableRefreshRateSwitching=" + disableRefreshRateSwitching
                + ", baseModeRefreshRate=" + baseModeRefreshRate + "}";
        }
    }

    @VisibleForTesting
    final class SettingsObserver extends ContentObserver {
        private final Uri mPeakRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE);
        private final Uri mMinRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.MIN_REFRESH_RATE);
        private final Uri mLowPowerModeSetting =
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE);
        private final Uri mMatchContentFrameRateSetting =
                Settings.Secure.getUriFor(Settings.Secure.MATCH_CONTENT_FRAME_RATE);

        private final Context mContext;
        private float mDefaultPeakRefreshRate;
        private float mDefaultRefreshRate;

        SettingsObserver(@NonNull Context context, @NonNull Handler handler) {
            super(handler);
            mContext = context;
            mDefaultPeakRefreshRate = (float) context.getResources().getInteger(
                    R.integer.config_defaultPeakRefreshRate);
            mDefaultRefreshRate =
                    (float) context.getResources().getInteger(R.integer.config_defaultRefreshRate);
        }

        public void observe() {
            final ContentResolver cr = mContext.getContentResolver();
            mInjector.registerPeakRefreshRateObserver(cr, this);
            cr.registerContentObserver(mMinRefreshRateSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mLowPowerModeSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mMatchContentFrameRateSetting, false /*notifyDescendants*/,
                    this);

            Float deviceConfigDefaultPeakRefresh =
                    mDeviceConfigDisplaySettings.getDefaultPeakRefreshRate();
            if (deviceConfigDefaultPeakRefresh != null) {
                mDefaultPeakRefreshRate = deviceConfigDefaultPeakRefresh;
            }

            synchronized (mLock) {
                updateRefreshRateSettingLocked();
                updateLowPowerModeSettingLocked();
                updateModeSwitchingTypeSettingLocked();
            }
        }

        public void setDefaultRefreshRate(float refreshRate) {
            synchronized (mLock) {
                mDefaultRefreshRate = refreshRate;
                updateRefreshRateSettingLocked();
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
                } else if (mMatchContentFrameRateSetting.equals(uri)) {
                    updateModeSwitchingTypeSettingLocked();
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
            final ContentResolver cr = mContext.getContentResolver();
            float minRefreshRate = Settings.System.getFloatForUser(cr,
                    Settings.System.MIN_REFRESH_RATE, 0f, cr.getUserId());
            float peakRefreshRate = Settings.System.getFloatForUser(cr,
                    Settings.System.PEAK_REFRESH_RATE, mDefaultPeakRefreshRate, cr.getUserId());
            updateRefreshRateSettingLocked(minRefreshRate, peakRefreshRate, mDefaultRefreshRate);
        }

        private void updateRefreshRateSettingLocked(
                float minRefreshRate, float peakRefreshRate, float defaultRefreshRate) {
            // TODO(b/156304339): The logic in here, aside from updating the refresh rate votes, is
            // used to predict if we're going to be doing frequent refresh rate switching, and if
            // so, enable the brightness observer. The logic here is more complicated and fragile
            // than necessary, and we should improve it. See b/156304339 for more info.
            Vote peakVote = peakRefreshRate == 0f
                    ? null
                    : Vote.forRefreshRates(0f, Math.max(minRefreshRate, peakRefreshRate));
            updateVoteLocked(Vote.PRIORITY_USER_SETTING_PEAK_REFRESH_RATE, peakVote);
            updateVoteLocked(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                    Vote.forRefreshRates(minRefreshRate, Float.POSITIVE_INFINITY));
            Vote defaultVote =
                    defaultRefreshRate == 0f ? null : Vote.forRefreshRates(0f, defaultRefreshRate);
            updateVoteLocked(Vote.PRIORITY_DEFAULT_REFRESH_RATE, defaultVote);

            float maxRefreshRate;
            if (peakRefreshRate == 0f && defaultRefreshRate == 0f) {
                // We require that at least one of the peak or default refresh rate values are
                // set. The brightness observer requires that we're able to predict whether or not
                // we're going to do frequent refresh rate switching, and with the way the code is
                // currently written, we need either a default or peak refresh rate value for that.
                Slog.e(TAG, "Default and peak refresh rates are both 0. One of them should be set"
                        + " to a valid value.");
                maxRefreshRate = minRefreshRate;
            } else if (peakRefreshRate == 0f) {
                maxRefreshRate = defaultRefreshRate;
            } else if (defaultRefreshRate == 0f) {
                maxRefreshRate = peakRefreshRate;
            } else {
                maxRefreshRate = Math.min(defaultRefreshRate, peakRefreshRate);
            }

            mBrightnessObserver.onRefreshRateSettingChangedLocked(minRefreshRate, maxRefreshRate);
        }

        private void updateModeSwitchingTypeSettingLocked() {
            final ContentResolver cr = mContext.getContentResolver();
            int switchingType = Settings.Secure.getIntForUser(
                    cr, Settings.Secure.MATCH_CONTENT_FRAME_RATE, mModeSwitchingType /*default*/,
                    cr.getUserId());
            if (switchingType != mModeSwitchingType) {
                mModeSwitchingType = switchingType;
                notifyDesiredDisplayModeSpecsChangedLocked();
            }
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  SettingsObserver");
            pw.println("    mDefaultRefreshRate: " + mDefaultRefreshRate);
            pw.println("    mDefaultPeakRefreshRate: " + mDefaultPeakRefreshRate);
        }
    }

    final class AppRequestObserver {
        private final SparseArray<Display.Mode> mAppRequestedModeByDisplay;
        private final SparseArray<RefreshRateRange> mAppPreferredRefreshRateRangeByDisplay;

        AppRequestObserver() {
            mAppRequestedModeByDisplay = new SparseArray<>();
            mAppPreferredRefreshRateRangeByDisplay = new SparseArray<>();
        }

        public void setAppRequest(int displayId, int modeId, float requestedMinRefreshRateRange,
                float requestedMaxRefreshRateRange) {
            synchronized (mLock) {
                setAppRequestedModeLocked(displayId, modeId);
                setAppPreferredRefreshRateRangeLocked(displayId, requestedMinRefreshRateRange,
                        requestedMaxRefreshRateRange);
            }
        }

        private void setAppRequestedModeLocked(int displayId, int modeId) {
            final Display.Mode requestedMode = findModeByIdLocked(displayId, modeId);
            if (Objects.equals(requestedMode, mAppRequestedModeByDisplay.get(displayId))) {
                return;
            }

            final Vote baseModeRefreshRateVote;
            final Vote sizeVote;
            if (requestedMode != null) {
                mAppRequestedModeByDisplay.put(displayId, requestedMode);
                baseModeRefreshRateVote =
                        Vote.forBaseModeRefreshRate(requestedMode.getRefreshRate());
                sizeVote = Vote.forSize(requestedMode.getPhysicalWidth(),
                        requestedMode.getPhysicalHeight());
            } else {
                mAppRequestedModeByDisplay.remove(displayId);
                baseModeRefreshRateVote = null;
                sizeVote = null;
            }

            updateVoteLocked(displayId, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                    baseModeRefreshRateVote);
            updateVoteLocked(displayId, Vote.PRIORITY_APP_REQUEST_SIZE, sizeVote);
        }

        private void setAppPreferredRefreshRateRangeLocked(int displayId,
                float requestedMinRefreshRateRange, float requestedMaxRefreshRateRange) {
            final Vote vote;

            RefreshRateRange refreshRateRange = null;
            if (requestedMinRefreshRateRange > 0 || requestedMaxRefreshRateRange > 0) {
                float min = requestedMinRefreshRateRange;
                float max = requestedMaxRefreshRateRange > 0
                        ? requestedMaxRefreshRateRange : Float.POSITIVE_INFINITY;
                refreshRateRange = new RefreshRateRange(min, max);
                if (refreshRateRange.min == 0 && refreshRateRange.max == 0) {
                    // requestedMinRefreshRateRange/requestedMaxRefreshRateRange were invalid
                    refreshRateRange = null;
                }
            }

            if (Objects.equals(refreshRateRange,
                    mAppPreferredRefreshRateRangeByDisplay.get(displayId))) {
                return;
            }

            if (refreshRateRange != null) {
                mAppPreferredRefreshRateRangeByDisplay.put(displayId, refreshRateRange);
                vote = Vote.forRefreshRates(refreshRateRange.min, refreshRateRange.max);
            } else {
                mAppPreferredRefreshRateRangeByDisplay.remove(displayId);
                vote = null;
            }
            synchronized (mLock) {
                updateVoteLocked(displayId, Vote.PRIORITY_APP_REQUEST_REFRESH_RATE_RANGE, vote);
            }
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
            pw.println("    mAppPreferredRefreshRateRangeByDisplay:");
            for (int i = 0; i < mAppPreferredRefreshRateRangeByDisplay.size(); i++) {
                final int id = mAppPreferredRefreshRateRangeByDisplay.keyAt(i);
                final RefreshRateRange refreshRateRange =
                        mAppPreferredRefreshRateRangeByDisplay.valueAt(i);
                pw.println("    " + id + " -> " + refreshRateRange);
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
            // TODO: Break the coupling between DisplayObserver and BrightnessObserver.
            mBrightnessObserver.onDisplayChanged(displayId);
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
                    notifyDesiredDisplayModeSpecsChangedLocked();
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
    @VisibleForTesting
    public class BrightnessObserver extends ContentObserver {
        private final static int LIGHT_SENSOR_RATE_MS = 250;
        private int[] mLowDisplayBrightnessThresholds;
        private int[] mLowAmbientBrightnessThresholds;
        private int[] mHighDisplayBrightnessThresholds;
        private int[] mHighAmbientBrightnessThresholds;
        // valid threshold if any item from the array >= 0
        private boolean mShouldObserveDisplayLowChange;
        private boolean mShouldObserveAmbientLowChange;
        private boolean mShouldObserveDisplayHighChange;
        private boolean mShouldObserveAmbientHighChange;
        private boolean mLoggingEnabled;

        private SensorManager mSensorManager;
        private Sensor mLightSensor;
        private final LightSensorEventListener mLightSensorListener =
                new LightSensorEventListener();
        // Take it as low brightness before valid sensor data comes
        private float mAmbientLux = -1.0f;
        private AmbientFilter mAmbientFilter;
        private int mBrightness = -1;

        private final Context mContext;

        // Enable light sensor only when mShouldObserveAmbientLowChange is true or
        // mShouldObserveAmbientHighChange is true, screen is on, peak refresh rate
        // changeable and low power mode off. After initialization, these states will
        // be updated from the same handler thread.
        private int mDefaultDisplayState = Display.STATE_UNKNOWN;
        private boolean mRefreshRateChangeable = false;
        private boolean mLowPowerModeEnabled = false;

        private int mRefreshRateInLowZone;
        private int mRefreshRateInHighZone;

        BrightnessObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
            mLowDisplayBrightnessThresholds = context.getResources().getIntArray(
                    R.array.config_brightnessThresholdsOfPeakRefreshRate);
            mLowAmbientBrightnessThresholds = context.getResources().getIntArray(
                    R.array.config_ambientThresholdsOfPeakRefreshRate);

            if (mLowDisplayBrightnessThresholds.length != mLowAmbientBrightnessThresholds.length) {
                throw new RuntimeException("display low brightness threshold array and ambient "
                        + "brightness threshold array have different length: "
                        + "displayBrightnessThresholds="
                        + Arrays.toString(mLowDisplayBrightnessThresholds)
                        + ", ambientBrightnessThresholds="
                        + Arrays.toString(mLowAmbientBrightnessThresholds));
            }

            mHighDisplayBrightnessThresholds = context.getResources().getIntArray(
                    R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate);
            mHighAmbientBrightnessThresholds = context.getResources().getIntArray(
                    R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate);
            if (mHighDisplayBrightnessThresholds.length
                    != mHighAmbientBrightnessThresholds.length) {
                throw new RuntimeException("display high brightness threshold array and ambient "
                        + "brightness threshold array have different length: "
                        + "displayBrightnessThresholds="
                        + Arrays.toString(mHighDisplayBrightnessThresholds)
                        + ", ambientBrightnessThresholds="
                        + Arrays.toString(mHighAmbientBrightnessThresholds));
            }
            mRefreshRateInHighZone = context.getResources().getInteger(
                    R.integer.config_fixedRefreshRateInHighZone);
        }

        /**
         * @return the refresh to lock to when in a low brightness zone
         */
        @VisibleForTesting
        int getRefreshRateInLowZone() {
            return mRefreshRateInLowZone;
        }

        /**
         * @return the display brightness thresholds for the low brightness zones
         */
        @VisibleForTesting
        int[] getLowDisplayBrightnessThresholds() {
            return mLowDisplayBrightnessThresholds;
        }

        /**
         * @return the ambient brightness thresholds for the low brightness zones
         */
        @VisibleForTesting
        int[] getLowAmbientBrightnessThresholds() {
            return mLowAmbientBrightnessThresholds;
        }

        public void registerLightSensor(SensorManager sensorManager, Sensor lightSensor) {
            mSensorManager = sensorManager;
            mLightSensor = lightSensor;

            mSensorManager.registerListener(mLightSensorListener,
                    mLightSensor, LIGHT_SENSOR_RATE_MS * 1000, mHandler);
        }

        public void observe(SensorManager sensorManager) {
            mSensorManager = sensorManager;
            final ContentResolver cr = mContext.getContentResolver();
            mBrightness = Settings.System.getIntForUser(cr,
                    Settings.System.SCREEN_BRIGHTNESS, -1 /*default*/, cr.getUserId());

            // DeviceConfig is accessible after system ready.
            int[] lowDisplayBrightnessThresholds =
                    mDeviceConfigDisplaySettings.getLowDisplayBrightnessThresholds();
            int[] lowAmbientBrightnessThresholds =
                    mDeviceConfigDisplaySettings.getLowAmbientBrightnessThresholds();

            if (lowDisplayBrightnessThresholds != null && lowAmbientBrightnessThresholds != null
                    && lowDisplayBrightnessThresholds.length
                    == lowAmbientBrightnessThresholds.length) {
                mLowDisplayBrightnessThresholds = lowDisplayBrightnessThresholds;
                mLowAmbientBrightnessThresholds = lowAmbientBrightnessThresholds;
            }


            int[] highDisplayBrightnessThresholds =
                    mDeviceConfigDisplaySettings.getHighDisplayBrightnessThresholds();
            int[] highAmbientBrightnessThresholds =
                    mDeviceConfigDisplaySettings.getHighAmbientBrightnessThresholds();

            if (highDisplayBrightnessThresholds != null && highAmbientBrightnessThresholds != null
                    && highDisplayBrightnessThresholds.length
                    == highAmbientBrightnessThresholds.length) {
                mHighDisplayBrightnessThresholds = highDisplayBrightnessThresholds;
                mHighAmbientBrightnessThresholds = highAmbientBrightnessThresholds;
            }

            mRefreshRateInLowZone = mDeviceConfigDisplaySettings.getRefreshRateInLowZone();
            mRefreshRateInHighZone = mDeviceConfigDisplaySettings.getRefreshRateInHighZone();

            restartObserver();
            mDeviceConfigDisplaySettings.startListening();
        }

        public void setLoggingEnabled(boolean loggingEnabled) {
            if (mLoggingEnabled == loggingEnabled) {
                return;
            }
            mLoggingEnabled = loggingEnabled;
            mLightSensorListener.setLoggingEnabled(loggingEnabled);
        }

        public void onRefreshRateSettingChangedLocked(float min, float max) {
            boolean changeable = (max - min > 1f && max > 60f);
            if (mRefreshRateChangeable != changeable) {
                mRefreshRateChangeable = changeable;
                updateSensorStatus();
                if (!changeable) {
                    // Revoke previous vote from BrightnessObserver
                    updateVoteLocked(Vote.PRIORITY_FLICKER_REFRESH_RATE, null);
                    updateVoteLocked(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, null);
                }
            }
        }

        public void onLowPowerModeEnabledLocked(boolean b) {
            if (mLowPowerModeEnabled != b) {
                mLowPowerModeEnabled = b;
                updateSensorStatus();
            }
        }

        public void onDeviceConfigLowBrightnessThresholdsChanged(int[] displayThresholds,
                int[] ambientThresholds) {
            if (displayThresholds != null && ambientThresholds != null
                    && displayThresholds.length == ambientThresholds.length) {
                mLowDisplayBrightnessThresholds = displayThresholds;
                mLowAmbientBrightnessThresholds = ambientThresholds;
            } else {
                // Invalid or empty. Use device default.
                mLowDisplayBrightnessThresholds = mContext.getResources().getIntArray(
                        R.array.config_brightnessThresholdsOfPeakRefreshRate);
                mLowAmbientBrightnessThresholds = mContext.getResources().getIntArray(
                        R.array.config_ambientThresholdsOfPeakRefreshRate);
            }
            restartObserver();
        }

        public void onDeviceConfigRefreshRateInLowZoneChanged(int refreshRate) {
            if (refreshRate != mRefreshRateInLowZone) {
                mRefreshRateInLowZone = refreshRate;
                restartObserver();
            }
        }

        public void onDeviceConfigHighBrightnessThresholdsChanged(int[] displayThresholds,
                int[] ambientThresholds) {
            if (displayThresholds != null && ambientThresholds != null
                    && displayThresholds.length == ambientThresholds.length) {
                mHighDisplayBrightnessThresholds = displayThresholds;
                mHighAmbientBrightnessThresholds = ambientThresholds;
            } else {
                // Invalid or empty. Use device default.
                mHighDisplayBrightnessThresholds = mContext.getResources().getIntArray(
                        R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate);
                mHighAmbientBrightnessThresholds = mContext.getResources().getIntArray(
                        R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate);
            }
            restartObserver();
        }

        public void onDeviceConfigRefreshRateInHighZoneChanged(int refreshRate) {
            if (refreshRate != mRefreshRateInHighZone) {
                mRefreshRateInHighZone = refreshRate;
                restartObserver();
            }
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  BrightnessObserver");
            pw.println("    mAmbientLux: " + mAmbientLux);
            pw.println("    mBrightness: " + mBrightness);
            pw.println("    mDefaultDisplayState: " + mDefaultDisplayState);
            pw.println("    mLowPowerModeEnabled: " + mLowPowerModeEnabled);
            pw.println("    mRefreshRateChangeable: " + mRefreshRateChangeable);
            pw.println("    mShouldObserveDisplayLowChange: " + mShouldObserveDisplayLowChange);
            pw.println("    mShouldObserveAmbientLowChange: " + mShouldObserveAmbientLowChange);
            pw.println("    mRefreshRateInLowZone: " + mRefreshRateInLowZone);

            for (int d : mLowDisplayBrightnessThresholds) {
                pw.println("    mDisplayLowBrightnessThreshold: " + d);
            }

            for (int d : mLowAmbientBrightnessThresholds) {
                pw.println("    mAmbientLowBrightnessThreshold: " + d);
            }

            pw.println("    mShouldObserveDisplayHighChange: " + mShouldObserveDisplayHighChange);
            pw.println("    mShouldObserveAmbientHighChange: " + mShouldObserveAmbientHighChange);
            pw.println("    mRefreshRateInHighZone: " + mRefreshRateInHighZone);

            for (int d : mHighDisplayBrightnessThresholds) {
                pw.println("    mDisplayHighBrightnessThresholds: " + d);
            }

            for (int d : mHighAmbientBrightnessThresholds) {
                pw.println("    mAmbientHighBrightnessThresholds: " + d);
            }

            mLightSensorListener.dumpLocked(pw);

            if (mAmbientFilter != null) {
                IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
                mAmbientFilter.dump(ipw);
            }
        }

        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                updateDefaultDisplayState();
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                final ContentResolver cr = mContext.getContentResolver();
                int brightness = Settings.System.getIntForUser(cr,
                        Settings.System.SCREEN_BRIGHTNESS, -1 /*default*/, cr.getUserId());
                if (brightness != mBrightness) {
                    mBrightness = brightness;
                    onBrightnessChangedLocked();
                }
            }
        }

        private void restartObserver() {
            final ContentResolver cr = mContext.getContentResolver();

            if (mRefreshRateInLowZone > 0) {
                mShouldObserveDisplayLowChange = hasValidThreshold(
                        mLowDisplayBrightnessThresholds);
                mShouldObserveAmbientLowChange = hasValidThreshold(
                        mLowAmbientBrightnessThresholds);
            } else {
                mShouldObserveDisplayLowChange = false;
                mShouldObserveAmbientLowChange = false;
            }

            if (mRefreshRateInHighZone > 0) {
                mShouldObserveDisplayHighChange = hasValidThreshold(
                        mHighDisplayBrightnessThresholds);
                mShouldObserveAmbientHighChange = hasValidThreshold(
                        mHighAmbientBrightnessThresholds);
            } else {
                mShouldObserveDisplayHighChange = false;
                mShouldObserveAmbientHighChange = false;
            }

            if (mShouldObserveDisplayLowChange || mShouldObserveDisplayHighChange) {
                // Content Service does not check if an listener has already been registered.
                // To ensure only one listener is registered, force an unregistration first.
                mInjector.unregisterBrightnessObserver(cr, this);
                mInjector.registerBrightnessObserver(cr, this);
            } else {
                mInjector.unregisterBrightnessObserver(cr, this);
            }

            if (mShouldObserveAmbientLowChange || mShouldObserveAmbientHighChange) {
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

                    mAmbientFilter = AmbientFilterFactory.createBrightnessFilter(TAG, res);
                    mLightSensor = lightSensor;
                }
            } else {
                mAmbientFilter = null;
                mLightSensor = null;
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
        private boolean hasValidThreshold(int[] a) {
            for (int d: a) {
                if (d >= 0) {
                    return true;
                }
            }

            return false;
        }

        private boolean isInsideLowZone(int brightness, float lux) {
            for (int i = 0; i < mLowDisplayBrightnessThresholds.length; i++) {
                int disp = mLowDisplayBrightnessThresholds[i];
                int ambi = mLowAmbientBrightnessThresholds[i];

                if (disp >= 0 && ambi >= 0) {
                    if (brightness <= disp && lux <= ambi) {
                        return true;
                    }
                } else if (disp >= 0) {
                    if (brightness <= disp) {
                        return true;
                    }
                } else if (ambi >= 0) {
                    if (lux <= ambi) {
                        return true;
                    }
                }
            }

            return false;
        }

        private boolean isInsideHighZone(int brightness, float lux) {
            for (int i = 0; i < mHighDisplayBrightnessThresholds.length; i++) {
                int disp = mHighDisplayBrightnessThresholds[i];
                int ambi = mHighAmbientBrightnessThresholds[i];

                if (disp >= 0 && ambi >= 0) {
                    if (brightness >= disp && lux >= ambi) {
                        return true;
                    }
                } else if (disp >= 0) {
                    if (brightness >= disp) {
                        return true;
                    }
                } else if (ambi >= 0) {
                    if (lux >= ambi) {
                        return true;
                    }
                }
            }

            return false;
        }
        private void onBrightnessChangedLocked() {
            Vote refreshRateVote = null;
            Vote refreshRateSwitchingVote = null;

            if (mBrightness < 0) {
                // Either the setting isn't available or we shouldn't be observing yet anyways.
                // Either way, just bail out since there's nothing we can do here.
                return;
            }

            boolean insideLowZone = hasValidLowZone() && isInsideLowZone(mBrightness, mAmbientLux);
            if (insideLowZone) {
                refreshRateVote =
                        Vote.forRefreshRates(mRefreshRateInLowZone, mRefreshRateInLowZone);
                refreshRateSwitchingVote = Vote.forDisableRefreshRateSwitching();
            }

            boolean insideHighZone = hasValidHighZone()
                    && isInsideHighZone(mBrightness, mAmbientLux);
            if (insideHighZone) {
                refreshRateVote =
                        Vote.forRefreshRates(mRefreshRateInHighZone, mRefreshRateInHighZone);
                refreshRateSwitchingVote = Vote.forDisableRefreshRateSwitching();
            }

            if (mLoggingEnabled) {
                Slog.d(TAG, "Display brightness " + mBrightness + ", ambient lux " +  mAmbientLux
                        + ", Vote " + refreshRateVote);
            }
            updateVoteLocked(Vote.PRIORITY_FLICKER_REFRESH_RATE, refreshRateVote);
            updateVoteLocked(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, refreshRateSwitchingVote);
        }

        private boolean hasValidLowZone() {
            return mRefreshRateInLowZone > 0
                    && (mShouldObserveDisplayLowChange || mShouldObserveAmbientLowChange);
        }

        private boolean hasValidHighZone() {
            return mRefreshRateInHighZone > 0
                    && (mShouldObserveDisplayHighChange || mShouldObserveAmbientHighChange);
        }

        private void updateDefaultDisplayState() {
            Display display = mContext.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                return;
            }

            setDefaultDisplayState(display.getState());
        }

        @VisibleForTesting
        public void setDefaultDisplayState(int state) {
            if (mLoggingEnabled) {
                Slog.d(TAG, "setDefaultDisplayState: mDefaultDisplayState = "
                        + mDefaultDisplayState + ", state = " + state);
            }

            if (mDefaultDisplayState != state) {
                mDefaultDisplayState = state;
                updateSensorStatus();
            }
        }

        private void updateSensorStatus() {
            if (mSensorManager == null || mLightSensorListener == null) {
                return;
            }

            if (mLoggingEnabled) {
                Slog.d(TAG, "updateSensorStatus: mShouldObserveAmbientLowChange = "
                        + mShouldObserveAmbientLowChange + ", mShouldObserveAmbientHighChange = "
                        + mShouldObserveAmbientHighChange);
                Slog.d(TAG, "updateSensorStatus: mLowPowerModeEnabled = "
                        + mLowPowerModeEnabled + ", mRefreshRateChangeable = "
                        + mRefreshRateChangeable);
            }

            if ((mShouldObserveAmbientLowChange || mShouldObserveAmbientHighChange)
                     && isDeviceActive() && !mLowPowerModeEnabled && mRefreshRateChangeable) {
                mSensorManager.registerListener(mLightSensorListener,
                        mLightSensor, LIGHT_SENSOR_RATE_MS * 1000, mHandler);
                if (mLoggingEnabled) {
                    Slog.d(TAG, "updateSensorStatus: registerListener");
                }
            } else {
                mLightSensorListener.removeCallbacks();
                mSensorManager.unregisterListener(mLightSensorListener);
                if (mLoggingEnabled) {
                    Slog.d(TAG, "updateSensorStatus: unregisterListener");
                }
            }
        }

        private boolean isDeviceActive() {
            return mDefaultDisplayState == Display.STATE_ON;
        }

        private final class LightSensorEventListener implements SensorEventListener {
            final private static int INJECT_EVENTS_INTERVAL_MS = LIGHT_SENSOR_RATE_MS;
            private float mLastSensorData;
            private long mTimestamp;
            private boolean mLoggingEnabled;

            public void dumpLocked(PrintWriter pw) {
                pw.println("    mLastSensorData: " + mLastSensorData);
                pw.println("    mTimestamp: " + formatTimestamp(mTimestamp));
            }


            public void setLoggingEnabled(boolean loggingEnabled) {
                if (mLoggingEnabled == loggingEnabled) {
                    return;
                }
                mLoggingEnabled = loggingEnabled;
            }

            @Override
            public void onSensorChanged(SensorEvent event) {
                mLastSensorData = event.values[0];
                if (mLoggingEnabled) {
                    Slog.d(TAG, "On sensor changed: " + mLastSensorData);
                }

                boolean lowZoneChanged = isDifferentZone(mLastSensorData, mAmbientLux,
                        mLowAmbientBrightnessThresholds);
                boolean highZoneChanged = isDifferentZone(mLastSensorData, mAmbientLux,
                        mHighAmbientBrightnessThresholds);
                if ((lowZoneChanged && mLastSensorData < mAmbientLux)
                        || (highZoneChanged && mLastSensorData > mAmbientLux)) {
                    // Easier to see flicker at lower brightness environment or high brightness
                    // environment. Forget the history to get immediate response.
                    if (mAmbientFilter != null) {
                        mAmbientFilter.clear();
                    }
                }

                long now = SystemClock.uptimeMillis();
                mTimestamp = System.currentTimeMillis();
                if (mAmbientFilter != null) {
                    mAmbientFilter.addValue(now, mLastSensorData);
                }

                mHandler.removeCallbacks(mInjectSensorEventRunnable);
                processSensorData(now);

                if ((lowZoneChanged && mLastSensorData > mAmbientLux)
                        || (highZoneChanged && mLastSensorData < mAmbientLux)) {
                    // Sensor may not report new event if there is no brightness change.
                    // Need to keep querying the temporal filter for the latest estimation,
                    // until sensor readout and filter estimation are in the same zone or
                    // is interrupted by a new sensor event.
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

            private String formatTimestamp(long time) {
                SimpleDateFormat dateFormat =
                        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                return dateFormat.format(new Date(time));
            }

            private void processSensorData(long now) {
                if (mAmbientFilter != null) {
                    mAmbientLux = mAmbientFilter.getEstimate(now);
                } else {
                    mAmbientLux = mLastSensorData;
                }

                synchronized (mLock) {
                    onBrightnessChangedLocked();
                }
            }

            private boolean isDifferentZone(float lux1, float lux2, int[] luxThresholds) {
                for (final float boundary : luxThresholds) {
                    // Test each boundary. See if the current value and the new value are at
                    // different sides.
                    if ((lux1 <= boundary && lux2 > boundary)
                            || (lux1 > boundary && lux2 <= boundary)) {
                        return true;
                    }
                }

                return false;
            }

            private final Runnable mInjectSensorEventRunnable = new Runnable() {
                @Override
                public void run() {
                    long now = SystemClock.uptimeMillis();
                    // No need to really inject the last event into a temporal filter.
                    processSensorData(now);

                    // Inject next event if there is a possible zone change.
                    if (isDifferentZone(mLastSensorData, mAmbientLux,
                            mLowAmbientBrightnessThresholds)
                            || isDifferentZone(mLastSensorData, mAmbientLux,
                            mHighAmbientBrightnessThresholds)) {
                        mHandler.postDelayed(mInjectSensorEventRunnable, INJECT_EVENTS_INTERVAL_MS);
                    }
                }
            };
        }
    }

    private class UdfpsObserver extends IUdfpsHbmListener.Stub {
        private final SparseBooleanArray mLocalHbmEnabled = new SparseBooleanArray();
        private final SparseBooleanArray mGlobalHbmEnabled = new SparseBooleanArray();

        public void observe() {
            StatusBarManagerInternal statusBar =
                    LocalServices.getService(StatusBarManagerInternal.class);
            if (statusBar != null) {
                statusBar.setUdfpsHbmListener(this);
            }
        }

        @Override
        public void onHbmEnabled(int hbmType, int displayId) {
            synchronized (mLock) {
                updateHbmStateLocked(hbmType, displayId, true /*enabled*/);
            }
        }

        @Override
        public void onHbmDisabled(int hbmType, int displayId) {
            synchronized (mLock) {
                updateHbmStateLocked(hbmType, displayId, false /*enabled*/);
            }
        }

        private void updateHbmStateLocked(int hbmType, int displayId, boolean enabled) {
            switch (hbmType) {
                case UdfpsObserver.LOCAL_HBM:
                    mLocalHbmEnabled.put(displayId, enabled);
                    break;
                case UdfpsObserver.GLOBAL_HBM:
                    mGlobalHbmEnabled.put(displayId, enabled);
                    break;
                default:
                    Slog.w(TAG, "Unknown HBM type reported. Ignoring.");
                    return;
            }
            updateVoteLocked(displayId);
        }

        private void updateVoteLocked(int displayId) {
            final Vote vote;
            if (mGlobalHbmEnabled.get(displayId)) {
                vote = Vote.forRefreshRates(60f, 60f);
            } else if (mLocalHbmEnabled.get(displayId)) {
                Display.Mode[] modes = mSupportedModesByDisplay.get(displayId);
                float maxRefreshRate = 0f;
                for (Display.Mode mode : modes) {
                    if (mode.getRefreshRate() > maxRefreshRate) {
                        maxRefreshRate = mode.getRefreshRate();
                    }
                }
                vote = Vote.forRefreshRates(maxRefreshRate, maxRefreshRate);
            } else {
                vote = null;
            }

            DisplayModeDirector.this.updateVoteLocked(displayId, Vote.PRIORITY_UDFPS, vote);
        }

        void dumpLocked(PrintWriter pw) {
            pw.println("  UdfpsObserver");
            pw.println("    mLocalHbmEnabled: ");
            for (int i = 0; i < mLocalHbmEnabled.size(); i++) {
                final int displayId = mLocalHbmEnabled.keyAt(i);
                final String enabled = mLocalHbmEnabled.valueAt(i) ? "enabled" : "disabled";
                pw.println("      Display " + displayId + ": " + enabled);
            }
            pw.println("    mGlobalHbmEnabled: ");
            for (int i = 0; i < mGlobalHbmEnabled.size(); i++) {
                final int displayId = mGlobalHbmEnabled.keyAt(i);
                final String enabled = mGlobalHbmEnabled.valueAt(i) ? "enabled" : "disabled";
                pw.println("      Display " + displayId + ": " + enabled);
            }

        }
    }

    private final class SensorObserver implements ProximityActiveListener,
            DisplayManager.DisplayListener {
        private final String mProximitySensorName = null;
        private final String mProximitySensorType = Sensor.STRING_TYPE_PROXIMITY;

        private final BallotBox mBallotBox;
        private final Context mContext;
        private final Injector mInjector;

        private DisplayManager mDisplayManager;
        private DisplayManagerInternal mDisplayManagerInternal;
        private boolean mIsProxActive = false;
        private final SparseBooleanArray mDozeStateByDisplay;

        SensorObserver(Context context, BallotBox ballotBox, Injector injector) {
            mContext = context;
            mBallotBox = ballotBox;
            mInjector = injector;
            mDozeStateByDisplay = new SparseBooleanArray();
        }

        @Override
        public void onProximityActive(boolean isActive) {
            synchronized (mLock) {
                if (mIsProxActive != isActive) {
                    mIsProxActive = isActive;
                    recalculateVotesLocked();
                }
            }
        }

        public void observe() {
            mDisplayManager = mContext.getSystemService(DisplayManager.class);
            mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);

            final SensorManagerInternal sensorManager =
                    LocalServices.getService(SensorManagerInternal.class);
            sensorManager.addProximityActiveListener(BackgroundThread.getExecutor(), this);

            synchronized (mLock) {
                for (Display d : mDisplayManager.getDisplays()) {
                    mDozeStateByDisplay.put(d.getDisplayId(), mInjector.isDozeState(d));
                }
            }
            mInjector.registerDisplayListener(this, BackgroundThread.getHandler(),
                    DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                            | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                            | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);
        }

        private void recalculateVotesLocked() {
            final Display[] displays = mDisplayManager.getDisplays();
            for (Display d : displays) {
                int displayId = d.getDisplayId();
                Vote vote = null;
                if (mIsProxActive && !mDozeStateByDisplay.get(displayId)) {
                    final RefreshRateRange rate =
                            mDisplayManagerInternal.getRefreshRateForDisplayAndSensor(
                                    displayId, mProximitySensorName, mProximitySensorType);
                    if (rate != null) {
                        vote = Vote.forRefreshRates(rate.min, rate.max);
                    }
                }
                mBallotBox.vote(displayId, Vote.PRIORITY_PROXIMITY, vote);
            }
        }

        void dumpLocked(PrintWriter pw) {
            pw.println("  SensorObserver");
            pw.println("    mIsProxActive=" + mIsProxActive);
            pw.println("    mDozeStateByDisplay:");
            for (int i = 0; i < mDozeStateByDisplay.size(); i++) {
                final int id = mDozeStateByDisplay.keyAt(i);
                final boolean dozed = mDozeStateByDisplay.valueAt(i);
                pw.println("      " + id + " -> " + dozed);
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            boolean isDozeState = mInjector.isDozeState(mDisplayManager.getDisplay(displayId));
            synchronized (mLock) {
                mDozeStateByDisplay.put(displayId, isDozeState);
                recalculateVotesLocked();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            boolean wasDozeState = mDozeStateByDisplay.get(displayId);
            synchronized (mLock) {
                mDozeStateByDisplay.put(displayId,
                        mInjector.isDozeState(mDisplayManager.getDisplay(displayId)));
                if (wasDozeState != mDozeStateByDisplay.get(displayId)) {
                    recalculateVotesLocked();
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                mDozeStateByDisplay.delete(displayId);
                recalculateVotesLocked();
            }
        }
    }

    /**
     * Listens to DisplayManager for HBM status and applies any refresh-rate restrictions for
     * HBM that are associated with that display. Restrictions are retrieved from
     * DisplayManagerInternal but originate in the display-device-config file.
     */
    private static class HbmObserver implements DisplayManager.DisplayListener {
        private final BallotBox mBallotBox;
        private final Handler mHandler;
        private final SparseBooleanArray mHbmEnabled = new SparseBooleanArray();
        private final Injector mInjector;

        private DisplayManagerInternal mDisplayManagerInternal;

        HbmObserver(Injector injector, BallotBox ballotBox, Handler handler) {
            mInjector = injector;
            mBallotBox = ballotBox;
            mHandler = handler;
        }

        public void observe() {
            mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
            mInjector.registerDisplayListener(this, mHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
                    | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);
        }

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {
            mBallotBox.vote(displayId, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE, null);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            final BrightnessInfo info = mInjector.getBrightnessInfo(displayId);
            if (info == null) {
                // Display no longer there. Assume we'll get an onDisplayRemoved very soon.
                return;
            }
            final boolean isHbmEnabled =
                    info.highBrightnessMode != BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF;
            if (isHbmEnabled == mHbmEnabled.get(displayId)) {
                // no change, ignore.
                return;
            }
            Vote vote = null;
            mHbmEnabled.put(displayId, isHbmEnabled);
            if (isHbmEnabled) {
                final List<RefreshRateLimitation> limits =
                        mDisplayManagerInternal.getRefreshRateLimitations(displayId);
                for (int i = 0; limits != null && i < limits.size(); i++) {
                    final RefreshRateLimitation limitation = limits.get(i);
                    if (limitation.type == REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE) {
                        vote = Vote.forRefreshRates(limitation.range.min, limitation.range.max);
                        break;
                    }
                }
            }
            mBallotBox.vote(displayId, Vote.PRIORITY_HIGH_BRIGHTNESS_MODE, vote);
        }

        void dumpLocked(PrintWriter pw) {
            pw.println("   HbmObserver");
            pw.println("     mHbmEnabled: " + mHbmEnabled);
        }
    }

    private class DeviceConfigDisplaySettings implements DeviceConfig.OnPropertiesChangedListener {
        public DeviceConfigDisplaySettings() {
        }

        public void startListening() {
            mDeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    BackgroundThread.getExecutor(), this);
        }

        /*
         * Return null if no such property or wrong format (not comma separated integers).
         */
        public int[] getLowDisplayBrightnessThresholds() {
            return getIntArrayProperty(
                    DisplayManager.DeviceConfig.
                            KEY_FIXED_REFRESH_RATE_LOW_DISPLAY_BRIGHTNESS_THRESHOLDS);
        }

        /*
         * Return null if no such property or wrong format (not comma separated integers).
         */
        public int[] getLowAmbientBrightnessThresholds() {
            return getIntArrayProperty(
                    DisplayManager.DeviceConfig.
                            KEY_FIXED_REFRESH_RATE_LOW_AMBIENT_BRIGHTNESS_THRESHOLDS);
        }

        public int getRefreshRateInLowZone() {
            int defaultRefreshRateInZone = mContext.getResources().getInteger(
                    R.integer.config_defaultRefreshRateInZone);

            int refreshRate = mDeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_LOW_ZONE,
                    defaultRefreshRateInZone);

            return refreshRate;
        }

        /*
         * Return null if no such property or wrong format (not comma separated integers).
         */
        public int[] getHighDisplayBrightnessThresholds() {
            return getIntArrayProperty(
                    DisplayManager.DeviceConfig
                            .KEY_FIXED_REFRESH_RATE_HIGH_DISPLAY_BRIGHTNESS_THRESHOLDS);
        }

        /*
         * Return null if no such property or wrong format (not comma separated integers).
         */
        public int[] getHighAmbientBrightnessThresholds() {
            return getIntArrayProperty(
                    DisplayManager.DeviceConfig
                            .KEY_FIXED_REFRESH_RATE_HIGH_AMBIENT_BRIGHTNESS_THRESHOLDS);
        }

        public int getRefreshRateInHighZone() {
            int defaultRefreshRateInZone = mContext.getResources().getInteger(
                    R.integer.config_fixedRefreshRateInHighZone);

            int refreshRate = mDeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HIGH_ZONE,
                    defaultRefreshRateInZone);

            return refreshRate;
        }

        /*
         * Return null if no such property
         */
        public Float getDefaultPeakRefreshRate() {
            float defaultPeakRefreshRate = mDeviceConfig.getFloat(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    DisplayManager.DeviceConfig.KEY_PEAK_REFRESH_RATE_DEFAULT, -1);

            if (defaultPeakRefreshRate == -1) {
                return null;
            }
            return defaultPeakRefreshRate;
        }

        @Override
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            Float defaultPeakRefreshRate = getDefaultPeakRefreshRate();
            mHandler.obtainMessage(MSG_DEFAULT_PEAK_REFRESH_RATE_CHANGED,
                    defaultPeakRefreshRate).sendToTarget();

            int[] lowDisplayBrightnessThresholds = getLowDisplayBrightnessThresholds();
            int[] lowAmbientBrightnessThresholds = getLowAmbientBrightnessThresholds();
            int refreshRateInLowZone = getRefreshRateInLowZone();

            mHandler.obtainMessage(MSG_LOW_BRIGHTNESS_THRESHOLDS_CHANGED,
                    new Pair<>(lowDisplayBrightnessThresholds, lowAmbientBrightnessThresholds))
                    .sendToTarget();
            mHandler.obtainMessage(MSG_REFRESH_RATE_IN_LOW_ZONE_CHANGED, refreshRateInLowZone, 0)
                    .sendToTarget();

            int[] highDisplayBrightnessThresholds = getHighDisplayBrightnessThresholds();
            int[] highAmbientBrightnessThresholds = getHighAmbientBrightnessThresholds();
            int refreshRateInHighZone = getRefreshRateInHighZone();

            mHandler.obtainMessage(MSG_HIGH_BRIGHTNESS_THRESHOLDS_CHANGED,
                    new Pair<>(highDisplayBrightnessThresholds, highAmbientBrightnessThresholds))
                    .sendToTarget();
            mHandler.obtainMessage(MSG_REFRESH_RATE_IN_HIGH_ZONE_CHANGED, refreshRateInHighZone, 0)
                    .sendToTarget();
        }

        private int[] getIntArrayProperty(String prop) {
            String strArray = mDeviceConfig.getString(DeviceConfig.NAMESPACE_DISPLAY_MANAGER, prop,
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

    interface Injector {
        // TODO: brightnessfloat: change this to the float setting
        Uri DISPLAY_BRIGHTNESS_URI = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);
        Uri PEAK_REFRESH_RATE_URI = Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE);

        @NonNull
        DeviceConfigInterface getDeviceConfig();

        void registerBrightnessObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer);

        void unregisterBrightnessObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer);

        void registerPeakRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer);

        void registerDisplayListener(@NonNull DisplayManager.DisplayListener listener,
                Handler handler, long flags);

        BrightnessInfo getBrightnessInfo(int displayId);

        boolean isDozeState(Display d);
    }

    @VisibleForTesting
    static class RealInjector implements Injector {
        private final Context mContext;
        private DisplayManager mDisplayManager;

        RealInjector(Context context) {
            mContext = context;
        }

        @Override
        @NonNull
        public DeviceConfigInterface getDeviceConfig() {
            return DeviceConfigInterface.REAL;
        }

        @Override
        public void registerBrightnessObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            cr.registerContentObserver(DISPLAY_BRIGHTNESS_URI, false /*notifyDescendants*/,
                    observer, UserHandle.USER_SYSTEM);
        }

        @Override
        public void unregisterBrightnessObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            cr.unregisterContentObserver(observer);
        }

        @Override
        public void registerPeakRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            cr.registerContentObserver(PEAK_REFRESH_RATE_URI, false /*notifyDescendants*/,
                    observer, UserHandle.USER_SYSTEM);
        }

        @Override
        public void registerDisplayListener(DisplayManager.DisplayListener listener,
                Handler handler, long flags) {
            getDisplayManager().registerDisplayListener(listener, handler, flags);
        }

        @Override
        public BrightnessInfo getBrightnessInfo(int displayId) {
            final Display display = getDisplayManager().getDisplay(displayId);
            if (display != null) {
                return display.getBrightnessInfo();
            }
            return null;
        }

        @Override
        public boolean isDozeState(Display d) {
            return Display.isDozeState(d.getState());
        }

        private DisplayManager getDisplayManager() {
            if (mDisplayManager == null) {
                mDisplayManager = mContext.getSystemService(DisplayManager.class);
            }
            return mDisplayManager;
        }
    }

    interface BallotBox {
        void vote(int displayId, int priority, Vote vote);
    }
}
