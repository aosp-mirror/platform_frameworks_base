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

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.FloatProperty;
import android.util.Property;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.misc.FreePathInterpolator;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;

/**
 * Used to describe a visible range that can be normalized to [0, 1].
 */
class Range {
    final float relativeMin;
    final float relativeMax;
    float origin;
    float min;
    float max;

    public Range(float relMin, float relMax) {
        min = relativeMin = relMin;
        max = relativeMax = relMax;
    }

    /**
     * Offsets this range to a given absolute position.
     */
    public void offset(float x) {
        this.origin = x;
        min = x + relativeMin;
        max = x + relativeMax;
    }

    /**
     * Returns x normalized to the range 0 to 1 such that 0 = min, 0.5 = origin and 1 = max
     *
     * @param x is an absolute value in the same domain as origin
     */
    public float getNormalizedX(float x) {
        if (x < origin) {
            return 0.5f + 0.5f * (x - origin) / -relativeMin;
        } else {
            return 0.5f + 0.5f * (x - origin) / relativeMax;
        }
    }

    /**
     * Given a normalized {@param x} value in this range, projected onto the full range to get an
     * absolute value about the given {@param origin}.
     */
    public float getAbsoluteX(float normX) {
        if (normX < 0.5f) {
            return (normX - 0.5f) / 0.5f * -relativeMin;
        } else {
            return (normX - 0.5f) / 0.5f * relativeMax;
        }
    }

    /**
     * Returns whether a value at an absolute x would be within range.
     */
    public boolean isInRange(float absX) {
        return (absX >= Math.floor(min)) && (absX <= Math.ceil(max));
    }
}

/**
 * The layout logic for a TaskStackView.  This layout can have two states focused and unfocused,
 * and in the focused state, there is a task that is displayed more prominently in the stack.
 */
public class TaskStackLayoutAlgorithm {

    // The scale factor to apply to the user movement in the stack to unfocus it
    private static final float UNFOCUS_MULTIPLIER = 0.8f;

    // The various focus states
    public static final float STATE_FOCUSED = 1f;
    public static final float STATE_UNFOCUSED = 0f;

    public interface TaskStackLayoutAlgorithmCallbacks {
        void onFocusStateChanged(float prevFocusState, float curFocusState);
    }

    /**
     * The various stack/freeform states.
     */
    public static class StackState {

        public static final StackState FREEFORM_ONLY = new StackState(1f, 255);
        public static final StackState STACK_ONLY = new StackState(0f, 0);
        public static final StackState SPLIT = new StackState(0.5f, 255);

        public final float freeformHeightPct;
        public final int freeformBackgroundAlpha;

        /**
         * @param freeformHeightPct the percentage of the stack height (not including paddings) to
         *                          allocate to the freeform workspace
         * @param freeformBackgroundAlpha the background alpha for the freeform workspace
         */
        private StackState(float freeformHeightPct, int freeformBackgroundAlpha) {
            this.freeformHeightPct = freeformHeightPct;
            this.freeformBackgroundAlpha = freeformBackgroundAlpha;
        }

        /**
         * Resolves the stack state for the layout given a task stack.
         */
        public static StackState getStackStateForStack(TaskStack stack) {
            SystemServicesProxy ssp = Recents.getSystemServices();
            boolean hasFreeformWorkspaces = ssp.hasFreeformWorkspaceSupport();
            int freeformCount = stack.getFreeformTaskCount();
            int stackCount = stack.getStackTaskCount();
            if (hasFreeformWorkspaces && stackCount > 0 && freeformCount > 0) {
                return SPLIT;
            } else if (hasFreeformWorkspaces && freeformCount > 0) {
                return FREEFORM_ONLY;
            } else {
                return STACK_ONLY;
            }
        }

        /**
         * Computes the freeform and stack rect for this state.
         *
         * @param freeformRectOut the freeform rect to be written out
         * @param stackRectOut the stack rect, we only write out the top of the stack
         * @param taskStackBounds the full rect that the freeform rect can take up
         */
        public void computeRects(Rect freeformRectOut, Rect stackRectOut,
                Rect taskStackBounds, int widthPadding, int heightPadding, int stackBottomOffset) {
            int availableHeight = taskStackBounds.height() - stackBottomOffset;
            int ffPaddedHeight = (int) (availableHeight * freeformHeightPct);
            int ffHeight = Math.max(0, ffPaddedHeight - (2 * heightPadding));
            freeformRectOut.set(taskStackBounds.left + widthPadding,
                    taskStackBounds.top + heightPadding,
                    taskStackBounds.right - widthPadding,
                    taskStackBounds.top + heightPadding + ffHeight);
            stackRectOut.set(taskStackBounds.left + widthPadding,
                    taskStackBounds.top,
                    taskStackBounds.right - widthPadding,
                    taskStackBounds.bottom);
            if (ffPaddedHeight > 0) {
                stackRectOut.top += ffPaddedHeight;
            } else {
                stackRectOut.top += heightPadding;
            }
        }
    }

