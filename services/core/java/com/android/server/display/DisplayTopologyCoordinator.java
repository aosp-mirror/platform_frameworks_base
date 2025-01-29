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

import static android.hardware.display.DisplayTopology.pxToDp;

import android.hardware.display.DisplayTopology;
import android.hardware.display.DisplayTopologyGraph;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Manages the relative placement (topology) of extended displays. Responsible for updating and
 * persisting the topology.
 */
class DisplayTopologyCoordinator {
    private static final String TAG = "DisplayTopologyCoordinator";

    private static String getUniqueId(DisplayInfo info) {
        if (info.displayId == Display.DEFAULT_DISPLAY && info.type == Display.TYPE_INTERNAL) {
            return "internal";
        }
        return info.uniqueId;
    }

    // Persistent data store for display topologies.
    private final DisplayTopologyStore mTopologyStore;

    @GuardedBy("mSyncRoot")
    private DisplayTopology mTopology;

    // Map from logical display ID to logical display density. Should always be consistent with
    // mTopology.
    @GuardedBy("mSyncRoot")
    private final SparseIntArray mDensities = new SparseIntArray();

    @GuardedBy("mSyncRoot")
    private final Map<String, Integer> mUniqueIdToDisplayIdMapping = new HashMap<>();

    @GuardedBy("mSyncRoot")
    private final SparseArray<String> mDisplayIdToUniqueIdMapping = new SparseArray<>();

    /**
     * Check if extended displays are enabled. If not, a topology is not needed.
     */
    private final BooleanSupplier mIsExtendedDisplayEnabled;

    /**
     * Callback used to send topology updates.
     * Should be invoked from the corresponding executor.
     * A copy of the topology should be sent that will not be modified by the system.
     */
    private final Consumer<Pair<DisplayTopology, DisplayTopologyGraph>> mOnTopologyChangedCallback;
    private final Executor mTopologyChangeExecutor;
    private final DisplayManagerService.SyncRoot mSyncRoot;
    private final Runnable mTopologySavedCallback;

    DisplayTopologyCoordinator(BooleanSupplier isExtendedDisplayEnabled,
            Consumer<Pair<DisplayTopology, DisplayTopologyGraph>> onTopologyChangedCallback,
            Executor topologyChangeExecutor, DisplayManagerService.SyncRoot syncRoot,
            Runnable topologySavedCallback) {
        this(new Injector(), isExtendedDisplayEnabled, onTopologyChangedCallback,
                topologyChangeExecutor, syncRoot, topologySavedCallback);
    }

