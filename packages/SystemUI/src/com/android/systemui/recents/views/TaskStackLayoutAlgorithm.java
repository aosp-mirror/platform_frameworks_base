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
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.HashMap;


/**
 * The layout logic for a TaskStackView.
 */
public class TaskStackLayoutAlgorithm {

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

    // The task bounds (untransformed) for layout.  This rect is anchored at mTaskRoot.
    public Rect mTaskRect = new Rect();
    // The freeform workspace bounds, inset from the top by the search bar, and is a fixed height
    public Rect mFreeformRect = new Rect();
    // The freeform stack bounds, inset from the top by the search bar and freeform workspace, and
    // runs to the bottom of the screen
    private Rect mFreeformStackRect = new Rect();
    // The stack bounds, inset from the top by the search bar, and runs to
    // the bottom of the screen
    private Rect mStackRect = new Rect();
    // The current stack rect, can either by mFreeformStackRect or mStackRect depending on whether
    // there is a freeform workspace
    public Rect mCurrentStackRect;
    // This is the current system insets
    public Rect mSystemInsets = new Rect();

    // The smallest scroll progress, at this value, the back most task will be visible
    float mMinScrollP;
    // The largest scroll progress, at this value, the front most task will be visible above the
    // navigation bar
    float mMaxScrollP;
    // The initial progress that the scroller is set when you first enter recents
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
    // The front-most task bottom offset
    int mStackBottomOffset;
    // The relative progress to ensure that the offset from the bottom of the stack to the bottom
    // of the task is respected
    float mStackBottomPOffset;

    // The last computed task counts
    int mNumStackTasks;
    int mNumFreeformTasks;
    // The min/max z translations
    int mMinTranslationZ;
    int mMaxTranslationZ;

    // Optimization, allows for quick lookup of task -> progress
    HashMap<Task.TaskKey, Float> mTaskProgressMap = new HashMap<>();

    // The freeform workspace layout
    FreeformWorkspaceLayoutAlgorithm mFreeformLayoutAlgorithm;

    // Log function
    static ParametricCurve sCurve;

    public TaskStackLayoutAlgorithm(Context context) {
        Resources res = context.getResources();
        mMinTranslationZ = res.getDimensionPixelSize(R.dimen.recents_task_view_z_min);
        mMaxTranslationZ = res.getDimensionPixelSize(R.dimen.recents_task_view_z_max);
        mContext = context;
        mFreeformLayoutAlgorithm = new FreeformWorkspaceLayoutAlgorithm();
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
                    SystemServicesProxy ssp = Recents.getSystemServices();
                    if (ssp.hasFreeformWorkspaceSupport()) {
                        return 1f;
                    }

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
     * Computes the stack and task rects.  The given task stack bounds is the whole bounds not
     * including the search bar.
     */
    public void initialize(Rect taskStackBounds) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        RecentsConfiguration config = Recents.getConfiguration();
        int widthPadding = (int) (config.taskStackWidthPaddingPct * taskStackBounds.width());
        int heightPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_stack_top_padding);

        // The freeform height is the visible height (not including system insets) - padding above
        // freeform and below stack - gap between the freeform and stack
        mStackBottomOffset = mSystemInsets.bottom + heightPadding;
        int ffHeight = (taskStackBounds.height() - 2 * heightPadding - mStackBottomOffset) / 2;
        mFreeformRect.set(taskStackBounds.left + widthPadding,
                taskStackBounds.top + heightPadding,
                taskStackBounds.right - widthPadding,
                taskStackBounds.top + heightPadding + ffHeight);
        mFreeformStackRect.set(taskStackBounds.left + widthPadding,
                taskStackBounds.top + heightPadding + ffHeight + heightPadding,
                taskStackBounds.right - widthPadding,
                taskStackBounds.bottom);
        mStackRect.set(taskStackBounds.left + widthPadding,
                taskStackBounds.top + heightPadding,
                taskStackBounds.right - widthPadding,
                taskStackBounds.bottom);
        // Anchor the task rect to the top-center of the non-freeform stack rect
        int size = mStackRect.width();
        mTaskRect.set(mStackRect.left, mStackRect.top,
                mStackRect.left + size, mStackRect.top + size);
        mCurrentStackRect = ssp.hasFreeformWorkspaceSupport() ? mFreeformStackRect : mStackRect;