    /**
     * A Property wrapper around the <code>focusState</code> functionality handled by the
     * {@link TaskStackLayoutAlgorithm#setFocusState(float)} and
     * {@link TaskStackLayoutAlgorithm#getFocusState()} methods.
     */
    private static final Property<TaskStackLayoutAlgorithm, Float> FOCUS_STATE =
            new FloatProperty<TaskStackLayoutAlgorithm>("focusState") {
        @Override
        public void setValue(TaskStackLayoutAlgorithm object, float value) {
            object.setFocusState(value);
        }

        @Override
        public Float get(TaskStackLayoutAlgorithm object) {
            return object.getFocusState();
        }
    };

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
    private StackState mState = StackState.SPLIT;
    private TaskStackLayoutAlgorithmCallbacks mCb;

    // The task bounds (untransformed) for layout.  This rect is anchored at mTaskRoot.
    public Rect mTaskRect = new Rect();
    // The freeform workspace bounds, inset from the top by the search bar, and is a fixed height
    public Rect mFreeformRect = new Rect();
    // The stack bounds, inset from the top by the search bar, and runs to
    // the bottom of the screen
    public Rect mStackRect = new Rect();
    // This is the current system insets
    public Rect mSystemInsets = new Rect();
    // This is the bounds of the history button above the stack rect
    public Rect mHistoryButtonRect = new Rect();

    // The visible ranges when the stack is focused and unfocused
    private Range mUnfocusedRange;
    private Range mFocusedRange;

    // The offset from the top when scrolled to the top of the stack
    private int mFocusedPeekHeight;

    // The offset from the top of the stack to the top of the bounds when the stack is scrolled to
    // the end
    private int mStackTopOffset;

    // The offset from the bottom of the stack to the bottom of the bounds when the stack is
    // scrolled to the front
    private int mStackBottomOffset;

    // The paths defining the motion of the tasks when the stack is focused and unfocused
    private Path mUnfocusedCurve;
    private Path mFocusedCurve;
    private FreePathInterpolator mUnfocusedCurveInterpolator;
    private FreePathInterpolator mFocusedCurveInterpolator;

    // The state of the stack focus (0..1), which controls the transition of the stack from the
    // focused to non-focused state
    private float mFocusState;

    // The animator used to reset the focused state
    private ObjectAnimator mFocusStateAnimator;

    // The smallest scroll progress, at this value, the back most task will be visible
    float mMinScrollP;
    // The largest scroll progress, at this value, the front most task will be visible above the
    // navigation bar
    float mMaxScrollP;
    // The initial progress that the scroller is set when you first enter recents
    float mInitialScrollP;
    // The task progress for the front-most task in the stack
    float mFrontMostTaskP;

    // The last computed task counts
    int mNumStackTasks;
    int mNumFreeformTasks;

    // The min/max z translations
    int mMinTranslationZ;
    int mMaxTranslationZ;

    // Optimization, allows for quick lookup of task -> index
    private ArrayMap<Task.TaskKey, Integer> mTaskIndexMap = new ArrayMap<>();

    // The freeform workspace layout
    FreeformWorkspaceLayoutAlgorithm mFreeformLayoutAlgorithm;

    // The transform to place TaskViews at the front and back of the stack respectively
    TaskViewTransform mBackOfStackTransform = new TaskViewTransform();
    TaskViewTransform mFrontOfStackTransform = new TaskViewTransform();

