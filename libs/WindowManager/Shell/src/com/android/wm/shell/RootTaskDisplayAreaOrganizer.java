/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell;

import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;

import androidx.annotation.NonNull;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Display area organizer for the root/default TaskDisplayAreas */
public class RootTaskDisplayAreaOrganizer extends DisplayAreaOrganizer {

    private static final String TAG = RootTaskDisplayAreaOrganizer.class.getSimpleName();

    // Display area info. mapped by displayIds.
    private final SparseArray<DisplayAreaInfo> mDisplayAreasInfo = new SparseArray<>();
    // Display area leashes. mapped by displayIds.
    private final SparseArray<SurfaceControl> mLeashes = new SparseArray<>();

    private final SparseArray<ArrayList<RootTaskDisplayAreaListener>> mListeners =
            new SparseArray<>();

    public RootTaskDisplayAreaOrganizer(Executor executor) {
        super(executor);
        registerOrganizer(FEATURE_DEFAULT_TASK_CONTAINER);
    }

    public void registerListener(int displayId, RootTaskDisplayAreaListener listener) {
        ArrayList<RootTaskDisplayAreaListener> listeners = mListeners.get(displayId);
        if (listeners == null) {
            listeners = new ArrayList<>();
            mListeners.put(displayId, listeners);
        }

        listeners.add(listener);

        final DisplayAreaInfo info = mDisplayAreasInfo.get(displayId);
        if (info != null) {
            listener.onDisplayAreaAppeared(info);
        }
    }

    public void unregisterListener(RootTaskDisplayAreaListener listener) {
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            final List<RootTaskDisplayAreaListener> listeners = mListeners.valueAt(i);
            if (listeners == null) continue;
            listeners.remove(listener);
        }
    }

    public void attachToDisplayArea(int displayId, SurfaceControl.Builder b) {
        final SurfaceControl sc = mLeashes.get(displayId);
        b.setParent(sc);
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        if (displayAreaInfo.featureId != FEATURE_DEFAULT_TASK_CONTAINER) {
            throw new IllegalArgumentException(
                    "Unknown feature: " + displayAreaInfo.featureId
                            + "displayAreaInfo:" + displayAreaInfo);
        }

        final int displayId = displayAreaInfo.displayId;
        if (mDisplayAreasInfo.get(displayId) != null) {
            throw new IllegalArgumentException(
                    "Duplicate DA for displayId: " + displayId
                            + " displayAreaInfo:" + displayAreaInfo
                            + " mDisplayAreasInfo.get():" + mDisplayAreasInfo.get(displayId));
        }

        mDisplayAreasInfo.put(displayId, displayAreaInfo);

        ArrayList<RootTaskDisplayAreaListener> listeners = mListeners.get(displayId);
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; --i) {
                listeners.get(i).onDisplayAreaAppeared(displayAreaInfo);
            }
        }
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        final int displayId = displayAreaInfo.displayId;
        if (mDisplayAreasInfo.get(displayId) == null) {
            throw new IllegalArgumentException(
                    "onDisplayAreaVanished() Unknown DA displayId: " + displayId
                            + " displayAreaInfo:" + displayAreaInfo
                            + " mDisplayAreasInfo.get():" + mDisplayAreasInfo.get(displayId));
        }

        mDisplayAreasInfo.remove(displayId);

        ArrayList<RootTaskDisplayAreaListener> listeners = mListeners.get(displayId);
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; --i) {
                listeners.get(i).onDisplayAreaVanished(displayAreaInfo);
            }
        }
    }

    @Override
    public void onDisplayAreaInfoChanged(@NonNull DisplayAreaInfo displayAreaInfo) {
        final int displayId = displayAreaInfo.displayId;
        if (mDisplayAreasInfo.get(displayId) == null) {
            throw new IllegalArgumentException(
                    "onDisplayAreaInfoChanged() Unknown DA displayId: " + displayId
                            + " displayAreaInfo:" + displayAreaInfo
                            + " mDisplayAreasInfo.get():" + mDisplayAreasInfo.get(displayId));
        }

        mDisplayAreasInfo.put(displayId, displayAreaInfo);

        ArrayList<RootTaskDisplayAreaListener> listeners = mListeners.get(displayId);
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; --i) {
                listeners.get(i).onDisplayAreaInfoChanged(displayAreaInfo);
            }
        }
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
    }

    @Override
    public String toString() {
        return TAG + "#" + mDisplayAreasInfo.size();
    }

    /** Callbacks for when root task display areas change. */
    public interface RootTaskDisplayAreaListener {
        default void onDisplayAreaAppeared(DisplayAreaInfo displayAreaInfo) {
        }

        default void onDisplayAreaVanished(DisplayAreaInfo displayAreaInfo) {
        }

        default void onDisplayAreaInfoChanged(DisplayAreaInfo displayAreaInfo) {
        }

        default void dump(@NonNull PrintWriter pw, String prefix) {
        }
    }
}