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
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;

import java.util.ArrayList;
import java.util.HashMap;

/* The layout logic for a TaskStackView.
 *
 * We are using a curve that defines the curve of the tasks as that go back in the recents list.
 * The curve is defined such that at curve progress p = 0 is the end of the curve (the top of the
 * stack rect), and p = 1 at the start of the curve and the bottom of the stack rect.
 */
public class TaskStackViewLayoutAlgorithm {

    // These are all going to change
    static final float StackPeekMinScale = 0.825f; // The min scale of the last card in the peek area

    RecentsConfiguration mConfig;

    // The various rects that define the stack view
    Rect mViewRect = new Rect();
    Rect mStackVisibleRect = new Rect();
    Rect mStackRect = new Rect();
    Rect mTaskRect = new Rect();

    // The min/max scroll progress
    float mMinScrollP;
    float mMaxScrollP;
    float mInitialScrollP;
    int mWithinAffiliationOffset;
    int mBetweenAffiliationOffset;
    HashMap<Task.TaskKey, Float> mTaskProgressMap = new HashMap<Task.TaskKey, Float>();

    // Log function
    static final float XScale = 1.75f;  // The large the XScale, the longer the flat area of the curve
    static final float LogBase = 300;
    static final int PrecisionSteps = 250;
    static float[] xp;
    static float[] px;

    public TaskStackViewLayoutAlgorithm(RecentsConfiguration config) {
        mConfig = config;

        // Precompute the path
        initializeCurve();
    }

    /** Computes the stack and task rects */
    public void computeRects(int windowWidth, int windowHeight, Rect taskStackBounds) {
        // Compute the stack rects
        mViewRect.set(0, 0, windowWidth, windowHeight);
        mStackRect.set(taskStackBounds);
        mStackVisibleRect.set(taskStackBounds);
        mStackVisibleRect.bottom = mViewRect.bottom;

        int widthPadding = (int) (mConfig.taskStackWidthPaddingPct * mStackRect.width());
        int heightPadding = mConfig.taskStackTopPaddingPx;
        mStackRect.inset(widthPadding, heightPadding);

        // Compute the task rect
        int size = mStackRect.width();
        int left = mStackRect.left + (mStackRect.width() - size) / 2;
        mTaskRect.set(left, mStackRect.top,
                left + size, mStackRect.top + size);

        // Update the affiliation offsets
        float visibleTaskPct = 0.55f;
        mWithinAffiliationOffset = mConfig.taskBarHeight;
        mBetweenAffiliationOffset = (int) (visibleTaskPct * mTaskRect.height());
    }

    /** Computes the minimum and maximum scroll progress values.  This method may be called before
     * the RecentsConfiguration is set, so we need to pass in the alt-tab state. */
    void computeMinMaxScroll(ArrayList<Task> tasks, boolean launchedWithAltTab,
            boolean launchedFromHome) {
        // Clear the progress map
        mTaskProgressMap.clear();

        // Return early if we have no tasks
        if (tasks.isEmpty()) {
            mMinScrollP = mMaxScrollP = 0;
            return;
        }

        // Note that we should account for the scale difference of the offsets at the screen bottom
        int taskHeight = mTaskRect.height();
        float pAtBottomOfStackRect = screenYToCurveProgress(mStackVisibleRect.bottom);
        float pWithinAffiliateTop = screenYToCurveProgress(mStackVisibleRect.bottom -
                mWithinAffiliationOffset);
        float scale = curveProgressToScale(pWithinAffiliateTop);
        int scaleYOffset = (int) (((1f - scale) * taskHeight) / 2);
        pWithinAffiliateTop = screenYToCurveProgress(mStackVisibleRect.bottom -
                mWithinAffiliationOffset + scaleYOffset);
        float pWithinAffiliateOffset = pAtBottomOfStackRect - pWithinAffiliateTop;
        float pBetweenAffiliateOffset = pAtBottomOfStackRect -
                screenYToCurveProgress(mStackVisibleRect.bottom - mBetweenAffiliationOffset);
        float pTaskHeightOffset = pAtBottomOfStackRect -
                screenYToCurveProgress(mStackVisibleRect.bottom - taskHeight);
        float pNavBarOffset = pAtBottomOfStackRect -
                screenYToCurveProgress(mStackVisibleRect.bottom - (mStackVisibleRect.bottom - mStackRect.bottom));

        // Update the task offsets
        float pAtBackMostCardTop = 0.5f;
        float pAtFrontMostCardTop = pAtBackMostCardTop;
        float pAtSecondFrontMostCardTop = pAtBackMostCardTop;
        int taskCount = tasks.size();
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks.get(i);
            mTaskProgressMap.put(task.key, pAtFrontMostCardTop);

            if (i < (taskCount - 1)) {
                // Increment the peek height
                float pPeek = task.group.isFrontMostTask(task) ? pBetweenAffiliateOffset :
                    pWithinAffiliateOffset;
                pAtSecondFrontMostCardTop = pAtFrontMostCardTop;
                pAtFrontMostCardTop += pPeek;
            }
        }