    public TaskStackLayoutAlgorithm(Context context, TaskStackLayoutAlgorithmCallbacks cb) {
        Resources res = context.getResources();
        mContext = context;
        mCb = cb;

        mFocusedRange = new Range(res.getFloat(R.integer.recents_layout_focused_range_min),
                res.getFloat(R.integer.recents_layout_focused_range_max));
        mUnfocusedRange = new Range(res.getFloat(R.integer.recents_layout_unfocused_range_min),
                res.getFloat(R.integer.recents_layout_unfocused_range_max));
        mFocusState = getDefaultFocusState();
        mFocusedPeekHeight = res.getDimensionPixelSize(R.dimen.recents_layout_focused_peek_size);

        mMinTranslationZ = res.getDimensionPixelSize(R.dimen.recents_task_view_z_min);
        mMaxTranslationZ = res.getDimensionPixelSize(R.dimen.recents_task_view_z_max);
        mFreeformLayoutAlgorithm = new FreeformWorkspaceLayoutAlgorithm(context);
    }

    /**
     * Resets this layout when the stack view is reset.
     */
    public void reset() {
        setFocusState(getDefaultFocusState());
    }

    /**
     * Sets the system insets.
     */
    public void setSystemInsets(Rect systemInsets) {
        mSystemInsets.set(systemInsets);
    }

    /**
     * Sets the focused state.
     */
    public void setFocusState(float focusState) {
        float prevFocusState = mFocusState;
        mFocusState = focusState;
        updateFrontBackTransforms();
        if (mCb != null) {
            mCb.onFocusStateChanged(prevFocusState, focusState);
        }
    }

    /**
     * Gets the focused state.
     */
    public float getFocusState() {
        return mFocusState;
    }

    /**
     * Computes the stack and task rects.  The given task stack bounds is the whole bounds not
     * including the search bar.
     */
    public void initialize(Rect taskStackBounds, StackState state) {
        RecentsConfiguration config = Recents.getConfiguration();
        int widthPadding = (int) (config.taskStackWidthPaddingPct * taskStackBounds.width());
        int heightPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_stack_top_padding);
        Rect lastStackRect = new Rect(mStackRect);

        // The freeform height is the visible height (not including system insets) - padding above
        // freeform and below stack - gap between the freeform and stack
        mState = state;
        mStackTopOffset = mFocusedPeekHeight + heightPadding;
        mStackBottomOffset = mSystemInsets.bottom + heightPadding;
        state.computeRects(mFreeformRect, mStackRect, taskStackBounds, widthPadding, heightPadding,
                mStackBottomOffset);
        // The history button will take the full un-padded header space above the stack
        mHistoryButtonRect.set(mStackRect.left, mStackRect.top - heightPadding,
                mStackRect.right, mStackRect.top + mFocusedPeekHeight);

        // Anchor the task rect to the top-center of the non-freeform stack rect
        float aspect = (float) (taskStackBounds.width() - mSystemInsets.left - mSystemInsets.right)
                / (taskStackBounds.height() - mSystemInsets.bottom);
        int width = mStackRect.width();
        int minHeight = mStackRect.height() - mFocusedPeekHeight - mStackBottomOffset;
        int height = (int) Math.min(width / aspect, minHeight);
        mTaskRect.set(mStackRect.left, mStackRect.top,
                mStackRect.left + width, mStackRect.top + height);

        // Short circuit here if the stack rects haven't changed so we don't do all the work below
        if (lastStackRect.equals(mStackRect)) {
            return;
        }

