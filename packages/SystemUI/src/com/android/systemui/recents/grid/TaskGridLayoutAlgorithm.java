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
import java.util.Arrays;
import java.util.List;

class TaskGridLayoutAlgorithm {

    public enum VerticalGravity {
        START, END, CENTER
    }

    public static final List<Integer> ZERO_MARGIN = new ArrayList<>();
    static {
        Integer[] zero = {0, 0, 0, 0};
        ZERO_MARGIN.addAll(Arrays.asList(zero));
    }
    private static final String TAG = "TaskGridLayoutAlgorithm";

    /**
     * Calculates the adequate rectangles for the specified number of tasks to be layed out on
     * the screen.
     * @param count The number of task views to layout.
     * @param containerWidth The width of the whole area containing those tasks.
     * @param containerHeight The height of the whole area containing those tasks.
     * @param screenRatio The ratio of the device's screen, so that tasks have the same aspect
     *         ratio (ignoring the title bar).
     * @param padding The amount of padding, in pixels, in between task views.
     * @param margins The amount of space to be left blank around the area on the left, top, right
     *         and bottom.
     * @param titleBarHeight The height, in pixels, of the task views title bar.
     * @return A list of rectangles to be used for layout.
     */
    static ArrayList<Rect> getRectsForTaskCount(int count, int containerWidth, int containerHeight,
            float screenRatio, int padding, List<Integer> margins, int titleBarHeight) {
        return getRectsForTaskCount(count, containerWidth, containerHeight,  screenRatio, padding,
                margins, titleBarHeight, null, VerticalGravity.CENTER);
    }

    private static ArrayList<Rect> getRectsForTaskCount(int count, int containerWidth,
            int containerHeight, float screenRatio, int padding, List<Integer> margins,
            int titleBarHeight, Rect preCalculatedTile, VerticalGravity gravity) {
        ArrayList<Rect> rects = new ArrayList<>(count);
        boolean landscape = (containerWidth > containerHeight);
        containerWidth -= margins.get(0) + margins.get(2);
        containerHeight -= margins.get(1) + margins.get(3);

        // We support at most 9 tasks in this layout.
        count = Math.min(count, RecentsGridActivity.MAX_VISIBLE_TASKS);

        if (count == 0) {
            return rects;
        }
        if (count <= 3) {
            // Base case: single line.
            int taskWidth, taskHeight;
            if (preCalculatedTile != null) {
                taskWidth = preCalculatedTile.width();
                taskHeight = preCalculatedTile.height();
            } else {
                // Divide available width in equal parts.
                int maxTaskWidth = (containerWidth - (count - 1) * padding) / count;
                int maxTaskHeight = containerHeight;
                if (maxTaskHeight >= maxTaskWidth / screenRatio + titleBarHeight) {
                    // Width bound.
                    taskWidth = maxTaskWidth;
                    taskHeight = (int) (maxTaskWidth / screenRatio + titleBarHeight);
                } else {
                    // Height bound.
                    taskHeight = maxTaskHeight;
                    taskWidth = (int) ((taskHeight - titleBarHeight) * screenRatio);
                }
            }
            int emptySpaceX = containerWidth - (count * taskWidth) - (count - 1) * padding;
            int emptySpaceY = containerHeight - taskHeight;
            for (int i = 0; i < count; i++) {
                int left = emptySpaceX / 2 + i * taskWidth + i * padding;
                int top;
                switch (gravity) {
                    case CENTER:
                        top = emptySpaceY / 2;
                        break;
                    case END:
                        top = emptySpaceY;
                        break;
                    case START:
                    default:
                        top = 0;
                        break;
                }
                Rect rect = new Rect(left, top, left + taskWidth, top + taskHeight);
                rect.offset(margins.get(0), margins.get(1));
                rects.add(rect);
            }
        } else if (count < 7) {
            // Two lines.
            int lineHeight = (containerHeight - padding) / 2;
            int lineTaskCount = (int) Math.ceil((double) count / 2);
            List<Rect> rectsA = getRectsForTaskCount(lineTaskCount, containerWidth, lineHeight,
                    screenRatio, padding, ZERO_MARGIN, titleBarHeight, null, VerticalGravity.END);
            List<Rect> rectsB = getRectsForTaskCount(count - lineTaskCount, containerWidth,
                    lineHeight, screenRatio, padding, ZERO_MARGIN, titleBarHeight, rectsA.get(0),
                    VerticalGravity.START);
            for (int i = 0; i < rectsA.size(); i++) {
                rectsA.get(i).offset(margins.get(0), margins.get(1));
            }
            for (int i = 0; i < rectsB.size(); i++) {
                rectsB.get(i).offset(margins.get(0), margins.get(1) + lineHeight + padding);
            }
            rects.addAll(rectsA);
            rects.addAll(rectsB);
        } else {
            // Three lines.
            int lineHeight = (containerHeight - 2 * padding) / 3;
            int lineTaskCount = (int) Math.ceil((double) count / 3);
            List<Rect> rectsA = getRectsForTaskCount(lineTaskCount, containerWidth, lineHeight,
                    screenRatio, padding, ZERO_MARGIN, titleBarHeight, null, VerticalGravity.END);
            List<Rect> rectsB = getRectsForTaskCount(lineTaskCount, containerWidth, lineHeight,
                    screenRatio,  padding, ZERO_MARGIN, titleBarHeight, rectsA.get(0),
                    VerticalGravity.END);
            List<Rect> rectsC = getRectsForTaskCount(count - (2 * lineTaskCount), containerWidth,
                    lineHeight, screenRatio, padding, ZERO_MARGIN, titleBarHeight, rectsA.get(0),
                    VerticalGravity.START);
            for (int i = 0; i < rectsA.size(); i++) {
                rectsA.get(i).offset(margins.get(0), margins.get(1));
            }
            for (int i = 0; i < rectsB.size(); i++) {
                rectsB.get(i).offset(margins.get(0), margins.get(1) + lineHeight + padding);
            }
            for (int i = 0; i < rectsC.size(); i++) {
                rectsC.get(i).offset(margins.get(0), margins.get(1) + 2 * (lineHeight + padding));
            }
            rects.addAll(rectsA);
            rects.addAll(rectsB);
            rects.addAll(rectsC);
        }
        return rects;
    }
}
