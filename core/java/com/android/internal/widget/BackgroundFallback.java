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


package com.android.internal.widget;

import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * Helper class for drawing a fallback background in framework decor layouts.
 * Useful for when an app has not set a window background but we're asked to draw
 * an uncovered area.
 */
public class BackgroundFallback {
    private Drawable mBackgroundFallback;

    public void setDrawable(Drawable d) {
        mBackgroundFallback = d;
    }

    public boolean hasFallback() {
        return mBackgroundFallback != null;
    }

    public void draw(ViewGroup root, Canvas c, View content) {
        if (!hasFallback()) {
            return;
        }

        // Draw the fallback in the padding.
        final int width = root.getWidth();
        final int height = root.getHeight();
        int left = width;
        int top = height;
        int right = 0;
        int bottom = 0;

        final int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = root.getChildAt(i);
            final Drawable childBg = child.getBackground();
            if (child == content) {
                // We always count the content view container unless it has no background
                // and no children.
                if (childBg == null && child instanceof ViewGroup &&
                        ((ViewGroup) child).getChildCount() == 0) {
                    continue;
                }
            } else if (child.getVisibility() != View.VISIBLE || childBg == null ||
                    childBg.getOpacity() != PixelFormat.OPAQUE) {
                // Potentially translucent or invisible children don't count, and we assume
                // the content view will cover the whole area if we're in a background
                // fallback situation.
                continue;
            }
            left = Math.min(left, child.getLeft());
            top = Math.min(top, child.getTop());
            right = Math.max(right, child.getRight());
            bottom = Math.max(bottom, child.getBottom());
        }

        if (left >= right || top >= bottom) {
            // No valid area to draw in.
            return;
        }

        if (top > 0) {
            mBackgroundFallback.setBounds(0, 0, width, top);
            mBackgroundFallback.draw(c);
        }
        if (left > 0) {
            mBackgroundFallback.setBounds(0, top, left, height);
            mBackgroundFallback.draw(c);
        }
        if (right < width) {
            mBackgroundFallback.setBounds(right, top, width, height);
            mBackgroundFallback.draw(c);
        }
        if (bottom < height) {
            mBackgroundFallback.setBounds(left, bottom, right, height);
            mBackgroundFallback.draw(c);
        }
    }
}
