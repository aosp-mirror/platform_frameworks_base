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
 * A holder that contains a collection of rectangular areas identified by int id. Each individual
 * region can be updated separately.
 */
class TapExcludeRegionHolder {
    private SparseArray<Rect> mTapExcludeRects = new SparseArray<>();

    /** Update the specified region with provided position and size. */
    void updateRegion(int regionId, int left, int top, int width, int height) {
        if (width <= 0 || height <= 0) {
            // A region became empty - remove it.
            mTapExcludeRects.remove(regionId);
            return;
        }

        Rect region = mTapExcludeRects.get(regionId);
        if (region == null) {
            region = new Rect();
        }
        region.set(left, top, left + width, top + height);
        mTapExcludeRects.put(regionId, region);
    }

    /**
     * Union the provided region with current region formed by this container.
     */
    void amendRegion(Region region, Rect boundingRegion) {
        for (int i = mTapExcludeRects.size() - 1; i>= 0 ; --i) {
            final Rect rect = mTapExcludeRects.valueAt(i);
            rect.intersect(boundingRegion);
            region.union(rect);
        }
    }
}
