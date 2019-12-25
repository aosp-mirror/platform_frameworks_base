/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.wm;

import android.graphics.Rect;
import android.graphics.Region;
import android.util.SparseArray;

/**
 * A holder that contains a collection of regions identified by int id. Each individual region can
 * be updated separately.
 */
class TapExcludeRegionHolder {
    private SparseArray<Region> mTapExcludeRegions = new SparseArray<>();

    /** Update the specified region with provided position and size. */
    void updateRegion(int regionId, Region region) {
        // Remove the previous one because there is a new one incoming.
        mTapExcludeRegions.remove(regionId);

        if (region == null || region.isEmpty()) {
            // The incoming region is invalid. Don't use it.
            return;
        }

        mTapExcludeRegions.put(regionId, region);
    }

    /**
     * Union the provided region with current region formed by this container.
     */
    void amendRegion(Region region, Rect bounds) {
        for (int i = mTapExcludeRegions.size() - 1; i >= 0; --i) {
            final Region r = mTapExcludeRegions.valueAt(i);
            if (bounds != null) {
                r.op(bounds, Region.Op.INTERSECT);
            }
            region.op(r, Region.Op.UNION);
        }
    }

    /**
     * Return true if tap exclude region is empty.
     */
    boolean isEmpty() {
        return mTapExcludeRegions.size() == 0;
    }
}
