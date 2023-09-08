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

package com.android.server.display.mode;

import android.annotation.Nullable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.Temperature;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;

import java.io.PrintWriter;

final class SkinThermalStatusObserver extends IThermalEventListener.Stub implements
        DisplayManager.DisplayListener {
    private static final String TAG = "SkinThermalStatusObserver";

    private final VotesStorage mVotesStorage;
    private final DisplayModeDirector.Injector mInjector;

    private boolean mLoggingEnabled;

    private final Handler mHandler;
    private final Object mThermalObserverLock = new Object();
    @GuardedBy("mThermalObserverLock")
    @Temperature.ThrottlingStatus
    private int mStatus = Temperature.THROTTLING_NONE;
    @GuardedBy("mThermalObserverLock")
    private final SparseArray<SparseArray<SurfaceControl.RefreshRateRange>>
            mThermalThrottlingByDisplay = new SparseArray<>();

    SkinThermalStatusObserver(DisplayModeDirector.Injector injector,
            VotesStorage votesStorage) {
        this(injector, votesStorage, BackgroundThread.getHandler());
    }

    @VisibleForTesting
    SkinThermalStatusObserver(DisplayModeDirector.Injector injector,
            VotesStorage votesStorage, Handler handler) {
        mInjector = injector;
        mVotesStorage = votesStorage;
        mHandler = handler;
    }

    @Nullable
    public static SurfaceControl.RefreshRateRange findBestMatchingRefreshRateRange(
            @Temperature.ThrottlingStatus int currentStatus,
            SparseArray<SurfaceControl.RefreshRateRange> throttlingMap) {
        SurfaceControl.RefreshRateRange foundRange = null;
        for (int status = currentStatus; status >= 0; status--) {
            foundRange = throttlingMap.get(status);
            if (foundRange != null) {
                break;
            }
        }
        return foundRange;
    }

    void observe() {
        // if failed to register thermal service listener, don't register display listener
        if (!mInjector.registerThermalServiceListener(this)) {
            return;
        }

        mInjector.registerDisplayListener(this, mHandler,
                DisplayManager.EVENT_FLAG_DISPLAY_ADDED | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                        | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);

        populateInitialDisplayInfo();
    }

    void setLoggingEnabled(boolean enabled) {
        mLoggingEnabled = enabled;
    }

    @Override
    public void notifyThrottling(Temperature temp) {
        @Temperature.ThrottlingStatus int currentStatus = temp.getStatus();

        synchronized (mThermalObserverLock) {
            if (mStatus == currentStatus) {
                return; // status not changed, skip update
            }
            mStatus = currentStatus;
            mHandler.post(this::updateVotes);
        }

        if (mLoggingEnabled) {
            Slog.d(TAG, "New thermal throttling status " + ", current thermal status = "
                    + currentStatus);
        }
    }

    //region DisplayManager.DisplayListener
    @Override
    public void onDisplayAdded(int displayId) {
        updateThermalRefreshRateThrottling(displayId);
        if (mLoggingEnabled) {
            Slog.d(TAG, "Display added:" + displayId);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        synchronized (mThermalObserverLock) {
            mThermalThrottlingByDisplay.remove(displayId);
            mHandler.post(() -> mVotesStorage.updateVote(displayId,
                    Vote.PRIORITY_SKIN_TEMPERATURE, null));
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "Display removed and voted: displayId=" + displayId);
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        updateThermalRefreshRateThrottling(displayId);
        if (mLoggingEnabled) {
            Slog.d(TAG, "Display changed:" + displayId);
        }
    }
    //endregion

    private void populateInitialDisplayInfo() {
        DisplayInfo info = new DisplayInfo();
        Display[] displays = mInjector.getDisplays();
        int size = displays.length;
        SparseArray<SparseArray<SurfaceControl.RefreshRateRange>> localMap = new SparseArray<>(
                size);
        for (Display d : displays) {
            final int displayId = d.getDisplayId();
            d.getDisplayInfo(info);
            localMap.put(displayId, info.thermalRefreshRateThrottling);
        }
        synchronized (mThermalObserverLock) {
            for (int i = 0; i < size; i++) {
                mThermalThrottlingByDisplay.put(localMap.keyAt(i), localMap.valueAt(i));
            }
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "Display initial info:" + localMap);
        }
    }

    private void updateThermalRefreshRateThrottling(int displayId) {
        DisplayInfo displayInfo = new DisplayInfo();
        mInjector.getDisplayInfo(displayId, displayInfo);
        SparseArray<SurfaceControl.RefreshRateRange> throttlingMap =
                displayInfo.thermalRefreshRateThrottling;

        synchronized (mThermalObserverLock) {
            mThermalThrottlingByDisplay.put(displayId, throttlingMap);
            mHandler.post(() -> updateVoteForDisplay(displayId));
        }
        if (mLoggingEnabled) {
            Slog.d(TAG,
                    "Thermal throttling updated: display=" + displayId + ", map=" + throttlingMap);
        }
    }

    //region in mHandler thread
    private void updateVotes() {
        @Temperature.ThrottlingStatus int localStatus;
        SparseArray<SparseArray<SurfaceControl.RefreshRateRange>> localMap;

        synchronized (mThermalObserverLock) {
            localStatus = mStatus;
            localMap = mThermalThrottlingByDisplay.clone();
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "Updating votes for status=" + localStatus + ", map=" + localMap);
        }
        int size = localMap.size();
        for (int i = 0; i < size; i++) {
            reportThrottlingIfNeeded(localMap.keyAt(i), localStatus, localMap.valueAt(i));
        }
    }

    private void updateVoteForDisplay(int displayId) {
        @Temperature.ThrottlingStatus int localStatus;
        SparseArray<SurfaceControl.RefreshRateRange> localMap;

        synchronized (mThermalObserverLock) {
            localStatus = mStatus;
            localMap = mThermalThrottlingByDisplay.get(displayId);
        }
        if (localMap == null) {
            Slog.d(TAG, "Updating votes, display already removed, display=" + displayId);
            return;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "Updating votes for status=" + localStatus + ", display =" + displayId
                    + ", map=" + localMap);
        }
        reportThrottlingIfNeeded(displayId, localStatus, localMap);
    }

    private void reportThrottlingIfNeeded(int displayId,
            @Temperature.ThrottlingStatus int currentStatus,
            SparseArray<SurfaceControl.RefreshRateRange> throttlingMap) {
        if (currentStatus == -1) { // no throttling status reported from thermal sensor yet
            return;
        }

        if (throttlingMap.size() == 0) { // map is not configured, using default behaviour
            fallbackReportThrottlingIfNeeded(displayId, currentStatus);
            return;
        }

        SurfaceControl.RefreshRateRange foundRange = findBestMatchingRefreshRateRange(currentStatus,
                throttlingMap);
        // if status <= currentStatus not found in the map reset vote
        Vote vote = null;
        if (foundRange != null) { // otherwise vote with found range
            vote = Vote.forRenderFrameRates(foundRange.min, foundRange.max);
        }
        mVotesStorage.updateVote(displayId, Vote.PRIORITY_SKIN_TEMPERATURE, vote);
        if (mLoggingEnabled) {
            Slog.d(TAG, "Voted: vote=" + vote + ", display =" + displayId);
        }
    }

    private void fallbackReportThrottlingIfNeeded(int displayId,
            @Temperature.ThrottlingStatus int currentStatus) {
        Vote vote = null;
        if (currentStatus >= Temperature.THROTTLING_CRITICAL) {
            vote = Vote.forRenderFrameRates(0f, 60f);
        }
        mVotesStorage.updateVote(displayId, Vote.PRIORITY_SKIN_TEMPERATURE, vote);
        if (mLoggingEnabled) {
            Slog.d(TAG, "Voted(fallback): vote=" + vote + ", display =" + displayId);
        }
    }
    //endregion

    void dumpLocked(PrintWriter writer) {
        @Temperature.ThrottlingStatus int localStatus;
        SparseArray<SparseArray<SurfaceControl.RefreshRateRange>> localMap;

        synchronized (mThermalObserverLock) {
            localStatus = mStatus;
            localMap = mThermalThrottlingByDisplay.clone();
        }

        writer.println("  SkinThermalStatusObserver:");
        writer.println("    mStatus: " + localStatus);
        writer.println("    mThermalThrottlingByDisplay: " + localMap);
    }
}