        // Reinitialize the focused and unfocused curves
        mUnfocusedCurve = constructUnfocusedCurve();
        mUnfocusedCurveInterpolator = new FreePathInterpolator(mUnfocusedCurve);
        mFocusedCurve = constructFocusedCurve();
        mFocusedCurveInterpolator = new FreePathInterpolator(mFocusedCurve);
        updateFrontBackTransforms();
    }

    /**
     * Computes the minimum and maximum scroll progress values and the progress values for each task
     * in the stack.
     */
    void update(TaskStack stack, ArraySet<Task.TaskKey> ignoreTasksSet) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Clear the progress map
        mTaskIndexMap.clear();

        // Return early if we have no tasks
        ArrayList<Task> tasks = stack.getStackTasks();
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
            if (ignoreTasksSet.contains(task.key)) {
                continue;
            }
            if (task.isFreeformTask()) {
                freeformTasks.add(task);
            } else {
                stackTasks.add(task);
            }
        }
        mNumStackTasks = stackTasks.size();
        mNumFreeformTasks = freeformTasks.size();

        // Put each of the tasks in the progress map at a fixed index (does not need to actually
        // map to a scroll position, just by index)
        int taskCount = stackTasks.size();
        for (int i = 0; i < taskCount; i++) {
            Task task = stackTasks.get(i);
            mTaskIndexMap.put(task.key, i);
        }

        // Calculate the min/max scroll
        if (getDefaultFocusState() > 0f) {
            mMinScrollP = 0;
            mMaxScrollP = Math.max(mMinScrollP, mNumStackTasks - 1);
        } else {
            if (!ssp.hasFreeformWorkspaceSupport() && mNumStackTasks == 1) {
                mMinScrollP = mMaxScrollP = 0;
            } else {
                float bottomOffsetPct = (float) (mStackBottomOffset + mTaskRect.height()) /
                        mStackRect.height();
                float normX = mUnfocusedCurveInterpolator.getX(bottomOffsetPct);
                mMinScrollP = 0;
                mMaxScrollP = Math.max(mMinScrollP,
                        (mNumStackTasks - 1) - Math.max(0, mUnfocusedRange.getAbsoluteX(normX)));
            }
        }

        if (!freeformTasks.isEmpty()) {
            mFreeformLayoutAlgorithm.update(freeformTasks, this);
            mInitialScrollP = mMaxScrollP;
        } else {
            Task launchTask = stack.getLaunchTarget();
            int launchTaskIndex = launchTask != null
                    ? stack.indexOfStackTask(launchTask)
                    : mNumStackTasks - 1;
            if (!ssp.hasFreeformWorkspaceSupport() && mNumStackTasks == 1) {
                mInitialScrollP = mMinScrollP;
            } else if (getDefaultFocusState() > 0f) {
                RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
                if (launchState.launchedFromHome) {
                    mInitialScrollP = Math.max(mMinScrollP, Math.min(mMaxScrollP, launchTaskIndex));
                } else {
                    mInitialScrollP = Math.max(mMinScrollP, Math.min(mMaxScrollP,
                            launchTaskIndex - 1));
                }
            } else {
                float offsetPct = (float) (mTaskRect.height() / 2) / mStackRect.height();
                float normX = mUnfocusedCurveInterpolator.getX(offsetPct);
                mInitialScrollP = Math.max(mMinScrollP, Math.min(mMaxScrollP,
                        launchTaskIndex - mUnfocusedRange.getAbsoluteX(normX)));
            }
        }
    }

    /**
     * Updates this stack when a scroll happens.
     */
    public void updateFocusStateOnScroll(int yMovement) {
        Utilities.cancelAnimationWithoutCallbacks(mFocusStateAnimator);
        if (mFocusState > STATE_UNFOCUSED) {
            float delta = (float) yMovement / (UNFOCUS_MULTIPLIER * mStackRect.height());
            setFocusState(mFocusState - Math.min(mFocusState, Math.abs(delta)));
        }
    }

    /**
     * Aniamtes the focused state back to its orginal state.
     */
    public void animateFocusState(float newState) {
        Utilities.cancelAnimationWithoutCallbacks(mFocusStateAnimator);
        if (Float.compare(newState, getFocusState()) != 0) {
            mFocusStateAnimator = ObjectAnimator.ofFloat(this, FOCUS_STATE, getFocusState(),
                    newState);
            mFocusStateAnimator.setDuration(mContext.getResources().getInteger(
                    R.integer.recents_animate_task_stack_scroll_duration));
            mFocusStateAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            mFocusStateAnimator.start();
        }
    }

    /**
     * Returns the default focus state.
     */
    public float getDefaultFocusState() {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        RecentsDebugFlags debugFlags = Recents.getDebugFlags();
        if (launchState.launchedWithAltTab || debugFlags.isInitialStatePaging()) {
            return STATE_FOCUSED;
        }
        return STATE_UNFOCUSED;
    }

    /**
     * Returns the TaskViewTransform that would put the task just off the back of the stack.
     */
    public TaskViewTransform getBackOfStackTransform() {
        return mBackOfStackTransform;
    }

    /**
     * Returns the TaskViewTransform that would put the task just off the front of the stack.
     */
    public TaskViewTransform getFrontOfStackTransform() {
        return mFrontOfStackTransform;
    }

    /**
     *
     * Returns the current stack state.
     */
    public StackState getStackState() {
        return mState;
    }

    /**
     * Computes the maximum number of visible tasks and thumbnails when the scroll is at the initial
     * stack scroll.  Requires that update() is called first.
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
        TaskViewTransform tmpTransform = new TaskViewTransform();
        Range currentRange = getDefaultFocusState() > 0f ? mFocusedRange : mUnfocusedRange;
        currentRange.offset(mInitialScrollP);
        int taskBarHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_task_bar_height);
        int numVisibleTasks = Math.max(mNumFreeformTasks, 1);
        int numVisibleThumbnails = Math.max(mNumFreeformTasks, 1);
        float prevScreenY = Integer.MAX_VALUE;
        for (int i = tasks.size() - 1; i >= 0; i--) {
            Task task = tasks.get(i);

            // Skip freeform
            if (task.isFreeformTask()) {
                continue;
            }

            // Skip invisible
            float taskProgress = getStackScrollForTask(task);
            if (!currentRange.isInRange(taskProgress)) {
                continue;
            }

            boolean isFrontMostTaskInGroup = task.group == null || task.group.isFrontMostTask(task);
            if (isFrontMostTaskInGroup) {
                getStackTransform(taskProgress, mInitialScrollP, tmpTransform, null,
                        false /* ignoreSingleTaskCase */);
                float screenY = tmpTransform.rect.top;
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
                        taskProgress = getStackScrollForTask(tasks.get(j));
                        if (!currentRange.isInRange(taskProgress)) {
                            continue;
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
            TaskViewTransform transformOut, TaskViewTransform frontTransform) {
        if (mFreeformLayoutAlgorithm.isTransformAvailable(task, this)) {
            mFreeformLayoutAlgorithm.getTransform(task, transformOut, this);
            return transformOut;
        } else {
            // Return early if we have an invalid index
            if (task == null || !mTaskIndexMap.containsKey(task.key)) {
                transformOut.reset();
                return transformOut;
            }
            return getStackTransform(mTaskIndexMap.get(task.key), stackScroll, transformOut,
                    frontTransform, false /* ignoreSingleTaskCase */);
        }
    }

    /**
     * Update/get the transform.
     *
     * @param ignoreSingleTaskCase When set, will ensure that the transform computed does not take
     *                             into account the special single-task case.  This is only used
     *                             internally to ensure that we can calculate the transform for any
     *                             position in the stack.
     */
    public TaskViewTransform getStackTransform(float taskProgress, float stackScroll,
            TaskViewTransform transformOut, TaskViewTransform frontTransform,
            boolean ignoreSingleTaskCase) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Compute the focused and unfocused offset
        mUnfocusedRange.offset(stackScroll);
        float p = mUnfocusedRange.getNormalizedX(taskProgress);
        float yp = mUnfocusedCurveInterpolator.getInterpolation(p);
        float unfocusedP = p;
        int unFocusedY = (int) (Math.max(0f, (1f - yp)) * mStackRect.height());
        boolean unfocusedVisible = mUnfocusedRange.isInRange(taskProgress);
        int focusedY = 0;
        boolean focusedVisible = true;
        if (mFocusState > 0f) {
            mFocusedRange.offset(stackScroll);
            p = mFocusedRange.getNormalizedX(taskProgress);
            yp = mFocusedCurveInterpolator.getInterpolation(p);
            focusedY = (int) (Math.max(0f, (1f - yp)) * mStackRect.height());
            focusedVisible = mFocusedRange.isInRange(taskProgress);
        }

        // Skip if the task is not visible
        if (!unfocusedVisible && !focusedVisible) {
            transformOut.reset();
            return transformOut;
        }

        int x = (mStackRect.width() - mTaskRect.width()) / 2;
        int y;
        float z;
        float relP;
        if (!ssp.hasFreeformWorkspaceSupport() && mNumStackTasks == 1 && !ignoreSingleTaskCase) {
            // When there is exactly one task, then decouple the task from the stack and just move
            // in screen space
            p = (mMinScrollP - stackScroll) / mNumStackTasks;
            int centerYOffset = (mStackRect.top - mTaskRect.top) +
                    (mStackRect.height() - mTaskRect.height()) / 2;
            y = centerYOffset + getYForDeltaP(p, 0);
            z = mMaxTranslationZ;
            relP = 1f;

        } else {
            // Otherwise, update the task to the stack layout
            y = unFocusedY + (int) (mFocusState * (focusedY - unFocusedY));
            y += (mStackRect.top - mTaskRect.top);
            z = Math.max(mMinTranslationZ, Math.min(mMaxTranslationZ,
                    mMinTranslationZ + (p * (mMaxTranslationZ - mMinTranslationZ))));
            if (mNumStackTasks == 1) {
                relP = 1f;
            } else {
                relP = Math.min(mMaxScrollP, unfocusedP);
            }
        }

        // Fill out the transform
        transformOut.scale = 1f;
        transformOut.alpha = 1f;
        transformOut.translationZ = z;
        transformOut.rect.set(mTaskRect);
        transformOut.rect.offset(x, y);
        Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
        transformOut.visible = (transformOut.rect.top < mStackRect.bottom) &&
                (frontTransform == null || transformOut.rect.top != frontTransform.rect.top);
        transformOut.p = relP;
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
        if (!mTaskIndexMap.containsKey(t.key)) return 0f;
        return mTaskIndexMap.get(t.key);
    }

    /**
     * Maps a movement in screen y, relative to {@param downY}, to a movement in along the arc
     * length of the curve.  We know the curve is mostly flat, so we just map the length of the
     * screen along the arc-length proportionally (1/arclength).
     */
    public float getDeltaPForY(int downY, int y) {
        float deltaP = (float) (y - downY) / mStackRect.height() *
                mUnfocusedCurveInterpolator.getArcLength();
        return -deltaP;
    }

    /**
     * This is the inverse of {@link #getDeltaPForY}.  Given a movement along the arc length
     * of the curve, map back to the screen y.
     */
    public int getYForDeltaP(float downScrollP, float p) {
        int y = (int) ((p - downScrollP) * mStackRect.height() *
                (1f / mUnfocusedCurveInterpolator.getArcLength()));
        return -y;
    }

    /**
     * Creates a new path for the focused curve.
     */
    private Path constructFocusedCurve() {
        int taskBarHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_task_bar_height);

        // Initialize the focused curve. This curve is a piecewise curve composed of several
        // quadradic beziers that goes from (0,1) through (0.5, peek height offset),
        // (0.667, next task offset), (0.833, bottom task offset), and (1,0).
        float peekHeightPct = (float) mFocusedPeekHeight / mStackRect.height();
        Path p = new Path();
        p.moveTo(0f, 1f);
        p.lineTo(0.5f, 1f - peekHeightPct);
        p.lineTo(0.66666667f, (float) (taskBarHeight * 3) / mStackRect.height());
        p.lineTo(0.83333333f, (float) (taskBarHeight / 2) / mStackRect.height());
        p.lineTo(1f, 0f);
        return p;
    }

    /**
     * Creates a new path for the unfocused curve.
     */
    private Path constructUnfocusedCurve() {
        // Initialize the unfocused curve. This curve is a piecewise curve composed of two quadradic
        // beziers that goes from (0,1) through (0.5, peek height offset) and ends at (1,0).  This
        // ensures that we match the range, at which 0.5 represents the stack scroll at the current
        // task progress.  Because the height offset can change depending on a resource, we compute
        // the control point of the second bezier such that between it and a first known point,
        // there is a tangent at (0.5, peek height offset).
        float cpoint1X = 0.4f;
        float cpoint1Y = 1f;
        float peekHeightPct = (float) mFocusedPeekHeight / mStackRect.height();
        float slope = ((1f - peekHeightPct) - cpoint1Y) / (0.5f - cpoint1X);
        float b = 1f - slope * cpoint1X;
        float cpoint2X = 0.75f;
        float cpoint2Y = slope * cpoint2X + b;
        Path p = new Path();
        p.moveTo(0f, 1f);
        p.cubicTo(0f, 1f, cpoint1X, 1f, 0.5f, 1f - peekHeightPct);
        p.cubicTo(0.5f, 1f - peekHeightPct, cpoint2X, cpoint2Y, 1f, 0f);
        return p;
    }

    /**
     * Updates the current transforms that would put a TaskView at the front and back of the stack.
     */
    private void updateFrontBackTransforms() {
        // Return early if we have not yet initialized
        if (mStackRect.isEmpty()) {
            return;
        }

        float min = mUnfocusedRange.relativeMin +
                mFocusState * (mFocusedRange.relativeMin - mUnfocusedRange.relativeMin);
        float max = mUnfocusedRange.relativeMax +
                mFocusState * (mFocusedRange.relativeMax - mUnfocusedRange.relativeMax);
        getStackTransform(min, 0f, mBackOfStackTransform, null, true /* ignoreSingleTaskCase */);
        getStackTransform(max, 0f, mFrontOfStackTransform, null, true /* ignoreSingleTaskCase */);
        mBackOfStackTransform.visible = true;
        mFrontOfStackTransform.visible = true;
    }
}
