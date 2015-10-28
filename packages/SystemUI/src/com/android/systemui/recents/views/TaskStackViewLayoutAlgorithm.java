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
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.ParametricCurve;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;

import java.util.ArrayList;
import java.util.HashMap;


/**
 * The layout logic for a TaskStackView.
 *
 */
public class TaskStackViewLayoutAlgorithm {

    private static final String TAG = "TaskStackViewLayoutAlgorithm";
    private static final boolean DEBUG = false;

    // The min scale of the last task at the top of the curve
    private static final float STACK_PEEK_MIN_SCALE = 0.85f;
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

    // This is the view bounds inset exactly by the search bar, but without the bottom inset
    // see RecentsConfiguration.getTaskStackBounds()
    public Rect mStackRect = new Rect();

    // This is the task view bounds for layout (untransformed), the rect is top-aligned to the top
    // of the stack rect
    public Rect mTaskRect = new Rect();

    // The bounds of the freeform workspace, the rect is top-aligned to the top of the stack rect
    public Rect mFreeformRect = new Rect();
    // This is the current system insets
    public Rect mSystemInsets = new Rect();

    // The smallest scroll progress, at this value, the back most task will be visible
    float mMinScrollP;
    // The largest scroll progress, at this value, the front most task will be visible above the
    // navigation bar
    float mMaxScrollP;
    // The scroll progress at which the stack scroll ends and the overscroll begins.  This serves
    // as the point at which we can show the freeform space.
    float mMaxStackScrollP;
    // The initial progress that the scroller is set
    float mInitialScrollP;
    // The task progress for the front-most task in the stack
    float mFrontMostTaskP;

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
    // The relative progress to ensure that the freeform workspace height is respected
    float mFreeformWorkspacePOffset;
    // The front-most task bottom offset
    int mTaskBottomOffset;

    // The number of cells in the freeform workspace
    int mFreeformCellXCount;
    int mFreeformCellYCount;
    // The width and height of the cells in the freeform workspace
    int mFreeformCellWidth;
    int mFreeformCellHeight;

    // The last computed task counts
    int mNumStackTasks;
    int mNumFreeformTasks;
    // The min/max z translations
    int mMinTranslationZ;
    int mMaxTranslationZ;

    // Optimization, allows for quick lookup of task -> progress
    HashMap<Task.TaskKey, Float> mTaskProgressMap = new HashMap<>();

    // Log function
    static ParametricCurve sCurve;

    public TaskStackViewLayoutAlgorithm(Context context) {
        Resources res = context.getResources();
        mMinTranslationZ = res.getDimensionPixelSize(R.dimen.recents_task_view_z_min);
        mMaxTranslationZ = res.getDimensionPixelSize(R.dimen.recents_task_view_z_max);
        mContext = context;
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
     * Computes the stack and task rects.
     */
    public void computeRects(Rect taskStackBounds) {
        RecentsConfiguration config = Recents.getConfiguration();
        int widthPadding = (int) (config.taskStackWidthPaddingPct * taskStackBounds.width());
        int heightPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_stack_top_padding);

        // Compute the stack rect, inset from the given task stack bounds
        mStackRect.set(taskStackBounds.left + widthPadding, taskStackBounds.top + heightPadding,
                taskStackBounds.right - widthPadding, taskStackBounds.bottom);
        mTaskBottomOffset = mSystemInsets.bottom + heightPadding;

        // Compute the task rect, align it to the top-center square in the stack rect
        int size = Math.min(mStackRect.width(), mStackRect.height() - mTaskBottomOffset);
        int xOffset = (mStackRect.width() - size) / 2;
        mTaskRect.set(mStackRect.left + xOffset, mStackRect.top,
                mStackRect.right - xOffset, mStackRect.top + size);
        mFreeformRect.set(mTaskRect);

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
        mFreeformWorkspacePOffset = sCurve.computePOffsetForHeight(mFreeformRect.height(),
                mStackRect);

        if (DEBUG) {
            Log.d(TAG, "computeRects");
            Log.d(TAG, "\tarclength: " + sCurve.getArcLength());
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

        // Return early if we have no tasks
        if (tasks.isEmpty()) {
            mMinScrollP = mMaxScrollP = mMaxStackScrollP = 0;
            mNumStackTasks = mNumFreeformTasks = 0;
            return;
        }

        // Filter the set of freeform and stack tasks
        ArrayList<Task> freeformTasks = new ArrayList<>();
        ArrayList<Task> stackTasks = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (task.isFreeformTask()) {
                freeformTasks.add(task);
            } else {
                stackTasks.add(task);
            }
        }
        mNumStackTasks = stackTasks.size();
        mNumFreeformTasks = freeformTasks.size();

        // TODO: In the case where there is only freeform tasks, then the scrolls should be set to
        // zero

        if (!stackTasks.isEmpty()) {
            // Update the for each task from back to front.
            float pAtBackMostTaskTop = 0;
            float pAtFrontMostTaskTop = pAtBackMostTaskTop;
            int taskCount = stackTasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task task = stackTasks.get(i);
                mTaskProgressMap.put(task.key, pAtFrontMostTaskTop);

                if (i < (taskCount - 1)) {
                    // Increment the peek height
                    float pPeek = task.group.isFrontMostTask(task) ?
                            mBetweenAffiliationPOffset : mWithinAffiliationPOffset;
                    pAtFrontMostTaskTop += pPeek;
                }
            }

            mFrontMostTaskP = pAtFrontMostTaskTop;
            // Set the max scroll progress to the point at which the top of the front-most task
            // is aligned to the bottom of the stack (offset by nav bar, padding, and task height)
            mMaxStackScrollP = getBottomAlignedScrollProgress(pAtFrontMostTaskTop,
                    mTaskBottomPOffset + mTaskHeightPOffset);
            // Basically align the back-most task such that its progress is the same as the top of
            // the front most task at the max stack scroll
            mMinScrollP = getBottomAlignedScrollProgress(pAtBackMostTaskTop,
                    mTaskBottomPOffset + mTaskHeightPOffset);
        }

