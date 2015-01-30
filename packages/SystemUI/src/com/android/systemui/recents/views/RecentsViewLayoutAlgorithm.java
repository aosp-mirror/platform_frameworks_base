/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.graphics.Rect;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.List;

/* The layout logic for the RecentsView. */
public class RecentsViewLayoutAlgorithm {

    RecentsConfiguration mConfig;

    public RecentsViewLayoutAlgorithm(RecentsConfiguration config) {
        mConfig = config;
    }

    /** Return the relative coordinate given coordinates in another size. */
    private int getRelativeCoordinate(int availableOffset, int availableSize, int otherCoord, int otherSize) {
        float relPos = (float) otherCoord / otherSize;
        return availableOffset + (int) (relPos * availableSize);
    }

    /**
     * Computes and returns the bounds that each of the stack views should take up.
     */
    List<Rect> computeStackRects(List<TaskStackView> stackViews, Rect availableBounds) {
        ArrayList<Rect> bounds = new ArrayList<Rect>(stackViews.size());
        int stackViewsCount = stackViews.size();
        for (int i = 0; i < stackViewsCount; i++) {
            TaskStack stack = stackViews.get(i).getStack();
            Rect sb = stack.stackBounds;
            Rect db = stack.displayBounds;
            Rect ab = availableBounds;
            bounds.add(new Rect(getRelativeCoordinate(ab.left, ab.width(), sb.left, db.width()),
                    getRelativeCoordinate(ab.top, ab.height(), sb.top, db.height()),
                    getRelativeCoordinate(ab.left, ab.width(), sb.right, db.width()),
                    getRelativeCoordinate(ab.top, ab.height(), sb.bottom, db.height())));
        }
        return bounds;
    }
}
