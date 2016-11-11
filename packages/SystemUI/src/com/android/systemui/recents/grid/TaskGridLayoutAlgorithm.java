/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.recents.grid;

import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

class TaskGridLayoutAlgorithm {

    private static final String TAG = "TaskGridLayoutAlgorithm";

    static List<Rect> getRectsForTaskCount(int count, int containerWidth, int containerHeight,
            boolean allowLineOfThree, int padding) {
        return getRectsForTaskCount(count, containerWidth, containerHeight, allowLineOfThree,
                padding, null);
    }

    static List<Rect> getRectsForTaskCount(int count, int containerWidth, int containerHeight,
            boolean allowLineOfThree, int padding, Rect preCalculatedTile) {
        int singleLineMaxCount = allowLineOfThree ? 3 : 2;
        List<Rect> rects = new ArrayList<>(count);
        boolean landscape = (containerWidth > containerHeight);

        // We support at most 9 tasks in this layout.
        count = Math.min(count, 9);

        if (count == 0) {
            return rects;
        }
        if (count <= singleLineMaxCount) {
            if (landscape) {
                // Single line.
                int taskWidth = 0;
                int emptySpace = 0;
                if (preCalculatedTile != null) {
                    taskWidth = preCalculatedTile.width();
                    emptySpace = containerWidth - (count * taskWidth) - (count - 1) * padding;
                } else {
                    // Divide available space in equal parts.
                    taskWidth = (containerWidth - (count - 1) * padding) / count;
                }
                for (int i = 0; i < count; i++) {
                    int left = emptySpace / 2 + i * taskWidth + i * padding;
                    rects.add(new Rect(left, 0, left + taskWidth, containerHeight));
                }
            } else {
                // Single column. Divide available space in equal parts.
                int taskHeight = (containerHeight - (count - 1) * padding) / count;
                for (int i = 0; i < count; i++) {
                    int top = i * taskHeight + i * padding;
                    rects.add(new Rect(0, top, containerWidth, top + taskHeight));
                }
            }
        } else if (count < 7) {
            // Two lines.
            int lineHeight = (containerHeight - padding) / 2;
            int lineTaskCount = (int) Math.ceil((double) count / 2);
            List<Rect> rectsA = getRectsForTaskCount(
                    lineTaskCount, containerWidth, lineHeight, true /* allowLineOfThree */, padding,
                            null);
            List<Rect> rectsB = getRectsForTaskCount(
                    count - lineTaskCount, containerWidth, lineHeight, true /* allowLineOfThree */,
                            padding, rectsA.get(0));
            for (Rect rect : rectsB) {
                rect.offset(0, lineHeight + padding);
            }
            rects.addAll(rectsA);
            rects.addAll(rectsB);
        } else {
            // Three lines.
            int lineHeight = (containerHeight - 2 * padding) / 3;
            int lineTaskCount = (int) Math.ceil((double) count / 3);
            List<Rect> rectsA = getRectsForTaskCount(
                lineTaskCount, containerWidth, lineHeight, true /* allowLineOfThree */, padding, null);
            List<Rect> rectsB = getRectsForTaskCount(
                lineTaskCount, containerWidth, lineHeight, true /* allowLineOfThree */, padding,
                        rectsA.get(0));
            List<Rect> rectsC = getRectsForTaskCount(
                count - (2 * lineTaskCount), containerWidth, lineHeight,
                         true /* allowLineOfThree */, padding, rectsA.get(0));
            for (Rect rect : rectsB) {
                rect.offset(0, lineHeight + padding);
            }
            for (Rect rect : rectsC) {
                rect.offset(0, 2 * (lineHeight + padding));
            }
            rects.addAll(rectsA);
            rects.addAll(rectsB);
            rects.addAll(rectsC);
        }
        return rects;
    }
}