        if (!freeformTasks.isEmpty()) {
            // Calculate the cell width/height depending on the number of freeform tasks
            mFreeformCellXCount = Math.max(2, (int) Math.ceil(Math.sqrt(mNumFreeformTasks)));
            mFreeformCellYCount = Math.max(2, (int) Math.ceil((float) mNumFreeformTasks / mFreeformCellXCount));
            mFreeformCellWidth = mFreeformRect.width() / mFreeformCellXCount;
            mFreeformCellHeight = mFreeformRect.height() / mFreeformCellYCount;

            // Put each of the tasks in the progress map at a fixed index (does not need to actually
            // map to a scroll position, just by index)
            int taskCount = freeformTasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task task = freeformTasks.get(i);
                mTaskProgressMap.put(task.key, (mFrontMostTaskP + 1) + i);
            }

            // The max scroll includes the freeform workspace offset. As the scroll progress exceeds
            // mMaxStackScrollP up to mMaxScrollP, the stack will translate upwards and the freeform
            // workspace will be visible
            mMaxScrollP = mMaxStackScrollP + mFreeformWorkspacePOffset;
            mInitialScrollP = mMaxScrollP;
        } else {
            mMaxScrollP = mMaxStackScrollP;
            mInitialScrollP = Math.max(mMinScrollP, mMaxStackScrollP - mTaskHalfHeightPOffset);
        }
        if (DEBUG) {
            Log.d(TAG, "mNumStackTasks: " + mNumStackTasks);
            Log.d(TAG, "mNumFreeformTasks: " + mNumFreeformTasks);
            Log.d(TAG, "mMinScrollP: " + mMinScrollP);
            Log.d(TAG, "mMaxStackScrollP: " + mMaxStackScrollP);
            Log.d(TAG, "mMaxScrollP: " + mMaxScrollP);
        }
    }

    /**
     * Computes the maximum number of visible tasks and thumbnails.  Requires that
     * computeMinMaxScroll() is called first.
     */
    public VisibilityReport computeStackVisibilityReport(ArrayList<Task> tasks) {
        // Ensure minimum visibility count
        if (tasks.size() <= 1) {
            return new VisibilityReport(1, 1);
        }

        // If there are freeform tasks, then they will be the only ones visible
        int freeformTaskCount = 0;
        for (Task t : tasks) {
            if (t.isFreeformTask()) {
                freeformTaskCount++;
            }
        }
        if (freeformTaskCount > 0) {
            return new VisibilityReport(freeformTaskCount, freeformTaskCount);
        }

        // Otherwise, walk backwards in the stack and count the number of tasks and visible
        // thumbnails
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
        float stackOverscroll = (stackScroll - mMaxStackScrollP) / mFreeformWorkspacePOffset;
        int overscrollYOffset = 0;
        if (mNumFreeformTasks > 0) {
            overscrollYOffset = (int) (Math.max(0, stackOverscroll) * mFreeformRect.height());
        }

        if ((mNumFreeformTasks > 0) && (stackScroll > mMaxStackScrollP) &&
                (taskProgress > mFrontMostTaskP)) {
            // This is a freeform task, so lay it out in the freeform workspace
            int taskIndex = Math.round(taskProgress - (mFrontMostTaskP + 1));
            int x = taskIndex % mFreeformCellXCount;
            int y = taskIndex / mFreeformCellXCount;
            int frontTaskBottom = mStackRect.height() - mTaskBottomOffset;
            float scale = (float) mFreeformCellWidth / mTaskRect.width();
            int scaleXOffset = (int) (((1f - scale) * mTaskRect.width()) / 2);
            int scaleYOffset = (int) (((1f - scale) * mTaskRect.height()) / 2);
            transformOut.scale = scale;
            transformOut.translationX = x * mFreeformCellWidth - scaleXOffset;
            transformOut.translationY = frontTaskBottom - overscrollYOffset +
                    (y * mFreeformCellHeight) - scaleYOffset;
            transformOut.visible = true;
            return transformOut;

        } else if (mNumStackTasks == 1) {
            // Center the task in the stack, changing the scale will not follow the curve, but just
            // modulate some values directly
            float pTaskRelative = mMinScrollP - stackScroll;
            float scale = SINGLE_TASK_SCALE;
            int topOffset = (mStackRect.height() - mTaskBottomOffset - mTaskRect.height()) / 2;
            transformOut.scale = scale;
            transformOut.translationY = (int) (topOffset + (pTaskRelative * mStackRect.height())) -
                    overscrollYOffset;
            transformOut.translationZ = mMaxTranslationZ;
            transformOut.rect.set(mTaskRect);
            transformOut.rect.offset(0, transformOut.translationY);
            Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
            transformOut.visible = true;
            transformOut.p = pTaskRelative;
            return transformOut;

        } else {
            float pTaskRelative = taskProgress - stackScroll;
            if (mNumFreeformTasks > 0) {
                pTaskRelative = Math.min(mMaxStackScrollP, pTaskRelative);
            }
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
                    scaleYOffset - overscrollYOffset;
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
     * Update/get the transform
     */
    public TaskViewTransform getFreeformWorkspaceBounds(float stackScroll,
            TaskViewTransform transformOut) {
        transformOut.reset();
        if (mNumFreeformTasks == 0) {
            return transformOut;
        }

        if (stackScroll > mMaxStackScrollP) {
            float stackOverscroll = (stackScroll - mMaxStackScrollP) / mFreeformWorkspacePOffset;
            int overscrollYOffset = (int) (stackOverscroll * mFreeformRect.height());
            int frontTaskBottom = mStackRect.height() - mTaskBottomOffset;
            transformOut.visible = true;
            transformOut.alpha =
            transformOut.translationY = frontTaskBottom - overscrollYOffset;
        }
        return transformOut;
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

    /**
     * Maps a movement in screen y, relative to {@param downY}, to a movement in along the arc
     * length of the curve.  We know the curve is mostly flat, so we just map the length of the
     * screen along the arc-length proportionally (1/arclength).
     */
    public float getDeltaPForY(int downY, int y) {
        float deltaP = (float) (y - downY) / mStackRect.height() * (1f / sCurve.getArcLength());
        return -deltaP;
    }

    /**
     * This is the inverse of {@link #getDeltaPForY}.  Given a movement along the arc length
     * of the curve, map back to the screen y.
     */
    public int getYForDeltaP(float downScrollP, float p) {
        int y = (int) ((p - downScrollP) * mStackRect.height() * sCurve.getArcLength());
        return -y;
    }

    private float getBottomAlignedScrollProgress(float p, float pOffsetFromBottom) {
        // At scroll progress == p, then p is at the top of the stack
        // At scroll progress == p + 1, then p is at the bottom of the stack
        return p - (1 - pOffsetFromBottom);
    }
}
