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

package com.android.server.wm.utils;

import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RegionIterator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Utility methods to handle Regions.
 */
public class RegionUtils {

    private RegionUtils() {}


    /**
     * Converts a list of rects into a {@code Region}.
     *
     * @param rects the list of rects to convert
     * @param outRegion the Region to set to the list of rects
     */
    public static void rectListToRegion(List<Rect> rects, Region outRegion) {
        outRegion.setEmpty();
        final int n = rects.size();
        for (int i = 0; i < n; i++) {
            outRegion.union(rects.get(i));
        }
    }

    /**
     * Applies actions on each rect contained within a {@code Region}.
     *
     * Order is bottom to top, then right to left.
     *
     * @param region the given region.
     * @param rectConsumer the action holder.
     */
    public static void forEachRectReverse(Region region, Consumer<Rect> rectConsumer) {
        final RegionIterator it = new RegionIterator(region);
        final ArrayList<Rect> rects = new ArrayList<>();
        final Rect rect = new Rect();
        while (it.next(rect)) {
            rects.add(new Rect(rect));
        }
        // TODO: instead of creating an array and reversing it, expose the reverse iterator through
        //       JNI.
        Collections.reverse(rects);
        rects.forEach(rectConsumer);
    }
}
