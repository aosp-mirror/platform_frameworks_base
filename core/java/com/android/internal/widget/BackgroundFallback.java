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

import android.annotation.Nullable;
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

    public @Nullable Drawable getDrawable() {
        return mBackgroundFallback;
    }

    public boolean hasFallback() {
        return mBackgroundFallback != null;
    }

    /**
     * Draws the fallback background.
     *
     * @param boundsView The view determining with which bounds the background should be drawn.
     * @param root The view group containing the content.
     * @param c The canvas to draw the background onto.
     * @param content The view where the actual app content is contained in.
     * @param coveringView1 A potentially opaque view drawn atop the content
     * @param coveringView2 A potentially opaque view drawn atop the content
     */
    public void draw(ViewGroup boundsView, ViewGroup root, Canvas c, View content,
            View coveringView1, View coveringView2) {
        if (!hasFallback()) {
            return;
        }

        // Draw the fallback in the padding.
        final int width = boundsView.getWidth();
        final int height = boundsView.getHeight();

        final int rootOffsetX = root.getLeft();
        final int rootOffsetY = root.getTop();

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
            } else if (child.getVisibility() != View.VISIBLE || !isOpaque(childBg)) {
                // Potentially translucent or invisible children don't count, and we assume
                // the content view will cover the whole area if we're in a background
                // fallback situation.
                continue;
            }
            left = Math.min(left, rootOffsetX + child.getLeft());
            top = Math.min(top, rootOffsetY + child.getTop());
            right = Math.max(right, rootOffsetX + child.getRight());
            bottom = Math.max(bottom, rootOffsetY + child.getBottom());
        }

        // If one of the bar backgrounds is a solid color and covers the entire padding on a side
        // we can drop that padding.
        boolean eachBarCoversTopInY = true;
        for (int i = 0; i < 2; i++) {
            View v = (i == 0) ? coveringView1 : coveringView2;
            if (v == null || v.getVisibility() != View.VISIBLE
                    || v.getAlpha() != 1f || !isOpaque(v.getBackground())) {
                eachBarCoversTopInY = false;
                continue;
            }

            // Bar covers entire left padding
            if (v.getTop() <= 0 && v.getBottom() >= height
                    && v.getLeft() <= 0 && v.getRight() >= left) {
                left = 0;
            }
            // Bar covers entire right padding
            if (v.getTop() <= 0 && v.getBottom() >= height
                    && v.getLeft() <= right && v.getRight() >= width) {
                right = width;
            }
            // Bar covers entire top padding
            if (v.getTop() <= 0 && v.getBottom() >= top
                    && v.getLeft() <= 0 && v.getRight() >= width) {
                top = 0;
            }
            // Bar covers entire bottom padding
            if (v.getTop() <= bottom && v.getBottom() >= height
                    && v.getLeft() <= 0 && v.getRight() >= width) {
                bottom = height;
            }

            eachBarCoversTopInY &= v.getTop() <= 0 && v.getBottom() >= top;
        }

        // Special case: Sometimes, both covering views together may cover the top inset, but
        // neither does on its own.
        if (eachBarCoversTopInY && (viewsCoverEntireWidth(coveringView1, coveringView2, width)
                || viewsCoverEntireWidth(coveringView2, coveringView1, width))) {
            top = 0;
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

    private boolean isOpaque(Drawable childBg) {
        return childBg != null && childBg.getOpacity() == PixelFormat.OPAQUE;
    }

    /**
     * Returns true if {@code view1} starts before or on {@code 0} and extends at least
     * up to {@code view2}, and that view extends at least to {@code width}.
     *
     * @param view1 the first view to check if it covers the width
     * @param view2 the second view to check if it covers the width
     * @param width the width to check for
     * @return returns true if both views together cover the entire width (and view1 is to the left
     *         of view2)
     */
    private boolean viewsCoverEntireWidth(View view1, View view2, int width) {
        return view1.getLeft() <= 0
                && view1.getRight() >= view2.getLeft()
                && view2.getRight() >= width;
    }
}
