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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.Log;
import com.android.systemui.R;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.ParametricCurve;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;

import java.util.ArrayList;
import java.util.HashMap;


/**
 * The layout logic for a TaskStackView.
 */
public class TaskStackViewLayoutAlgorithm {

    private static final boolean DEBUG = false;
    private static final String TAG = "TaskStackViewLayoutAlgorithm";

    // The min scale of the last task at the top of the curve
    private static final float STACK_PEEK_MIN_SCALE = 0.75f;
    // The scale of the last task
    private static final float SINGLE_TASK_SCALE = 0.95f;
    // The percentage of height of task to show between tasks
    private static final float VISIBLE_TASK_HEIGHT_BETWEEN_TASKS = 0.5f;

    // A report of the visibility state of the stack
    public class VisibilityReport {
        public int numVisibleTasks;
        public int numVisibleThumbnails;

        /** Package level ctor */
        VisibilityReport(int tasks, int thumbnails) {
            numVisibleTasks = tasks;
            numVisibleThumbnails = thumbnails;
        }
    }

    Context mContext;
    RecentsConfiguration mConfig;

    // This is the view bounds inset exactly by the search bar, but without the bottom inset
    // see RecentsConfiguration.getTaskStackBounds()
    public Rect mStackRect = new Rect();
    // This is the task view bounds for layout (untransformed), the rect is top-aligned to the top
    // of the stack rect
    public Rect mTaskRect = new Rect();
    // This is the current system insets
    public Rect mSystemInsets = new Rect();

    // The smallest scroll progress, at this value, the back most task will be visible
    float mMinScrollP;
    // The largest scroll progress, at this value, the front most task will be visible above the
    // navigation bar
    float mMaxScrollP;
    // The initial progress that the scroller is set
    float mInitialScrollP;

    // The relative progress to ensure that the height between affiliated tasks is respected
    float mWithinAffiliationPOffset;
    // The relative progress to ensure that the height between non-affiliated tasks is
    // respected
    float mBetweenAffiliationPOffset;
    // The relative progress to ensure that the task height is respected
    float mTaskHeightPOffset;
    // The relative progress to ensure that the half task height is respected
    float mTaskHalfHeightPOffset;
    // The relative progress to ensure that the offset from the bottom of the stack to the bottom
    // of the task is respected
    float mTaskBottomPOffset;
    // The front-most task bottom offset
    int mTaskBottomOffset;

    // The last computed task count
    int mNumTasks;
    // The min/max z translations
    int mMinTranslationZ;
    int mMaxTranslationZ;

    // Optimization, allows for quick lookup of task -> progress
    HashMap<Task.TaskKey, Float> mTaskProgressMap = new HashMap<>();

    // Log function
    static ParametricCurve sCurve;

    public TaskStackViewLayoutAlgorithm(Context context, RecentsConfiguration config) {
        Resources res = context.getResources();
        mMinTranslationZ = res.getDimensionPixelSize(R.dimen.recents_task_view_z_min);
        mMaxTranslationZ = res.getDimensionPixelSize(R.dimen.recents_task_view_z_max);
        mContext = context;
        mConfig = config;
        if (sCurve == null) {
            sCurve = new ParametricCurve(new ParametricCurve.CurveFunction() {
                // The large the XScale, the longer the flat area of the curve
                private static final float XScale = 1.75f;
                private static final float LogBase = 3000;

                float reverse(float x) {
                    return (-x * XScale) + 1;
                }

                @Override
                public float f(float x) {
                    return 1f - (float) (Math.pow(LogBase, reverse(x))) / (LogBase);
                }

                @Override
                public float invF(float y) {
                    return (float) (Math.log(1f - reverse(y)) / (-Math.log(LogBase) * XScale));
                }
            }, new ParametricCurve.ParametricCurveFunction() {
                @Override
                public float f(float p) {
                    if (p < 0) return STACK_PEEK_MIN_SCALE;
                    if (p > 1) return 1f;
                    float scaleRange = (1f - STACK_PEEK_MIN_SCALE);
                    float scale = STACK_PEEK_MIN_SCALE + (p * scaleRange);
                    return scale;
                }
            });
        }
    }

