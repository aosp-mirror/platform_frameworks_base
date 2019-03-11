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
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.R;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * The DisplayModeDirector is responsible for determining what modes are allowed to be
 * automatically picked by the system based on system-wide and display-specific configuration.
 */
public class DisplayModeDirector {
    private static final String TAG = "DisplayModeDirector";
    private static final boolean DEBUG = false;

    private static final int MSG_ALLOWED_MODES_CHANGED = 1;

    // Special ID used to indicate that given vote is to be applied globally, rather than to a
    // specific display.
    private static final int GLOBAL_ID = -1;

    // What we consider to be the system's "default" refresh rate.
    private static final float DEFAULT_REFRESH_RATE = 60f;

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
    }

    /**
     * Tells the DisplayModeDirector to update allowed votes and begin observing relevant system
     * state.
     *
     * This has to be deferred because the object may be constructed before the rest of the system
     * is ready.
     */
    public void start() {
        mSettingsObserver.observe();
        mDisplayObserver.observe();
        mSettingsObserver.observe();
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

    private static final class DisplayModeDirectorHandler extends Handler {
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
            }
        }
    }

    private static final class Vote {
        public static final int PRIORITY_USER_SETTING = 0;
        // We split the app request into two priorities in case we can satisfy one desire without
        // the other.
        public static final int PRIORITY_APP_REQUEST_REFRESH_RATE = 1;
        public static final int PRIORITY_APP_REQUEST_SIZE = 2;
        public static final int PRIORITY_LOW_POWER_MODE = 3;

        // Whenever a new priority is added, remember to update MIN_PRIORITY and/or MAX_PRIORITY as
        // appropriate, as well as priorityToString.

        public static final int MIN_PRIORITY = PRIORITY_USER_SETTING;
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
                case PRIORITY_USER_SETTING:
                    return "PRIORITY_USER_SETTING";
                case PRIORITY_APP_REQUEST_REFRESH_RATE:
                    return "PRIORITY_APP_REQUEST_REFRESH_RATE";
                case PRIORITY_APP_REQUEST_SIZE:
                    return "PRIORITY_APP_REQUEST_SIZE";
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
        private final Uri mRefreshRateSetting =
                Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE);
        private final Uri mLowPowerModeSetting =
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE);

        private final Context mContext;
        private final float mDefaultPeakRefreshRate;

        SettingsObserver(@NonNull Context context, @NonNull Handler handler) {
            super(handler);
            mContext = context;
            mDefaultPeakRefreshRate = (float) context.getResources().getInteger(
                    R.integer.config_defaultPeakRefreshRate);
        }

        public void observe() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(mRefreshRateSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mLowPowerModeSetting, false /*notifyDescendants*/, this,
                    UserHandle.USER_SYSTEM);
            synchronized (mLock) {
                updateRefreshRateSettingLocked();
                updateLowPowerModeSettingLocked();
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            synchronized (mLock) {
                if (mRefreshRateSetting.equals(uri)) {
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
        }

        private void updateRefreshRateSettingLocked() {
            float peakRefreshRate = Settings.System.getFloat(mContext.getContentResolver(),
                    Settings.System.PEAK_REFRESH_RATE, DEFAULT_REFRESH_RATE);
            Vote vote = Vote.forRefreshRates(0f, peakRefreshRate);
            updateVoteLocked(Vote.PRIORITY_USER_SETTING, vote);
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
}
