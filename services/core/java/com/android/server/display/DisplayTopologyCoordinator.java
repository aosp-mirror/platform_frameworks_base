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

import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.function.BooleanSupplier;

/**
 * Manages the relative placement (topology) of extended displays. Responsible for updating and
 * persisting the topology.
 */
class DisplayTopologyCoordinator {

    @GuardedBy("mLock")
    private final DisplayTopology mTopology;

    /**
     * Check if extended displays are enabled. If not, a topology is not needed.
     */
    private final BooleanSupplier mIsExtendedDisplayEnabled;

    private final Object mLock = new Object();

    DisplayTopologyCoordinator(BooleanSupplier isExtendedDisplayEnabled) {
        this(new Injector(), isExtendedDisplayEnabled);
    }

    @VisibleForTesting
    DisplayTopologyCoordinator(Injector injector, BooleanSupplier isExtendedDisplayEnabled) {
        mTopology = injector.getTopology();
        mIsExtendedDisplayEnabled = isExtendedDisplayEnabled;
    }

    /**
     * Add a display to the topology.
     * @param info The display info
     */
    void onDisplayAdded(DisplayInfo info) {
        if (!isDisplayAllowedInTopology(info)) {
            return;
        }
        synchronized (mLock) {
            mTopology.addDisplay(info.displayId, getWidth(info), getHeight(info));
        }
    }

    /**
     * Print the object's state and debug information into the given stream.
     * @param pw The stream to dump information to.
     */
    void dump(PrintWriter pw) {
        synchronized (mLock) {
            mTopology.dump(pw);
        }
    }

    /**
     * @param info The display info
     * @return The width of the display in dp
     */
    private double getWidth(DisplayInfo info) {
        return info.logicalWidth * (double) DisplayMetrics.DENSITY_DEFAULT
                / info.logicalDensityDpi;
    }

    /**
     * @param info The display info
     * @return The height of the display in dp
     */
    private double getHeight(DisplayInfo info) {
        return info.logicalHeight * (double) DisplayMetrics.DENSITY_DEFAULT
                / info.logicalDensityDpi;
    }

    private boolean isDisplayAllowedInTopology(DisplayInfo info) {
        return mIsExtendedDisplayEnabled.getAsBoolean()
                && info.displayGroupId == Display.DEFAULT_DISPLAY_GROUP;
    }

    static class Injector {
        DisplayTopology getTopology() {
            return new DisplayTopology();
        }
    }
}