    /**
     * Sets the system insets.
     */
    public void setSystemInsets(Rect systemInsets) {
        mSystemInsets.set(systemInsets);
        if (DEBUG) {
            Log.d(TAG, "setSystemInsets: " + systemInsets);
        }
    }

    /**
     * Computes the stack and task rects
     */
    public void computeRects(int windowWidth, int windowHeight, Rect taskStackBounds) {
        int widthPadding = (int) (mConfig.taskStackWidthPaddingPct * taskStackBounds.width());
        int heightPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_stack_top_padding);

        // Compute the stack rect, inset from the given task stack bounds
        mStackRect.set(taskStackBounds.left + widthPadding, taskStackBounds.top + heightPadding,
                taskStackBounds.right - widthPadding, windowHeight);
        mTaskBottomOffset = mSystemInsets.bottom + heightPadding;

        // Compute the task rect, align it to the top-center square in the stack rect
        int size = Math.min(mStackRect.width(), taskStackBounds.height() - mTaskBottomOffset);
        int xOffset = (mStackRect.width() - size) / 2;
        mTaskRect.set(mStackRect.left + xOffset, mStackRect.top,
                mStackRect.right - xOffset, mStackRect.top + size);

        // Compute the progress offsets
        int withinAffiliationOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_task_bar_height);
        int betweenAffiliationOffset = (int) (VISIBLE_TASK_HEIGHT_BETWEEN_TASKS * mTaskRect.height());
        mWithinAffiliationPOffset = sCurve.computePOffsetForScaledHeight(withinAffiliationOffset,
                mStackRect);
        mBetweenAffiliationPOffset = sCurve.computePOffsetForScaledHeight(betweenAffiliationOffset,
                mStackRect);
        mTaskHeightPOffset = sCurve.computePOffsetForScaledHeight(mTaskRect.height(),
                mStackRect);
        mTaskHalfHeightPOffset = sCurve.computePOffsetForScaledHeight(mTaskRect.height() / 2,
                mStackRect);
        mTaskBottomPOffset = sCurve.computePOffsetForHeight(mTaskBottomOffset, mStackRect);

        if (DEBUG) {
            Log.d(TAG, "computeRects");
            Log.d(TAG, "\tmStackRect: " + mStackRect);
            Log.d(TAG, "\tmTaskRect: " + mTaskRect);
            Log.d(TAG, "\tmSystemInsets: " + mSystemInsets);

            Log.d(TAG, "\tpWithinAffiliateOffset: " + mWithinAffiliationPOffset);
            Log.d(TAG, "\tpBetweenAffiliateOffset: " + mBetweenAffiliationPOffset);
            Log.d(TAG, "\tmTaskHeightPOffset: " + mTaskHeightPOffset);
            Log.d(TAG, "\tmTaskHalfHeightPOffset: " + mTaskHalfHeightPOffset);
            Log.d(TAG, "\tmTaskBottomPOffset: " + mTaskBottomPOffset);

            Log.d(TAG, "\ty at p=0: " + sCurve.pToX(0f, mStackRect));
            Log.d(TAG, "\ty at p=1: " + sCurve.pToX(1f, mStackRect));

            for (int height = 0; height <= 1000; height += 50) {
                float p = sCurve.computePOffsetForScaledHeight(height, mStackRect);
                float p2 = sCurve.computePOffsetForHeight(height, mStackRect);
                Log.d(TAG, "offset: " + height + ", " +
                        p + " => " + (mStackRect.bottom - sCurve.pToX(1f - p, mStackRect)) /
                                sCurve.pToScale(1f - p) + ", " +
                        p2 + " => " + (mStackRect.bottom - sCurve.pToX(1f - p2, mStackRect)));
            }
        }
    }

    /**
     * Computes the minimum and maximum scroll progress values.  This method may be called before
     * the RecentsConfiguration is set, so we need to pass in the alt-tab state.
     */
    void computeMinMaxScroll(ArrayList<Task> tasks) {
        if (DEBUG) {
            Log.d(TAG, "computeMinMaxScroll");
        }

        // Clear the progress map
        mTaskProgressMap.clear();
        mNumTasks = tasks.size();

        // Return early if we have no tasks
        if (tasks.isEmpty()) {
            mMinScrollP = mMaxScrollP = 0;
            return;
        }

        // We calculate the progress by taking the progress of the element from the bottom of the
        // screen
        if (mNumTasks == 1) {
            // Just center the task in the visible stack rect
            mMinScrollP = mMaxScrollP = mInitialScrollP = 0f;
            mTaskProgressMap.put(tasks.get(0).key, 0f);
        } else {
            // Update the tasks from back to front with the new progresses. We set the initial
            // progress to the progress at which the top of the last task is near the center of the
            // visible stack rect.
            float pAtBackMostTaskTop = 0;
            float pAtFrontMostTaskTop = pAtBackMostTaskTop;
            int taskCount = tasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task task = tasks.get(i);
                mTaskProgressMap.put(task.key, pAtFrontMostTaskTop);

                if (i < (taskCount - 1)) {
                    // Increment the peek height
                    float pPeek = task.group.isFrontMostTask(task) ?
                            mBetweenAffiliationPOffset : mWithinAffiliationPOffset;
                    pAtFrontMostTaskTop += pPeek;
                }
            }

            // Set the max scroll progress to the point at which the bottom of the front-most task
            // is aligned to the bottom of the stack (including nav bar and stack padding)
            mMaxScrollP = pAtFrontMostTaskTop - 1f + mTaskBottomPOffset + mTaskHeightPOffset;
            // Basically align the back-most task such that its progress is the same as the top of
            // the front most task at the max scroll
            mMinScrollP = pAtBackMostTaskTop - 1f + mTaskBottomPOffset + mTaskHeightPOffset;
            // The offset the inital scroll position to the front of the stack, with half the front
            // task height visible
            mInitialScrollP = Math.max(mMinScrollP, mMaxScrollP - mTaskHalfHeightPOffset);
        }
    }

    /**
     * Computes the maximum number of visible tasks and thumbnails.  Requires that
     * computeMinMaxScroll() is called first.
     */
    public VisibilityReport computeStackVisibilityReport(ArrayList<Task> tasks) {
        if (tasks.size() <= 1) {
            return new VisibilityReport(1, 1);
        }

        // Walk backwards in the task stack and count the number of tasks and visible thumbnails
        int taskHeight = mTaskRect.height();
        int taskBarHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_task_bar_height);
        int numVisibleTasks = 1;
        int numVisibleThumbnails = 1;
        float progress = mTaskProgressMap.get(tasks.get(tasks.size() - 1).key) - mInitialScrollP;
        int prevScreenY = sCurve.pToX(progress, mStackRect);
        for (int i = tasks.size() - 2; i >= 0; i--) {
            Task task = tasks.get(i);
            progress = mTaskProgressMap.get(task.key) - mInitialScrollP;
            if (progress < 0) {
                break;
            }
            boolean isFrontMostTaskInGroup = task.group.isFrontMostTask(task);
            if (isFrontMostTaskInGroup) {
                float scaleAtP = sCurve.pToScale(progress);
                int scaleYOffsetAtP = (int) (((1f - scaleAtP) * taskHeight) / 2);
                int screenY = sCurve.pToX(progress, mStackRect) + scaleYOffsetAtP;
                boolean hasVisibleThumbnail = (prevScreenY - screenY) > taskBarHeight;
                if (hasVisibleThumbnail) {
                    numVisibleThumbnails++;
                    numVisibleTasks++;
                    prevScreenY = screenY;
                } else {
                    // Once we hit the next front most task that does not have a visible thumbnail,
                    // walk through remaining visible set
                    for (int j = i; j >= 0; j--) {
                        numVisibleTasks++;
                        progress = mTaskProgressMap.get(tasks.get(j).key) - mInitialScrollP;
                        if (progress < 0) {
                            break;
                        }
                    }
                    break;
                }
            } else if (!isFrontMostTaskInGroup) {
                // Affiliated task, no thumbnail
                numVisibleTasks++;
            }
        }
        return new VisibilityReport(numVisibleTasks, numVisibleThumbnails);
    }

    /**
     * Returns the transform for the given task.  This transform is relative to the mTaskRect, which
     * is what the view is measured and laid out with.
     */
    public TaskViewTransform getStackTransform(Task task, float stackScroll,
            TaskViewTransform transformOut, TaskViewTransform prevTransform) {
        // Return early if we have an invalid index
        if (task == null || !mTaskProgressMap.containsKey(task.key)) {
            transformOut.reset();
            return transformOut;
        }
        return getStackTransform(mTaskProgressMap.get(task.key), stackScroll, transformOut,
                prevTransform);
    }

    /** Update/get the transform */
    public TaskViewTransform getStackTransform(float taskProgress, float stackScroll,
            TaskViewTransform transformOut, TaskViewTransform prevTransform) {
        if (DEBUG) {
            Log.d(TAG, "getStackTransform: " + stackScroll);
        }

        if (mNumTasks == 1) {
            // Center the task in the stack, changing the scale will not follow the curve, but just
            // modulate some values directly
            float pTaskRelative = -stackScroll;
            float scale = SINGLE_TASK_SCALE;
            int topOffset = (mStackRect.height() - mTaskBottomOffset - mTaskRect.height()) / 2;
            transformOut.scale = scale;
            transformOut.translationY = (int) (topOffset + (pTaskRelative * mStackRect.height()));
            transformOut.translationZ = mMaxTranslationZ;
            transformOut.rect.set(mTaskRect);
            transformOut.rect.offset(0, transformOut.translationY);
            Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
            transformOut.visible = true;
            transformOut.p = pTaskRelative;
            return transformOut;
        } else {
            float pTaskRelative = taskProgress - stackScroll;
            float pBounded = Math.max(0, Math.min(pTaskRelative, 1f));
            // If the task top is outside of the bounds below the screen, then immediately reset it
            if (pTaskRelative > 1f) {
                transformOut.reset();
                transformOut.rect.set(mTaskRect);
                return transformOut;
            }
            // The check for the top is trickier, since we want to show the next task if it is at
            // all visible, even if p < 0.
            if (pTaskRelative < 0f) {
                if (prevTransform != null && Float.compare(prevTransform.p, 0f) <= 0) {
                    transformOut.reset();
                    transformOut.rect.set(mTaskRect);
                    return transformOut;
                }
            }
            float scale = sCurve.pToScale(pBounded);
            int scaleYOffset = (int) (((1f - scale) * mTaskRect.height()) / 2);
            transformOut.scale = scale;
            transformOut.translationY = sCurve.pToX(pBounded, mStackRect) - mStackRect.top -
                    scaleYOffset;
            transformOut.translationZ = Math.max(mMinTranslationZ,
                    mMinTranslationZ + (pBounded * (mMaxTranslationZ - mMinTranslationZ)));
            transformOut.rect.set(mTaskRect);
            transformOut.rect.offset(0, transformOut.translationY);
            Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
            transformOut.visible = true;
            transformOut.p = pTaskRelative;
            return transformOut;
        }
    }

    /**
     * Returns the untransformed task view bounds.
     */
    public Rect getUntransformedTaskViewBounds() {
        return new Rect(mTaskRect);
    }

    /**
     * Returns the scroll progress to scroll to such that the top of the task is at the top of the
     * stack.
     */
    float getStackScrollForTask(Task t) {
        if (!mTaskProgressMap.containsKey(t.key)) return 0f;
        return mTaskProgressMap.get(t.key);
    }
}
