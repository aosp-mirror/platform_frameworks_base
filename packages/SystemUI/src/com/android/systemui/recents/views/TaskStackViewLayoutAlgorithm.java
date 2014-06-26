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
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.Utilities;

/* The layout logic for a TaskStackView */
public class TaskStackViewLayoutAlgorithm {

    // These are all going to change
    static final float StackOverlapPct = 0.65f; // The overlap height relative to the task height
    static final float StackPeekHeightPct = 0.1f; // The height of the peek space relative to the stack height
    static final float StackPeekMinScale = 0.8f; // The min scale of the last card in the peek area
    static final int StackPeekNumCards = 3; // The number of cards we see in the peek space

    RecentsConfiguration mConfig;

    // The various rects that define the stack view
    Rect mRect = new Rect();
    Rect mStackRect = new Rect();
    Rect mStackRectSansPeek = new Rect();
    Rect mTaskRect = new Rect();

    // The min/max scroll
    int mMinScroll;
    int mMaxScroll;

    public TaskStackViewLayoutAlgorithm(RecentsConfiguration config) {
        mConfig = config;
    }

    /** Computes the stack and task rects */
    public void computeRects(int width, int height, int insetLeft, int insetBottom) {
        // Note: We let the stack view be the full height because we want the cards to go under the
        //       navigation bar if possible.  However, the stack rects which we use to calculate
        //       max scroll, etc. need to take the nav bar into account

        // Compute the stack rects
        mRect.set(0, 0, width, height);
        mStackRect.set(mRect);
        mStackRect.left += insetLeft;
        mStackRect.bottom -= insetBottom;

        int widthPadding = (int) (mConfig.taskStackWidthPaddingPct * mStackRect.width());
        int heightPadding = mConfig.taskStackTopPaddingPx;
        if (Constants.DebugFlags.App.EnableSearchLayout) {
            mStackRect.top += heightPadding;
            mStackRect.left += widthPadding;
            mStackRect.right -= widthPadding;
            mStackRect.bottom -= heightPadding;
        } else {
            mStackRect.inset(widthPadding, heightPadding);
        }
        mStackRectSansPeek.set(mStackRect);
        mStackRectSansPeek.top += StackPeekHeightPct * mStackRect.height();

        // Compute the task rect
        int size = mStackRect.width();
        int left = mStackRect.left + (mStackRect.width() - size) / 2;
        mTaskRect.set(left, mStackRectSansPeek.top,
                left + size, mStackRectSansPeek.top + size);
    }

    void computeMinMaxScroll(int taskCount) {
        // Compute the min and max scroll values
        int numTasks = Math.max(1, taskCount);
        int taskHeight = mTaskRect.height();
        int stackHeight = mStackRectSansPeek.height();
        int maxScrollHeight = taskHeight + getStackScrollForTaskIndex(numTasks - 1);

        if (numTasks <= 1) {
            // If there is only one task, then center the task in the stack rect (sans peek)
            mMinScroll = mMaxScroll = -(stackHeight - taskHeight) / 2;
        } else {
            mMinScroll = Math.min(stackHeight, maxScrollHeight) - stackHeight;
            mMaxScroll = maxScrollHeight - stackHeight;
        }
    }

    /** Update/get the transform (creates a new TaskViewTransform) */
    public TaskViewTransform getStackTransform(int indexInStack, int stackScroll) {
        TaskViewTransform transform = new TaskViewTransform();
        return getStackTransform(indexInStack, stackScroll, transform);
    }

    /** Update/get the transform */
    public TaskViewTransform getStackTransform(int indexInStack, int stackScroll,
                                               TaskViewTransform transformOut) {
        // Return early if we have an invalid index
        if (indexInStack < 0) {
            transformOut.reset();
            return transformOut;
        }

        // Map the items to an continuous position relative to the specified scroll
        int numPeekCards = StackPeekNumCards;
        float overlapHeight = StackOverlapPct * mTaskRect.height();
        float peekHeight = StackPeekHeightPct * mStackRect.height();
        float t = ((indexInStack * overlapHeight) - stackScroll) / overlapHeight;
        float boundedT = Math.max(t, -(numPeekCards + 1));

        // Set the scale relative to its position
        int numFrontScaledCards = 3;
        float minScale = StackPeekMinScale;
        float scaleRange = 1f - minScale;
        float scaleInc = scaleRange / (numPeekCards + numFrontScaledCards);
        float scale = Math.max(minScale, Math.min(1f, minScale + 
            ((boundedT + (numPeekCards + 1)) * scaleInc)));
        float scaleYOffset = ((1f - scale) * mTaskRect.height()) / 2;
        transformOut.scale = scale;

        // Set the y translation
        if (boundedT < 0f) {
            transformOut.translationY = (int) ((Math.max(-numPeekCards, boundedT) /
                    numPeekCards) * peekHeight - scaleYOffset);
        } else {
            transformOut.translationY = (int) (boundedT * overlapHeight - scaleYOffset);
        }

        // Set the z translation
        int minZ = mConfig.taskViewTranslationZMinPx;
        int incZ = mConfig.taskViewTranslationZIncrementPx;
        transformOut.translationZ = (int) Math.max(minZ, minZ + ((boundedT + numPeekCards) * incZ));

        // Set the alphas
        transformOut.dismissAlpha = Math.max(-1f, Math.min(0f, t + 1)) + 1f;

        // Update the rect and visibility
        transformOut.rect.set(mTaskRect);
        if (t < -(numPeekCards + 1)) {
            transformOut.visible = false;
        } else {
            transformOut.rect.offset(0, transformOut.translationY);
            Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
            transformOut.visible = Rect.intersects(mRect, transformOut.rect);
        }
        transformOut.t = t;
        return transformOut;
    }

    /**
     * Returns the overlap between one task and the next.
     */
    float getTaskOverlapHeight() {
        return StackOverlapPct * mTaskRect.height();
    }

    /**
     * Returns the scroll to such that the task transform at that index will have t=0. (If the scroll
     * is not bounded)
     */
    int getStackScrollForTaskIndex(int i) {
        return (int) (i * getTaskOverlapHeight());
    }

}