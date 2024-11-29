/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.hardware.display.DisplayTopology;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Manages the relative placement (topology) of extended displays. Responsible for updating and
 * persisting the topology.
 */
class DisplayTopologyCoordinator {

    @GuardedBy("mSyncRoot")
    private DisplayTopology mTopology;

    /**
     * Check if extended displays are enabled. If not, a topology is not needed.
     */
    private final BooleanSupplier mIsExtendedDisplayEnabled;

    /**
     * Callback used to send topology updates.
     * Should be invoked from the corresponding executor.
     * A copy of the topology should be sent that will not be modified by the system.
     */
    private final Consumer<DisplayTopology> mOnTopologyChangedCallback;
    private final Executor mTopologyChangeExecutor;
    private final DisplayManagerService.SyncRoot mSyncRoot;

    DisplayTopologyCoordinator(BooleanSupplier isExtendedDisplayEnabled,
            Consumer<DisplayTopology> onTopologyChangedCallback,
            Executor topologyChangeExecutor, DisplayManagerService.SyncRoot syncRoot) {
        this(new Injector(), isExtendedDisplayEnabled, onTopologyChangedCallback,
                topologyChangeExecutor, syncRoot);
    }

    @VisibleForTesting
    DisplayTopologyCoordinator(Injector injector, BooleanSupplier isExtendedDisplayEnabled,
            Consumer<DisplayTopology> onTopologyChangedCallback,
            Executor topologyChangeExecutor, DisplayManagerService.SyncRoot syncRoot) {
        mTopology = injector.getTopology();
        mIsExtendedDisplayEnabled = isExtendedDisplayEnabled;
        mOnTopologyChangedCallback = onTopologyChangedCallback;
        mTopologyChangeExecutor = topologyChangeExecutor;
        mSyncRoot = syncRoot;
    }

    /**
     * Add a display to the topology.
     * @param info The display info
     */
    void onDisplayAdded(DisplayInfo info) {
        if (!isDisplayAllowedInTopology(info)) {
            return;
        }
        synchronized (mSyncRoot) {
            mTopology.addDisplay(info.displayId, getWidth(info), getHeight(info));
            sendTopologyUpdateLocked();
        }
    }

    /**
     * Remove a display from the topology.
     * @param displayId The logical display ID
     */
    void onDisplayRemoved(int displayId) {
        synchronized (mSyncRoot) {
            mTopology.removeDisplay(displayId);
            sendTopologyUpdateLocked();
        }
    }

    /**
     * @return A deep copy of the topology.
     */
    DisplayTopology getTopology() {
        synchronized (mSyncRoot) {
            return mTopology.copy();
        }
    }

    void setTopology(DisplayTopology topology) {
        synchronized (mSyncRoot) {
            mTopology = topology;
            mTopology.normalize();
            sendTopologyUpdateLocked();
        }
    }

    /**
     * Print the object's state and debug information into the given stream.
     * @param pw The stream to dump information to.
     */
    void dump(PrintWriter pw) {
        synchronized (mSyncRoot) {
            mTopology.dump(pw);
        }
    }

    /**
     * @param info The display info
     * @return The width of the display in dp
     */
    private float getWidth(DisplayInfo info) {
        return info.logicalWidth * (float) DisplayMetrics.DENSITY_DEFAULT
                / info.logicalDensityDpi;
    }

    /**
     * @param info The display info
     * @return The height of the display in dp
     */
    private float getHeight(DisplayInfo info) {
        return info.logicalHeight * (float) DisplayMetrics.DENSITY_DEFAULT
                / info.logicalDensityDpi;
    }

    private boolean isDisplayAllowedInTopology(DisplayInfo info) {
        return mIsExtendedDisplayEnabled.getAsBoolean()
                && info.displayGroupId == Display.DEFAULT_DISPLAY_GROUP;
    }

    @GuardedBy("mSyncRoot")
    private void sendTopologyUpdateLocked() {
        DisplayTopology copy = mTopology.copy();
        mTopologyChangeExecutor.execute(() -> mOnTopologyChangedCallback.accept(copy));
    }

    @VisibleForTesting
    static class Injector {
        DisplayTopology getTopology() {
            return new DisplayTopology();
        }
    }
}
