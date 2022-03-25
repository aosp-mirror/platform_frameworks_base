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
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;

import androidx.annotation.NonNull;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Executor;

/** Display area organizer for the root display areas */
public class RootDisplayAreaOrganizer extends DisplayAreaOrganizer {

    private static final String TAG = RootDisplayAreaOrganizer.class.getSimpleName();

    /** {@link DisplayAreaInfo} list, which is mapped by display IDs. */
    private final SparseArray<DisplayAreaInfo> mDisplayAreasInfo = new SparseArray<>();
    /** Display area leashes, which is mapped by display IDs. */
    private final SparseArray<SurfaceControl> mLeashes = new SparseArray<>();

    public RootDisplayAreaOrganizer(Executor executor) {
        super(executor);
        List<DisplayAreaAppearedInfo> infos = registerOrganizer(FEATURE_ROOT);
        for (int i = infos.size() - 1; i >= 0; --i) {
            onDisplayAreaAppeared(infos.get(i).getDisplayAreaInfo(), infos.get(i).getLeash());
        }
    }

    public void attachToDisplayArea(int displayId, SurfaceControl.Builder b) {
        final SurfaceControl sc = mLeashes.get(displayId);
        if (sc != null) {
            b.setParent(sc);
        }
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        if (displayAreaInfo.featureId != FEATURE_ROOT) {
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
        mLeashes.put(displayId, leash);
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

}
