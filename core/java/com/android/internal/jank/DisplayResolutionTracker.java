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

package com.android.internal.jank;

import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__FHD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__HD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__QHD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__SD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__UNKNOWN_RESOLUTION;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.util.SparseArray;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
* A class that tracks the display resolutions.
* @hide
*/
public class DisplayResolutionTracker {
    private static final String TAG = DisplayResolutionTracker.class.getSimpleName();

    public static final int RESOLUTION_UNKNOWN =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__UNKNOWN_RESOLUTION;
    public static final int RESOLUTION_SD =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__SD;
    public static final int RESOLUTION_HD =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__HD;
    public static final int RESOLUTION_FHD =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__FHD;
    public static final int RESOLUTION_QHD =
            UIINTERACTION_FRAME_INFO_REPORTED__DISPLAY_RESOLUTION__QHD;

    /** @hide */
    @IntDef({
        RESOLUTION_UNKNOWN,
        RESOLUTION_SD,
        RESOLUTION_HD,
        RESOLUTION_FHD,
        RESOLUTION_QHD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Resolution {
    }

    private final DisplayInterface mManager;
    private final SparseArray<Integer> mResolutions = new SparseArray<>();
    private final Object mLock = new Object();

    public DisplayResolutionTracker(@Nullable Handler handler) {
        this(DisplayInterface.getDefault(handler));
    }

    @VisibleForTesting
    public DisplayResolutionTracker(DisplayInterface manager) {
        mManager = manager;
        mManager.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                updateDisplay(displayId);
            }

            @Override
            public void onDisplayChanged(int displayId) {
                updateDisplay(displayId);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                // Not in the event mask below, won't be called.
            }
        });
    }

    private void updateDisplay(int displayId) {
        DisplayInfo info = mManager.getDisplayInfo(displayId);
        if (info == null) {
            return;
        }
        @Resolution int resolution = getResolution(info);

        synchronized (mLock) {
            mResolutions.put(displayId, resolution);
        }
    }

    /**
     * Returns the (cached) resolution of the display with the given ID.
     */
    @Resolution
    public int getResolution(int displayId) {
        return mResolutions.get(displayId, RESOLUTION_UNKNOWN);
    }

    /**
     * Returns the resolution of the given {@link DisplayInfo}.
     */
    @VisibleForTesting
    @Resolution
    public static int getResolution(DisplayInfo info) {
        int smaller = Math.min(info.logicalWidth, info.logicalHeight);
        int larger = Math.max(info.logicalWidth, info.logicalHeight);
        if (smaller < 720 || larger < 1280) {
            return RESOLUTION_SD;
        } else if (smaller < 1080 || larger < 1920) {
            return RESOLUTION_HD;
        } else if (smaller < 1440 || larger < 2560) {
            return RESOLUTION_FHD;
        } else {
            return RESOLUTION_QHD;
        }
    }

    /**
     * Wrapper around the final {@link DisplayManagerGlobal} class.
     * @hide
     */
    @VisibleForTesting
    public interface DisplayInterface {
        /** Reurns an implementation wrapping {@link DisplayManagerGlobal}. */
        static DisplayInterface getDefault(@Nullable Handler handler) {
            DisplayManagerGlobal manager = DisplayManagerGlobal.getInstance();
            return new DisplayInterface() {
                @Override
                public void registerDisplayListener(DisplayManager.DisplayListener listener) {
                    manager.registerDisplayListener(listener, handler,
                            DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                                    | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED,
                            ActivityThread.currentPackageName());
                }

                @Override
                public DisplayInfo getDisplayInfo(int displayId) {
                    return manager.getDisplayInfo(displayId);
                }
            };
        }

        /** {@see DisplayManagerGlobal#registerDisplayListener} */
        void registerDisplayListener(DisplayManager.DisplayListener listener);
        /** {@see DisplayManagerGlobal#getDisplayInfo} */
        DisplayInfo getDisplayInfo(int displayId);
    }
}
