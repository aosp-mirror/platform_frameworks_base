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

package com.android.internal.widget;

import android.annotation.AttrRes;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.R;

/**
 * Special implementation of linear layout that's capable of laying out alert
 * dialog components.
 * <p>
 * A dialog consists of up to three panels. All panels are optional, and a
 * dialog may contain only a single panel. The panels are laid out according
 * to the following guidelines:
 * <ul>
 *     <li>topPanel: exactly wrap_content</li>
 *     <li>contentPanel OR customPanel: at most fill_parent, first priority for
 *         extra space</li>
 *     <li>buttonPanel: at least minHeight, at most wrap_content, second
 *         priority for extra space</li>
 * </ul>
 */
public class AlertDialogLayout extends LinearLayout {

    public AlertDialogLayout(@Nullable Context context) {
        super(context);
    }

    public AlertDialogLayout(@Nullable Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public AlertDialogLayout(@Nullable Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AlertDialogLayout(@Nullable Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!tryOnMeasure(widthMeasureSpec, heightMeasureSpec)) {
            // Failed to perform custom measurement, let superclass handle it.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private boolean tryOnMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View topPanel = null;
        View buttonPanel = null;
        View middlePanel = null;

        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }

            final int id = child.getId();
            switch (id) {
                case R.id.topPanel:
                    topPanel = child;
                    break;
                case R.id.buttonPanel:
                    buttonPanel = child;
                    break;
                case R.id.contentPanel:
                case R.id.customPanel:
                    if (middlePanel != null) {
                        // Both the content and custom are visible. Abort!
                        return false;
                    }
                    middlePanel = child;
                    break;
                default:
                    // Unknown top-level child. Abort!
                    return false;
            }
        }

        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);

        int childState = 0;
        int usedHeight = getPaddingTop() + getPaddingBottom();

        if (topPanel != null) {
            topPanel.measure(widthMeasureSpec, MeasureSpec.UNSPECIFIED);

            usedHeight += topPanel.getMeasuredHeight();
            childState = combineMeasuredStates(childState, topPanel.getMeasuredState());
        }

        int buttonHeight = 0;
        int buttonWantsHeight = 0;
        if (buttonPanel != null) {
            buttonPanel.measure(widthMeasureSpec, MeasureSpec.UNSPECIFIED);
            buttonHeight = resolveMinimumHeight(buttonPanel);
            buttonWantsHeight = buttonPanel.getMeasuredHeight() - buttonHeight;

            usedHeight += buttonHeight;
            childState = combineMeasuredStates(childState, buttonPanel.getMeasuredState());
        }

        int middleHeight = 0;
        if (middlePanel != null) {
            final int childHeightSpec;
            if (heightMode == MeasureSpec.UNSPECIFIED) {
                childHeightSpec = MeasureSpec.UNSPECIFIED;
            } else {
                childHeightSpec = MeasureSpec.makeMeasureSpec(
                        Math.max(0, heightSize - usedHeight), heightMode);
            }

            middlePanel.measure(widthMeasureSpec, childHeightSpec);
            middleHeight = middlePanel.getMeasuredHeight();

            usedHeight += middleHeight;
            childState = combineMeasuredStates(childState, middlePanel.getMeasuredState());
        }

        int remainingHeight = heightSize - usedHeight;

        // Time for the "real" button measure pass. If we have remaining space,
        // make the button pane bigger up to its target height. Otherwise,
        // just remeasure the button at whatever height it needs.
        if (buttonPanel != null) {
            usedHeight -= buttonHeight;

            final int heightToGive = Math.min(remainingHeight, buttonWantsHeight);
            if (heightToGive > 0) {
                remainingHeight -= heightToGive;
                buttonHeight += heightToGive;
            }

            final int childHeightSpec = MeasureSpec.makeMeasureSpec(
                    buttonHeight, MeasureSpec.EXACTLY);
            buttonPanel.measure(widthMeasureSpec, childHeightSpec);

            usedHeight += buttonPanel.getMeasuredHeight();
            childState = combineMeasuredStates(childState, buttonPanel.getMeasuredState());
        }

        // If we still have remaining space, make the middle pane bigger up
        // to the maximum height.
        if (middlePanel != null && remainingHeight > 0) {
            usedHeight -= middleHeight;

            final int heightToGive = remainingHeight;
            remainingHeight -= heightToGive;
            middleHeight += heightToGive;

            // Pass the same height mode as we're using for the dialog itself.
            // If it's EXACTLY, then the middle pane MUST use the entire
            // height.
            final int childHeightSpec = MeasureSpec.makeMeasureSpec(
                    middleHeight, heightMode);
            middlePanel.measure(widthMeasureSpec, childHeightSpec);

            usedHeight += middlePanel.getMeasuredHeight();
            childState = combineMeasuredStates(childState, middlePanel.getMeasuredState());
        }

        // Compute desired width as maximum child width.
        int maxWidth = 0;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                maxWidth = Math.max(maxWidth, child.getMeasuredWidth());
            }
        }

        maxWidth += getPaddingLeft() + getPaddingRight();

        final int widthSizeAndState = resolveSizeAndState(maxWidth, widthMeasureSpec, childState);
        final int heightSizeAndState = resolveSizeAndState(usedHeight, heightMeasureSpec, 0);
        setMeasuredDimension(widthSizeAndState, heightSizeAndState);

        // If the children weren't already measured EXACTLY, we need to run
        // another measure pass to for MATCH_PARENT widths.
        if (widthMode != MeasureSpec.EXACTLY) {
            forceUniformWidth(count, heightMeasureSpec);
        }

        return true;
    }

    /**
     * Remeasures child views to exactly match the layout's measured width.
     *
     * @param count the number of child views
     * @param heightMeasureSpec the original height measure spec
     */
    private void forceUniformWidth(int count, int heightMeasureSpec) {
        // Pretend that the linear layout has an exact size.
        final int uniformMeasureSpec = MeasureSpec.makeMeasureSpec(
                getMeasuredWidth(), MeasureSpec.EXACTLY);

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp.width == LayoutParams.MATCH_PARENT) {
                    // Temporarily force children to reuse their old measured
                    // height.
                    final int oldHeight = lp.height;
                    lp.height = child.getMeasuredHeight();

                    // Remeasure with new dimensions.
                    measureChildWithMargins(child, uniformMeasureSpec, 0, heightMeasureSpec, 0);
                    lp.height = oldHeight;
                }
            }
        }
    }

    /**
     * Attempts to resolve the minimum height of a view.
     * <p>
     * If the view doesn't have a minimum height set and only contains a single
     * child, attempts to resolve the minimum height of the child view.
     *
     * @param v the view whose minimum height to resolve
     * @return the minimum height
     */
    private int resolveMinimumHeight(View v) {
        final int minHeight = v.getMinimumHeight();
        if (minHeight > 0) {
            return minHeight;
        }

        if (v instanceof ViewGroup) {
            final ViewGroup vg = (ViewGroup) v;
            if (vg.getChildCount() == 1) {
                return resolveMinimumHeight(vg.getChildAt(0));
            }
        }

        return 0;
    }
}