        // Compute the progress offsets
        int withinAffiliationOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_task_bar_height);
        int betweenAffiliationOffset = (int) (VISIBLE_TASK_HEIGHT_BETWEEN_TASKS * mTaskRect.height());
        mWithinAffiliationPOffset = sCurve.computePOffsetForScaledHeight(withinAffiliationOffset,
                mCurrentStackRect);
        mBetweenAffiliationPOffset = sCurve.computePOffsetForScaledHeight(betweenAffiliationOffset,
                mCurrentStackRect);
        mTaskHeightPOffset = sCurve.computePOffsetForScaledHeight(mTaskRect.height(),
                mCurrentStackRect);
        mTaskHalfHeightPOffset = sCurve.computePOffsetForScaledHeight(mTaskRect.height() / 2,
                mCurrentStackRect);
        mStackBottomPOffset = sCurve.computePOffsetForHeight(mStackBottomOffset, mCurrentStackRect);

        if (DEBUG) {
            Log.d(TAG, "initialize");
            Log.d(TAG, "\tarclength: " + sCurve.getArcLength());
            Log.d(TAG, "\tmFreeformRect: " + mFreeformRect);
            Log.d(TAG, "\tmFreeformStackRect: " + mFreeformStackRect);
            Log.d(TAG, "\tmStackRect: " + mStackRect);
            Log.d(TAG, "\tmTaskRect: " + mTaskRect);
            Log.d(TAG, "\tmSystemInsets: " + mSystemInsets);

            Log.d(TAG, "\tpWithinAffiliateOffset: " + mWithinAffiliationPOffset);
            Log.d(TAG, "\tpBetweenAffiliateOffset: " + mBetweenAffiliationPOffset);
            Log.d(TAG, "\tmTaskHeightPOffset: " + mTaskHeightPOffset);
            Log.d(TAG, "\tmTaskHalfHeightPOffset: " + mTaskHalfHeightPOffset);
            Log.d(TAG, "\tmStackBottomPOffset: " + mStackBottomPOffset);

            Log.d(TAG, "\ty at p=0: " + sCurve.pToX(0f, mCurrentStackRect));
            Log.d(TAG, "\ty at p=1: " + sCurve.pToX(1f, mCurrentStackRect));
        }
    }

    /**
     * Computes the minimum and maximum scroll progress values and the progress values for each task
     * in the stack.
     */
    void update(TaskStack stack) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Clear the progress map
        mTaskProgressMap.clear();

        // Return early if we have no tasks
        ArrayList<Task> tasks = stack.getTasks();
        if (tasks.isEmpty()) {
            mFrontMostTaskP = 0;
            mMinScrollP = mMaxScrollP = 0;
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

        float pAtBackMostTaskTop = 0;
        float pAtFrontMostTaskTop = pAtBackMostTaskTop;
        if (!stackTasks.isEmpty()) {
            // Update the for each task from back to front.
            int taskCount = stackTasks.size();
            for (int i = 0; i < taskCount; i++) {
                Task task = stackTasks.get(i);
                mTaskProgressMap.put(task.key, pAtFrontMostTaskTop);

                if (DEBUG) {
                    Log.d(TAG, "Update: " + task.activityLabel + " p: " + pAtFrontMostTaskTop);
                }

                if (i < (taskCount - 1)) {
                    // Increment the peek height
                    float pPeek = task.group == null || task.group.isFrontMostTask(task) ?
                            mBetweenAffiliationPOffset : mWithinAffiliationPOffset;
                    pAtFrontMostTaskTop += pPeek;
                }
            }

            mFrontMostTaskP = pAtFrontMostTaskTop;
            if (mNumStackTasks > 1) {
                // Set the stack end scroll progress to the point at which the bottom of the front-most
                // task is aligned to the bottom of the stack
                mMaxScrollP = alignToStackBottom(pAtFrontMostTaskTop,
                        mStackBottomPOffset + (ssp.hasFreeformWorkspaceSupport() ?
                                mTaskHalfHeightPOffset : mTaskHeightPOffset));
                // Basically align the back-most task such that the last two tasks would be visible
                mMinScrollP = alignToStackBottom(pAtBackMostTaskTop,
                        mStackBottomPOffset + (ssp.hasFreeformWorkspaceSupport() ?
                                mTaskHalfHeightPOffset : mTaskHeightPOffset));
            } else {
                // When there is a single item, then just make all the stack progresses the same
                mMinScrollP = mMaxScrollP = 0;
            }
        }

        if (!freeformTasks.isEmpty()) {
            mFreeformLayoutAlgorithm.update(freeformTasks, this);
            mInitialScrollP = mMaxScrollP;
        } else {
            mInitialScrollP = Math.max(mMinScrollP, mMaxScrollP - mTaskHalfHeightPOffset);
        }

        if (DEBUG) {
            Log.d(TAG, "mNumStackTasks: " + mNumStackTasks);
            Log.d(TAG, "mNumFreeformTasks: " + mNumFreeformTasks);
            Log.d(TAG, "mMinScrollP: " + mMinScrollP);
            Log.d(TAG, "mMaxScrollP: " + mMaxScrollP);
        }
    }

    /**
     * Computes the maximum number of visible tasks and thumbnails.  Requires that
     * update() is called first.
     */
    public VisibilityReport computeStackVisibilityReport(ArrayList<Task> tasks) {
        // Ensure minimum visibility count
        if (tasks.size() <= 1) {
            return new VisibilityReport(1, 1);
        }

        // Quick return when there are no stack tasks
        if (mNumStackTasks == 0) {
            return new VisibilityReport(Math.max(mNumFreeformTasks, 1),
                    Math.max(mNumFreeformTasks, 1));
        }

        // Otherwise, walk backwards in the stack and count the number of tasks and visible
        // thumbnails and add that to the total freeform task count
        int taskHeight = mTaskRect.height();
        int taskBarHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_task_bar_height);
        int numVisibleTasks = Math.max(mNumFreeformTasks, 1);
        int numVisibleThumbnails = Math.max(mNumFreeformTasks, 1);
        Task firstNonFreeformTask = tasks.get(tasks.size() - mNumFreeformTasks - 1);
        float progress = mTaskProgressMap.get(firstNonFreeformTask.key) - mInitialScrollP;
        int prevScreenY = sCurve.pToX(progress, mCurrentStackRect);
        for (int i = tasks.size() - 2; i >= 0; i--) {
            Task task = tasks.get(i);
            if (task.isFreeformTask()) {
                continue;
            }

            progress = mTaskProgressMap.get(task.key) - mInitialScrollP;
            if (progress < 0) {
                break;
            }
            boolean isFrontMostTaskInGroup = task.group == null || task.group.isFrontMostTask(task);
            if (isFrontMostTaskInGroup) {
                float scaleAtP = sCurve.pToScale(progress);
                int scaleYOffsetAtP = (int) (((1f - scaleAtP) * taskHeight) / 2);
                int screenY = sCurve.pToX(progress, mCurrentStackRect) + scaleYOffsetAtP;
                boolean hasVisibleThumbnail = (prevScreenY - screenY) > taskBarHeight;
                if (hasVisibleThumbnail) {
                    numVisibleThumbnails++;
                    numVisibleTasks++;
                    prevScreenY = screenY;
                } else {
                    // Once we hit the next front most task that does not have a visible thumbnail,
                    // w  alk through remaining visible set
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
        if (mFreeformLayoutAlgorithm.isTransformAvailable(task, this)) {
            mFreeformLayoutAlgorithm.getTransform(task, transformOut, this);
            return transformOut;
        } else {
            // Return early if we have an invalid index
            if (task == null || !mTaskProgressMap.containsKey(task.key)) {
                transformOut.reset();
                return transformOut;
            }
            return getStackTransform(mTaskProgressMap.get(task.key), stackScroll, transformOut,
                    prevTransform);
        }
    }

    /** Update/get the transform */
    public TaskViewTransform getStackTransform(float taskProgress, float stackScroll,
            TaskViewTransform transformOut, TaskViewTransform prevTransform) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        if (!ssp.hasFreeformWorkspaceSupport() && mNumStackTasks == 1) {
            // Center the task in the stack, changing the scale will not follow the curve, but just
            // modulate some values directly
            float pTaskRelative = mMinScrollP - stackScroll;
            float scale = ssp.hasFreeformWorkspaceSupport() ? 1f : SINGLE_TASK_SCALE;
            int topOffset = (mCurrentStackRect.top - mTaskRect.top) +
                    (mCurrentStackRect.height() - mTaskRect.height()) / 2;
            transformOut.scale = scale;
            transformOut.translationX = (mStackRect.width() - mTaskRect.width()) / 2;
            transformOut.translationY = (int) (topOffset + (pTaskRelative * mCurrentStackRect.height()));
            transformOut.translationZ = mMaxTranslationZ;
            transformOut.rect.set(mTaskRect);
            transformOut.rect.offset(transformOut.translationX, transformOut.translationY);
            Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
            transformOut.visible = true;
            transformOut.p = pTaskRelative;
            return transformOut;

        } else {
            float pTaskRelative = taskProgress - stackScroll;
            float pBounded = Math.max(0, Math.min(pTaskRelative, 1f));
            if (DEBUG) {
                Log.d(TAG, "getStackTransform (normal): " + taskProgress + ", " + stackScroll);
            }

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
            transformOut.translationX = (mStackRect.width() - mTaskRect.width()) / 2;
            transformOut.translationY = (mCurrentStackRect.top - mTaskRect.top) +
                    (sCurve.pToX(pBounded, mCurrentStackRect) - mCurrentStackRect.top) -
                    scaleYOffset;
            transformOut.translationZ = Math.max(mMinTranslationZ,
                    mMinTranslationZ + (pBounded * (mMaxTranslationZ - mMinTranslationZ)));
            transformOut.rect.set(mTaskRect);
            transformOut.rect.offset(transformOut.translationX, transformOut.translationY);
            Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
            transformOut.visible = true;
            transformOut.p = pTaskRelative;
            if (DEBUG) {
                Log.d(TAG, "\t" + transformOut);
            }

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

    /**
     * Maps a movement in screen y, relative to {@param downY}, to a movement in along the arc
     * length of the curve.  We know the curve is mostly flat, so we just map the length of the
     * screen along the arc-length proportionally (1/arclength).
     */
    public float getDeltaPForY(int downY, int y) {
        float deltaP = (float) (y - downY) / mCurrentStackRect.height() * (1f / sCurve.getArcLength());
        return -deltaP;
    }

    /**
     * This is the inverse of {@link #getDeltaPForY}.  Given a movement along the arc length
     * of the curve, map back to the screen y.
     */
    public int getYForDeltaP(float downScrollP, float p) {
        int y = (int) ((p - downScrollP) * mCurrentStackRect.height() * sCurve.getArcLength());
        return -y;
    }

    private float alignToStackTop(float p) {
        // At scroll progress == p, then p is at the top of the stack
        return p;
    }

    private float alignToStackBottom(float p, float pOffsetFromBottom) {
        // At scroll progress == p, then p is at the top of the stack
        // At scroll progress == p + 1, then p is at the bottom of the stack
        return p - (1 - pOffsetFromBottom);
    }
}
