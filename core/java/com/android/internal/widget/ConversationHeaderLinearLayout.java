/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import java.util.LinkedList;
import java.util.List;

/**
 * This is a subclass of LinearLayout meant to be used in the Conversation header, to fix a bug
 * when multiple user-provided strings are shown in the same conversation header.  b/189723284
 *
 * This works around a deficiency in LinearLayout when shrinking views that it can't fully reduce
 * all contents if any of the oversized views reaches zero.
 */
@RemoteViews.RemoteView
public class ConversationHeaderLinearLayout extends LinearLayout {

    public ConversationHeaderLinearLayout(Context context) {
        super(context);
    }

    public ConversationHeaderLinearLayout(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ConversationHeaderLinearLayout(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private int calculateTotalChildLength() {
        final int count = getChildCount();
        int totalLength = 0;

        for (int i = 0; i < count; ++i) {
            final View child = getChildAt(i);
            if (child == null || child.getVisibility() == GONE) {
                continue;
            }
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                    child.getLayoutParams();
            totalLength += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        }
        return totalLength + getPaddingLeft() + getPaddingRight();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int containerWidth = getMeasuredWidth();
        final int contentsWidth = calculateTotalChildLength();

        int excessContents = contentsWidth - containerWidth;
        if (excessContents <= 0) {
            return;
        }
        final int count = getChildCount();

        float remainingWeight = 0;
        List<ViewInfo> visibleChildrenToShorten = null;

        // Find children which need to be shortened in order to ensure the contents fit.
        for (int i = 0; i < count; ++i) {
            final View child = getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) {
                continue;
            }
            final float weight = ((LayoutParams) child.getLayoutParams()).weight;
            if (weight == 0) {
                continue;
            }
            if (child.getMeasuredWidth() == 0) {
                continue;
            }
            if (visibleChildrenToShorten == null) {
                visibleChildrenToShorten = new LinkedList<>();
            }
            visibleChildrenToShorten.add(new ViewInfo(child));
            remainingWeight += Math.max(0, weight);
        }
        if (visibleChildrenToShorten == null || visibleChildrenToShorten.isEmpty()) {
            return;
        }
        balanceViewWidths(visibleChildrenToShorten, remainingWeight, excessContents);
        remeasureChangedChildren(visibleChildrenToShorten);
    }

    /**
     * Measure any child with a width that has changed.
     */
    private void remeasureChangedChildren(List<ViewInfo> childrenInfo) {
        for (ViewInfo info : childrenInfo) {
            if (info.mWidth != info.mStartWidth) {
                final int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        Math.max(0, info.mWidth), MeasureSpec.EXACTLY);
                final int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        info.mView.getMeasuredHeight(), MeasureSpec.EXACTLY);
                info.mView.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }
        }
    }

    /**
     * Given a list of view, use the weights to remove width from each view proportionally to the
     * weight (and ignoring the view's actual width), but do this iteratively whenever a view is
     * reduced to zero width, because in that case other views need reduction.
     */
    void balanceViewWidths(List<ViewInfo> viewInfos, float weightSum, int excessContents) {
        boolean performAnotherPass = true;
        // Loops only when all of the following are true:
        // * `performAnotherPass` -- a view clamped to 0 width (or the first iteration)
        // * `excessContents > 0` -- there is still horizontal space to allocate
        // * `weightSum > 0` -- at least 1 view with nonzero width AND nonzero weight left
        while (performAnotherPass && excessContents > 0 && weightSum > 0) {
            int excessRemovedDuringThisPass = 0;
            float weightSumForNextPass = 0;
            performAnotherPass = false;
            for (ViewInfo info : viewInfos) {
                if (info.mWeight <= 0) {
                    continue;
                }
                if (info.mWidth <= 0) {
                    continue;
                }
                int newWidth = (int) (info.mWidth - (excessContents * (info.mWeight / weightSum)));
                if (newWidth < 0) {
                    newWidth = 0;
                    performAnotherPass = true;
                }
                excessRemovedDuringThisPass += info.mWidth - newWidth;
                info.mWidth = newWidth;
                if (info.mWidth > 0) {
                    weightSumForNextPass += info.mWeight;
                }
            }
            excessContents -= excessRemovedDuringThisPass;
            weightSum = weightSumForNextPass;
        }
    }

    /**
     * A helper class for measuring children.
     */
    static class ViewInfo {
        final View mView;
        final float mWeight;
        final int mStartWidth;
        int mWidth;

        ViewInfo(View view) {
            this.mView = view;
            this.mWeight = ((LayoutParams) view.getLayoutParams()).weight;
            this.mStartWidth = this.mWidth = view.getMeasuredWidth();
        }
    }
}