        mMaxScrollP = pAtFrontMostCardTop - ((1f - pTaskHeightOffset - pNavBarOffset));
        mMinScrollP = tasks.size() == 1 ? Math.max(mMaxScrollP, 0f) : 0f;
        if (launchedWithAltTab) {
            if (launchedFromHome) {
                // Center the top most task, since that will be focused first
                mInitialScrollP = pAtSecondFrontMostCardTop - 0.5f;
            } else {
                // Center the second top most task, since that will be focused first
                mInitialScrollP = pAtSecondFrontMostCardTop - 0.5f;
            }
        } else {
            mInitialScrollP = pAtFrontMostCardTop - 0.825f;
        }
        mInitialScrollP = Math.max(0, mInitialScrollP);
    }

    /** Update/get the transform */
    public TaskViewTransform getStackTransform(Task task, float stackScroll, TaskViewTransform transformOut,
            TaskViewTransform prevTransform) {
        // Return early if we have an invalid index
        if (task == null) {
            transformOut.reset();
            return transformOut;
        }
        return getStackTransform(mTaskProgressMap.get(task.key), stackScroll, transformOut, prevTransform);
    }

    /** Update/get the transform */
    public TaskViewTransform getStackTransform(float taskProgress, float stackScroll, TaskViewTransform transformOut, TaskViewTransform prevTransform) {
        float pTaskRelative = taskProgress - stackScroll;
        float pBounded = Math.max(0, Math.min(pTaskRelative, 1f));
        // If the task top is outside of the bounds below the screen, then immediately reset it
        if (pTaskRelative > 1f) {
            transformOut.reset();
            transformOut.rect.set(mTaskRect);
            return transformOut;
        }
        // The check for the top is trickier, since we want to show the next task if it is at all
        // visible, even if p < 0.
        if (pTaskRelative < 0f) {
            if (prevTransform != null && Float.compare(prevTransform.p, 0f) <= 0) {
                transformOut.reset();
                transformOut.rect.set(mTaskRect);
                return transformOut;
            }
        }
        float scale = curveProgressToScale(pBounded);
        int scaleYOffset = (int) (((1f - scale) * mTaskRect.height()) / 2);
        int minZ = mConfig.taskViewTranslationZMinPx;
        int maxZ = mConfig.taskViewTranslationZMaxPx;
        transformOut.scale = scale;
        transformOut.translationY = curveProgressToScreenY(pBounded) - mStackVisibleRect.top -
                scaleYOffset;
        transformOut.translationZ = Math.max(minZ, minZ + (pBounded * (maxZ - minZ)));
        transformOut.rect.set(mTaskRect);
        transformOut.rect.offset(0, transformOut.translationY);
        Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
        transformOut.visible = true;
        transformOut.p = pTaskRelative;
        return transformOut;
    }

    /**
     * Returns the scroll to such task top = 1f;
     */
    float getStackScrollForTask(Task t) {
        return mTaskProgressMap.get(t.key);
    }

    /** Initializes the curve. */
    public static void initializeCurve() {
        if (xp != null && px != null) return;
        xp = new float[PrecisionSteps + 1];
        px = new float[PrecisionSteps + 1];

        // Approximate f(x)
        float[] fx = new float[PrecisionSteps + 1];
        float step = 1f / PrecisionSteps;
        float x = 0;
        for (int xStep = 0; xStep <= PrecisionSteps; xStep++) {
            fx[xStep] = logFunc(x);
            x += step;
        }
        // Calculate the arc length for x:1->0
        float pLength = 0;
        float[] dx = new float[PrecisionSteps + 1];
        dx[0] = 0;
        for (int xStep = 1; xStep < PrecisionSteps; xStep++) {
            dx[xStep] = (float) Math.sqrt(Math.pow(fx[xStep] - fx[xStep - 1], 2) + Math.pow(step, 2));
            pLength += dx[xStep];
        }
        // Approximate p(x), a function of cumulative progress with x, normalized to 0..1
        float p = 0;
        px[0] = 0f;
        px[PrecisionSteps] = 1f;
        for (int xStep = 1; xStep <= PrecisionSteps; xStep++) {
            p += Math.abs(dx[xStep] / pLength);
            px[xStep] = p;
        }
        // Given p(x), calculate the inverse function x(p). This assumes that x(p) is also a valid
        // function.
        int xStep = 0;
        p = 0;
        xp[0] = 0f;
        xp[PrecisionSteps] = 1f;
        for (int pStep = 0; pStep < PrecisionSteps; pStep++) {
            // Walk forward in px and find the x where px <= p && p < px+1
            while (xStep < PrecisionSteps) {
                if (px[xStep] > p) break;
                xStep++;
            }
            // Now, px[xStep-1] <= p < px[xStep]
            if (xStep == 0) {
                xp[pStep] = 0;
            } else {
                // Find x such that proportionally, x is correct
                float fraction = (p - px[xStep - 1]) / (px[xStep] - px[xStep - 1]);
                x = (xStep - 1 + fraction) * step;
                xp[pStep] = x;
            }
            p += step;
        }
    }

    /** Reverses and scales out x. */
    static float reverse(float x) {
        return (-x * XScale) + 1;
    }
    /** The log function describing the curve. */
    static float logFunc(float x) {
        return 1f - (float) (Math.pow(LogBase, reverse(x))) / (LogBase);
    }
    /** The inverse of the log function describing the curve. */
    float invLogFunc(float y) {
        return (float) (Math.log((1f - reverse(y)) * (LogBase - 1) + 1) / Math.log(LogBase));
    }

    /** Converts from the progress along the curve to a screen coordinate. */
    int curveProgressToScreenY(float p) {
        if (p < 0 || p > 1) return mStackVisibleRect.top + (int) (p * mStackVisibleRect.height());
        float pIndex = p * PrecisionSteps;
        int pFloorIndex = (int) Math.floor(pIndex);
        int pCeilIndex = (int) Math.ceil(pIndex);
        float xFraction = 0;
        if (pFloorIndex < PrecisionSteps && (pCeilIndex != pFloorIndex)) {
            float pFraction = (pIndex - pFloorIndex) / (pCeilIndex - pFloorIndex);
            xFraction = (xp[pCeilIndex] - xp[pFloorIndex]) * pFraction;
        }
        float x = xp[pFloorIndex] + xFraction;
        return mStackVisibleRect.top + (int) (x * mStackVisibleRect.height());
    }

    /** Converts from the progress along the curve to a scale. */
    float curveProgressToScale(float p) {
        if (p < 0) return StackPeekMinScale;
        if (p > 1) return 1f;
        float scaleRange = (1f - StackPeekMinScale);
        float scale = StackPeekMinScale + (p * scaleRange);
        return scale;
    }

    /** Converts from a screen coordinate to the progress along the curve. */
    float screenYToCurveProgress(int screenY) {
        float x = (float) (screenY - mStackVisibleRect.top) / mStackVisibleRect.height();
        if (x < 0 || x > 1) return x;
        float xIndex = x * PrecisionSteps;
        int xFloorIndex = (int) Math.floor(xIndex);
        int xCeilIndex = (int) Math.ceil(xIndex);
        float pFraction = 0;
        if (xFloorIndex < PrecisionSteps && (xCeilIndex != xFloorIndex)) {
            float xFraction = (xIndex - xFloorIndex) / (xCeilIndex - xFloorIndex);
            pFraction = (px[xCeilIndex] - px[xFloorIndex]) * xFraction;
        }
        return px[xFloorIndex] + pFraction;
    }
}