    @VisibleForTesting
    DisplayTopologyCoordinator(Injector injector, BooleanSupplier isExtendedDisplayEnabled,
            Consumer<Pair<DisplayTopology, DisplayTopologyGraph>> onTopologyChangedCallback,
            Executor topologyChangeExecutor, DisplayManagerService.SyncRoot syncRoot,
            Runnable topologySavedCallback) {
        mTopology = injector.getTopology();
        mIsExtendedDisplayEnabled = isExtendedDisplayEnabled;
        mOnTopologyChangedCallback = onTopologyChangedCallback;
        mTopologyChangeExecutor = topologyChangeExecutor;
        mSyncRoot = syncRoot;
        mTopologyStore = injector.createTopologyStore(
                mDisplayIdToUniqueIdMapping, mUniqueIdToDisplayIdMapping);
        mTopologySavedCallback = topologySavedCallback;
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
            addDisplayIdMappingLocked(info);
            mDensities.put(info.displayId, info.logicalDensityDpi);
            mTopology.addDisplay(info.displayId, getWidth(info), getHeight(info));
            restoreTopologyLocked();
            sendTopologyUpdateLocked();
        }
    }

    /**
     * Update the topology with display changes.
     * @param info The new display info
     */
    void onDisplayChanged(DisplayInfo info) {
        if (!isDisplayAllowedInTopology(info)) {
            return;
        }
        synchronized (mSyncRoot) {
            if (mDensities.indexOfKey(info.displayId) >= 0) {
                mDensities.put(info.displayId, info.logicalDensityDpi);
            }
            if (mTopology.updateDisplay(info.displayId, getWidth(info), getHeight(info))) {
                sendTopologyUpdateLocked();
            }
        }
    }

    /**
     * Remove a display from the topology.
     * @param displayId The logical display ID
     */
    void onDisplayRemoved(int displayId) {
        synchronized (mSyncRoot) {
            mDensities.delete(displayId);
            if (mTopology.removeDisplay(displayId)) {
                removeDisplayIdMappingLocked(displayId);
                restoreTopologyLocked();
                sendTopologyUpdateLocked();
            }
        }
    }

    /**
     * Loads all topologies from the persistent topology store for the given userId.
     * @param userId the user id, same as returned from
     *              {@link android.app.ActivityManagerInternal#getCurrentUserId()}.
     * @param isUserSwitching whether the id of the user is currently switching.
     */
    void reloadTopologies(int userId, boolean isUserSwitching) {
        boolean isTopologySaved = false;
        synchronized (mSyncRoot) {
            mTopologyStore.reloadTopologies(userId);
            boolean isTopologyRestored = restoreTopologyLocked();
            if (isTopologyRestored) {
                sendTopologyUpdateLocked();
            }
            if (isUserSwitching && !isTopologyRestored) {
                // During user switch, if topology is not restored - last user topology is the
                // good initial guess. Save this topology for consistent use in the future.
                isTopologySaved = mTopologyStore.saveTopology(mTopology);
            }
        }

        if (isTopologySaved) {
            mTopologySavedCallback.run();
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
        final boolean isTopologySaved;
        synchronized (mSyncRoot) {
            topology.normalize();
            mTopology = topology;
            sendTopologyUpdateLocked();
            isTopologySaved = mTopologyStore.saveTopology(topology);
        }

        if (isTopologySaved) {
            mTopologySavedCallback.run();
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

    @GuardedBy("mSyncRoot")
    private void removeDisplayIdMappingLocked(final int displayId) {
        final String uniqueId = mDisplayIdToUniqueIdMapping.get(displayId);
        if (null == uniqueId) {
            Slog.e(TAG, "Can't find uniqueId for displayId=" + displayId);
            return;
        }
        mDisplayIdToUniqueIdMapping.remove(displayId);
        mUniqueIdToDisplayIdMapping.remove(uniqueId);
    }

    @GuardedBy("mSyncRoot")
    private void addDisplayIdMappingLocked(DisplayInfo info) {
        final String uniqueId = getUniqueId(info);
        mUniqueIdToDisplayIdMapping.put(uniqueId, info.displayId);
        mDisplayIdToUniqueIdMapping.put(info.displayId, uniqueId);
    }

    /**
     * @param info The display info
     * @return The width of the display in dp
     */
    private float getWidth(DisplayInfo info) {
        return pxToDp(info.logicalWidth, info.logicalDensityDpi);
    }

    /**
     * @param info The display info
     * @return The height of the display in dp
     */
    private float getHeight(DisplayInfo info) {
        return pxToDp(info.logicalHeight, info.logicalDensityDpi);
    }

    private boolean isDisplayAllowedInTopology(DisplayInfo info) {
        if (info.type != Display.TYPE_INTERNAL && info.type != Display.TYPE_EXTERNAL
                && info.type != Display.TYPE_OVERLAY) {
            Slog.d(TAG, "Display " + info.displayId + " not allowed in topology because "
                    + "type is not INTERNAL, EXTERNAL or OVERLAY");
            return false;
        }
        if (info.type == Display.TYPE_INTERNAL && info.displayId != Display.DEFAULT_DISPLAY) {
            Slog.d(TAG, "Display " + info.displayId + " not allowed in topology because "
                    + "it is a non-default internal display");
            return false;
        }
        if ((info.type == Display.TYPE_EXTERNAL || info.type == Display.TYPE_OVERLAY)
                && !mIsExtendedDisplayEnabled.getAsBoolean()) {
            Slog.d(TAG, "Display " + info.displayId + " not allowed in topology because "
                    + "type is EXTERNAL or OVERLAY and !mIsExtendedDisplayEnabled");
            return false;
        }
        if (info.displayGroupId != Display.DEFAULT_DISPLAY_GROUP) {
            Slog.d(TAG, "Display " + info.displayId + " not allowed in topology because "
                    + "it is not in the default display group");
            return false;
        }
        return true;
    }

    /**
     * Restores {@link #mTopology} from {@link #mTopologyStore}, saves it in {@link #mTopology}.
     * @return true if the topology was restored, false otherwise.
     */
    @GuardedBy("mSyncRoot")
    private boolean restoreTopologyLocked() {
        var restoredTopology = mTopologyStore.restoreTopology(mTopology);
        if (restoredTopology == null) {
            return false;
        }
        mTopology = restoredTopology;
        mTopology.normalize();
        return true;
    }

    @GuardedBy("mSyncRoot")
    private void sendTopologyUpdateLocked() {
        DisplayTopology copy = mTopology.copy();
        SparseIntArray densities = mDensities.clone();
        mTopologyChangeExecutor.execute(() -> mOnTopologyChangedCallback.accept(
                new Pair<>(copy, copy.getGraph(densities))));
    }

    @VisibleForTesting
    static class Injector {
        DisplayTopology getTopology() {
            return new DisplayTopology();
        }

        DisplayTopologyStore createTopologyStore(
                SparseArray<String> displayIdToUniqueIdMapping,
                Map<String, Integer> uniqueIdToDisplayIdMapping) {
            return new DisplayTopologyXmlStore(new DisplayTopologyXmlStore.Injector() {
                @Override
                public SparseArray<String> getDisplayIdToUniqueIdMapping() {
                    return displayIdToUniqueIdMapping;
                }

                @Override
                public Map<String, Integer> getUniqueIdToDisplayIdMapping() {
                    return uniqueIdToDisplayIdMapping;
                }
            });
        }
    }
}
