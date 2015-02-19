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

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

/* A task view */
public class TaskView extends FrameLayout implements Task.TaskCallbacks,
        View.OnClickListener, View.OnLongClickListener {

    /** The TaskView callbacks */
    interface TaskViewCallbacks {
        public void onTaskViewAppIconClicked(TaskView tv);
        public void onTaskViewAppInfoClicked(TaskView tv);
        public void onTaskViewClicked(TaskView tv, Task task, boolean lockToTask);
        public void onTaskViewDismissed(TaskView tv);
        public void onTaskViewClipStateChanged(TaskView tv);
        public void onTaskViewFocusChanged(TaskView tv, boolean focused);
    }

    RecentsConfiguration mConfig;

    float mTaskProgress;
    ObjectAnimator mTaskProgressAnimator;
    float mMaxDimScale;
    int mDimAlpha;
    AccelerateInterpolator mDimInterpolator = new AccelerateInterpolator(1f);
    PorterDuffColorFilter mDimColorFilter = new PorterDuffColorFilter(0, PorterDuff.Mode.SRC_ATOP);
    Paint mDimLayerPaint = new Paint();
    float mActionButtonTranslationZ;

    Task mTask;
    boolean mTaskDataLoaded;
    boolean mIsFocused;
    boolean mFocusAnimationsEnabled;
    boolean mClipViewInStack;
    AnimateableViewBounds mViewBounds;

    View mContent;
    TaskViewThumbnail mThumbnailView;
    TaskViewHeader mHeaderView;
    View mActionButtonView;
    TaskViewCallbacks mCb;

    // Optimizations
    ValueAnimator.AnimatorUpdateListener mUpdateDimListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    setTaskProgress((Float) animation.getAnimatedValue());
                }
            };


    public TaskView(Context context) {
        this(context, null);
    }

    public TaskView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        mMaxDimScale = mConfig.taskStackMaxDim / 255f;
        mClipViewInStack = true;
        mViewBounds = new AnimateableViewBounds(this, mConfig.taskViewRoundedCornerRadiusPx);
        setTaskProgress(getTaskProgress());
        setDim(getDim());
        if (mConfig.fakeShadows) {
            setBackground(new FakeShadowDrawable(context.getResources(), mConfig));
        }
        setOutlineProvider(mViewBounds);
    }

    /** Set callback */
    void setCallbacks(TaskViewCallbacks cb) {
        mCb = cb;
    }

    /** Resets this TaskView for reuse. */
    void reset() {
        resetViewProperties();
        resetNoUserInteractionState();
        setClipViewInStack(false);
        setCallbacks(null);
    }

    /** Gets the task */
    Task getTask() {
        return mTask;
    }

    /** Returns the view bounds. */
    AnimateableViewBounds getViewBounds() {
        return mViewBounds;
    }

    @Override
    protected void onFinishInflate() {
        // Bind the views
        mContent = findViewById(R.id.task_view_content);
        mHeaderView = (TaskViewHeader) findViewById(R.id.task_view_bar);
        mThumbnailView = (TaskViewThumbnail) findViewById(R.id.task_view_thumbnail);
        mThumbnailView.updateClipToTaskBar(mHeaderView);
        mActionButtonView = findViewById(R.id.lock_to_app_fab);
        mActionButtonView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                // Set the outline to match the FAB background
                outline.setOval(0, 0, mActionButtonView.getWidth(), mActionButtonView.getHeight());
            }
        });
        mActionButtonTranslationZ = mActionButtonView.getTranslationZ();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        int widthWithoutPadding = width - mPaddingLeft - mPaddingRight;
        int heightWithoutPadding = height - mPaddingTop - mPaddingBottom;

        // Measure the content
        mContent.measure(MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY));

        // Measure the bar view, and action button
        mHeaderView.measure(MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(mConfig.taskBarHeight, MeasureSpec.EXACTLY));
        mActionButtonView.measure(
                MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(heightWithoutPadding, MeasureSpec.AT_MOST));
        // Measure the thumbnail to be square
        mThumbnailView.measure(
                MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
        invalidateOutline();
    }

    /** Synchronizes this view's properties with the task's transform */
    void updateViewPropertiesToTaskTransform(TaskViewTransform toTransform, int duration) {
        updateViewPropertiesToTaskTransform(toTransform, duration, null);
    }

    void updateViewPropertiesToTaskTransform(TaskViewTransform toTransform, int duration,
                                             ValueAnimator.AnimatorUpdateListener updateCallback) {
        // Apply the transform
        toTransform.applyToTaskView(this, duration, mConfig.fastOutSlowInInterpolator, false,
                !mConfig.fakeShadows, updateCallback);

        // Update the task progress
        Utilities.cancelAnimationWithoutCallbacks(mTaskProgressAnimator);
        if (duration <= 0) {
            setTaskProgress(toTransform.p);
        } else {
            mTaskProgressAnimator = ObjectAnimator.ofFloat(this, "taskProgress", toTransform.p);
            mTaskProgressAnimator.setDuration(duration);
            mTaskProgressAnimator.addUpdateListener(mUpdateDimListener);
            mTaskProgressAnimator.start();
        }
    }

    /** Resets this view's properties */
    void resetViewProperties() {
        setDim(0);
        setLayerType(View.LAYER_TYPE_NONE, null);
        TaskViewTransform.reset(this);
        if (mActionButtonView != null) {
            mActionButtonView.setScaleX(1f);
            mActionButtonView.setScaleY(1f);
            mActionButtonView.setAlpha(1f);
            mActionButtonView.setTranslationZ(mActionButtonTranslationZ);
        }
    }

    /**
     * When we are un/filtering, this method will set up the transform that we are animating to,
     * in order to hide the task.
     */
    void prepareTaskTransformForFilterTaskHidden(TaskViewTransform toTransform) {
        // Fade the view out and slide it away
        toTransform.alpha = 0f;
        toTransform.translationY += 200;
        toTransform.translationZ = 0;
    }

    /**
     * When we are un/filtering, this method will setup the transform that we are animating from,
     * in order to show the task.
     */
    void prepareTaskTransformForFilterTaskVisible(TaskViewTransform fromTransform) {
        // Fade the view in
        fromTransform.alpha = 0f;
    }

    /** Prepares this task view for the enter-recents animations.  This is called earlier in the
     * first layout because the actual animation into recents may take a long time. */
    void prepareEnterRecentsAnimation(boolean isTaskViewLaunchTargetTask,
                                             boolean occludesLaunchTarget, int offscreenY) {
        int initialDim = getDim();
        if (mConfig.launchedHasConfigurationChanged) {
            // Just load the views as-is
        } else if (mConfig.launchedFromAppWithThumbnail) {
            if (isTaskViewLaunchTargetTask) {
                // Set the dim to 0 so we can animate it in
                initialDim = 0;
                // Hide the action button
                mActionButtonView.setAlpha(0f);
            } else if (occludesLaunchTarget) {
                // Move the task view off screen (below) so we can animate it in
                setTranslationY(offscreenY);
            }

        } else if (mConfig.launchedFromHome) {
            // Move the task view off screen (below) so we can animate it in
            setTranslationY(offscreenY);
            setTranslationZ(0);
            setScaleX(1f);
            setScaleY(1f);
        }
        // Apply the current dim
        setDim(initialDim);
        // Prepare the thumbnail view alpha
        mThumbnailView.prepareEnterRecentsAnimation(isTaskViewLaunchTargetTask);
    }

    /** Animates this task view as it enters recents */
    void startEnterRecentsAnimation(final ViewAnimation.TaskViewEnterContext ctx) {
        final TaskViewTransform transform = ctx.currentTaskTransform;
        int startDelay = 0;

        if (mConfig.launchedFromAppWithThumbnail) {
            if (mTask.isLaunchTarget) {
                // Animate the dim/overlay
                if (Constants.DebugFlags.App.EnableThumbnailAlphaOnFrontmost) {
                    // Animate the thumbnail alpha before the dim animation (to prevent updating the
                    // hardware layer)
                    mThumbnailView.startEnterRecentsAnimation(mConfig.transitionEnterFromAppDelay,
                            new Runnable() {
                                @Override
                                public void run() {
                                    animateDimToProgress(0, mConfig.taskViewEnterFromAppDuration,
                                            ctx.postAnimationTrigger.decrementOnAnimationEnd());
                                }
                            });
                } else {
                    // Immediately start the dim animation
                    animateDimToProgress(mConfig.transitionEnterFromAppDelay,
                            mConfig.taskViewEnterFromAppDuration,
                            ctx.postAnimationTrigger.decrementOnAnimationEnd());
                }
                ctx.postAnimationTrigger.increment();

                // Animate the action button in
                fadeInActionButton(mConfig.transitionEnterFromAppDelay,
                        mConfig.taskViewEnterFromAppDuration);
            } else {
                // Animate the task up if it was occluding the launch target
                if (ctx.currentTaskOccludesLaunchTarget) {
                    setTranslationY(transform.translationY + mConfig.taskViewAffiliateGroupEnterOffsetPx);
                    setAlpha(0f);
                    animate().alpha(1f)
                            .translationY(transform.translationY)
                            .setStartDelay(mConfig.transitionEnterFromAppDelay)
                            .setUpdateListener(null)
                            .setInterpolator(mConfig.fastOutSlowInInterpolator)
                            .setDuration(mConfig.taskViewEnterFromHomeDuration)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    // Decrement the post animation trigger
                                    ctx.postAnimationTrigger.decrement();
                                }
                            })
                            .start();
                    ctx.postAnimationTrigger.increment();
                }
            }
            startDelay = mConfig.transitionEnterFromAppDelay;

        } else if (mConfig.launchedFromHome) {
            // Animate the tasks up
            int frontIndex = (ctx.currentStackViewCount - ctx.currentStackViewIndex - 1);
            int delay = mConfig.transitionEnterFromHomeDelay +
                    frontIndex * mConfig.taskViewEnterFromHomeStaggerDelay;

            setScaleX(transform.scale);
            setScaleY(transform.scale);
            if (!mConfig.fakeShadows) {
                animate().translationZ(transform.translationZ);
            }
            animate()
                    .translationY(transform.translationY)
                    .setStartDelay(delay)
                    .setUpdateListener(ctx.updateListener)
                    .setInterpolator(mConfig.quintOutInterpolator)
                    .setDuration(mConfig.taskViewEnterFromHomeDuration +
                            frontIndex * mConfig.taskViewEnterFromHomeStaggerDelay)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            // Decrement the post animation trigger
                            ctx.postAnimationTrigger.decrement();
                        }
                    })
                    .start();
            ctx.postAnimationTrigger.increment();
            startDelay = delay;
        }

        // Enable the focus animations from this point onwards so that they aren't affected by the
        // window transitions
        postDelayed(new Runnable() {
            @Override
            public void run() {
                enableFocusAnimations();
            }
        }, startDelay);
    }

    public void fadeInActionButton(int delay, int duration) {
        // Hide the action button
        mActionButtonView.setAlpha(0f);

        // Animate the action button in
        mActionButtonView.animate().alpha(1f)
                .setStartDelay(delay)
                .setDuration(duration)
                .setInterpolator(PhoneStatusBar.ALPHA_IN)
                .withLayer()
                .start();
    }

    /** Animates this task view as it leaves recents by pressing home. */
    void startExitToHomeAnimation(ViewAnimation.TaskViewExitContext ctx) {
        animate()
                .translationY(ctx.offscreenTranslationY)
                .setStartDelay(0)
                .setUpdateListener(null)
                .setInterpolator(mConfig.fastOutLinearInInterpolator)
                .setDuration(mConfig.taskViewExitToHomeDuration)
                .withEndAction(ctx.postAnimationTrigger.decrementAsRunnable())
                .start();
        ctx.postAnimationTrigger.increment();
    }

    /** Animates this task view as it exits recents */
    void startLaunchTaskAnimation(final Runnable postAnimRunnable, boolean isLaunchingTask,
            boolean occludesLaunchTarget, boolean lockToTask) {
        if (isLaunchingTask) {
            // Animate the thumbnail alpha back into full opacity for the window animation out
            mThumbnailView.startLaunchTaskAnimation(postAnimRunnable);

            // Animate the dim
            if (mDimAlpha > 0) {
                ObjectAnimator anim = ObjectAnimator.ofInt(this, "dim", 0);
                anim.setDuration(mConfig.taskViewExitToAppDuration);
                anim.setInterpolator(mConfig.fastOutLinearInInterpolator);
                anim.start();
            }

            // Animate the action button away
            if (!lockToTask) {
                float toScale = 0.9f;
                mActionButtonView.animate()
                        .scaleX(toScale)
                        .scaleY(toScale);
            }
            mActionButtonView.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setDuration(mConfig.taskViewExitToAppDuration)
                    .setInterpolator(mConfig.fastOutLinearInInterpolator)
                    .withLayer()
                    .start();
        } else {
            // Hide the dismiss button
            mHeaderView.startLaunchTaskDismissAnimation();
            // If this is another view in the task grouping and is in front of the launch task,
            // animate it away first
            if (occludesLaunchTarget) {
                animate().alpha(0f)
                    .translationY(getTranslationY() + mConfig.taskViewAffiliateGroupEnterOffsetPx)
                    .setStartDelay(0)
                    .setUpdateListener(null)
                    .setInterpolator(mConfig.fastOutLinearInInterpolator)
                    .setDuration(mConfig.taskViewExitToAppDuration)
                    .start();
            }
        }
    }

    /** Animates the deletion of this task view */
    void startDeleteTaskAnimation(final Runnable r) {
        // Disabling clipping with the stack while the view is animating away
        setClipViewInStack(false);

        animate().translationX(mConfig.taskViewRemoveAnimTranslationXPx)
            .alpha(0f)
            .setStartDelay(0)
            .setUpdateListener(null)
            .setInterpolator(mConfig.fastOutSlowInInterpolator)
            .setDuration(mConfig.taskViewRemoveAnimDuration)
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    // We just throw this into a runnable because starting a view property
                    // animation using layers can cause inconsisten results if we try and
                    // update the layers while the animation is running.  In some cases,
                    // the runnabled passed in may start an animation which also uses layers
                    // so we defer all this by posting this.
                    r.run();

                    // Re-enable clipping with the stack (we will reuse this view)
                    setClipViewInStack(true);
                }
            })
            .start();
    }

    /** Animates this task view if the user does not interact with the stack after a certain time. */
    void startNoUserInteractionAnimation() {
        mHeaderView.startNoUserInteractionAnimation();
    }

    /** Mark this task view that the user does has not interacted with the stack after a certain time. */
    void setNoUserInteractionState() {
        mHeaderView.setNoUserInteractionState();
    }

    /** Resets the state tracking that the user has not interacted with the stack after a certain time. */
    void resetNoUserInteractionState() {
        mHeaderView.resetNoUserInteractionState();
    }

    /** Dismisses this task. */
    void dismissTask() {
        // Animate out the view and call the callback
        final TaskView tv = this;
        startDeleteTaskAnimation(new Runnable() {
            @Override
            public void run() {
                if (mCb != null) {
                    mCb.onTaskViewDismissed(tv);
                }
            }
        });
    }

    /**
     * Returns whether this view should be clipped, or any views below should clip against this
     * view.
     */
    boolean shouldClipViewInStack() {
        return mClipViewInStack && (getVisibility() == View.VISIBLE);
    }

    /** Sets whether this view should be clipped, or clipped against. */
    void setClipViewInStack(boolean clip) {
        if (clip != mClipViewInStack) {
            mClipViewInStack = clip;
            if (mCb != null) {
                mCb.onTaskViewClipStateChanged(this);
            }
        }
    }

    /** Sets the current task progress. */
    public void setTaskProgress(float p) {
        mTaskProgress = p;
        mViewBounds.setAlpha(p);
        updateDimFromTaskProgress();
    }

    /** Returns the current task progress. */
    public float getTaskProgress() {
        return mTaskProgress;
    }

    /** Returns the current dim. */
    public void setDim(int dim) {
        mDimAlpha = dim;
        if (mConfig.useHardwareLayers) {
            // Defer setting hardware layers if we have not yet measured, or there is no dim to draw
            if (getMeasuredWidth() > 0 && getMeasuredHeight() > 0) {
                mDimColorFilter.setColor(Color.argb(mDimAlpha, 0, 0, 0));
                mDimLayerPaint.setColorFilter(mDimColorFilter);
                mContent.setLayerType(LAYER_TYPE_HARDWARE, mDimLayerPaint);
            }
        } else {
            float dimAlpha = mDimAlpha / 255.0f;
            if (mThumbnailView != null) {
                mThumbnailView.setDimAlpha(dimAlpha);
            }
            if (mHeaderView != null) {
                mHeaderView.setDimAlpha(dim);
            }
        }
    }

    /** Returns the current dim. */
    public int getDim() {
        return mDimAlpha;
    }

    /** Animates the dim to the task progress. */
    void animateDimToProgress(int delay, int duration, Animator.AnimatorListener postAnimRunnable) {
        // Animate the dim into view as well
        int toDim = getDimFromTaskProgress();
        if (toDim != getDim()) {
            ObjectAnimator anim = ObjectAnimator.ofInt(TaskView.this, "dim", toDim);
            anim.setStartDelay(delay);
            anim.setDuration(duration);
            if (postAnimRunnable != null) {
                anim.addListener(postAnimRunnable);
            }
            anim.start();
        }
    }

    /** Compute the dim as a function of the scale of this view. */
    int getDimFromTaskProgress() {
        float dim = mMaxDimScale * mDimInterpolator.getInterpolation(1f - mTaskProgress);
        return (int) (dim * 255);
    }

    /** Update the dim as a function of the scale of this view. */
    void updateDimFromTaskProgress() {
        setDim(getDimFromTaskProgress());
    }

    /**** View focus state ****/

    /**
     * Sets the focused task explicitly. We need a separate flag because requestFocus() won't happen
     * if the view is not currently visible, or we are in touch state (where we still want to keep
     * track of focus).
     */
    public void setFocusedTask(boolean animateFocusedState) {
        mIsFocused = true;
        if (mFocusAnimationsEnabled) {
            // Focus the header bar
            mHeaderView.onTaskViewFocusChanged(true, animateFocusedState);
        }
        // Update the thumbnail alpha with the focus
        mThumbnailView.onFocusChanged(true);
        // Call the callback
        if (mCb != null) {
            mCb.onTaskViewFocusChanged(this, true);
        }
        // Workaround, we don't always want it focusable in touch mode, but we want the first task
        // to be focused after the enter-recents animation, which can be triggered from either touch
        // or keyboard
        setFocusableInTouchMode(true);
        requestFocus();
        setFocusableInTouchMode(false);
        invalidate();
    }

    /**
     * Unsets the focused task explicitly.
     */
    void unsetFocusedTask() {
        mIsFocused = false;
        if (mFocusAnimationsEnabled) {
            // Un-focus the header bar
            mHeaderView.onTaskViewFocusChanged(false, true);
        }

        // Update the thumbnail alpha with the focus
        mThumbnailView.onFocusChanged(false);
        // Call the callback
        if (mCb != null) {
            mCb.onTaskViewFocusChanged(this, false);
        }
        invalidate();
    }

    /**
     * Updates the explicitly focused state when the view focus changes.
     */
    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) {
            unsetFocusedTask();
        }
    }

    /**
     * Returns whether we have explicitly been focused.
     */
    public boolean isFocusedTask() {
        return mIsFocused || isFocused();
    }

    /** Enables all focus animations. */
    void enableFocusAnimations() {
        boolean wasFocusAnimationsEnabled = mFocusAnimationsEnabled;
        mFocusAnimationsEnabled = true;
        if (mIsFocused && !wasFocusAnimationsEnabled) {
            // Re-notify the header if we were focused and animations were not previously enabled
            mHeaderView.onTaskViewFocusChanged(true, true);
        }
    }

    /**** TaskCallbacks Implementation ****/

    /** Binds this task view to the task */
    public void onTaskBound(Task t) {
        mTask = t;
        mTask.setCallbacks(this);

        // Hide the action button if lock to app is disabled for this view
        int lockButtonVisibility = (!t.lockToTaskEnabled || !t.lockToThisTask) ? GONE : VISIBLE;
        if (mActionButtonView.getVisibility() != lockButtonVisibility) {
            mActionButtonView.setVisibility(lockButtonVisibility);
            requestLayout();
        }
    }

    @Override
    public void onTaskDataLoaded() {
        if (mThumbnailView != null && mHeaderView != null) {
            // Bind each of the views to the new task data
            mThumbnailView.rebindToTask(mTask);
            mHeaderView.rebindToTask(mTask);
            // Rebind any listeners
            mHeaderView.mApplicationIcon.setOnClickListener(this);
            mHeaderView.mDismissButton.setOnClickListener(this);
            mActionButtonView.setOnClickListener(this);
            if (Constants.DebugFlags.App.EnableDevAppInfoOnLongPress) {
                if (mConfig.developerOptionsEnabled) {
                    mHeaderView.mApplicationIcon.setOnLongClickListener(this);
                }
            }
        }
        mTaskDataLoaded = true;
    }

    @Override
    public void onTaskDataUnloaded() {
        if (mThumbnailView != null && mHeaderView != null) {
            // Unbind each of the views from the task data and remove the task callback
            mTask.setCallbacks(null);
            mThumbnailView.unbindFromTask();
            mHeaderView.unbindFromTask();
            // Unbind any listeners
            mHeaderView.mApplicationIcon.setOnClickListener(null);
            mHeaderView.mDismissButton.setOnClickListener(null);
            mActionButtonView.setOnClickListener(null);
            if (Constants.DebugFlags.App.EnableDevAppInfoOnLongPress) {
                mHeaderView.mApplicationIcon.setOnLongClickListener(null);
            }
        }
        mTaskDataLoaded = false;
    }

    /** Enables/disables handling touch on this task view. */
    void setTouchEnabled(boolean enabled) {
        setOnClickListener(enabled ? this : null);
    }

    /**** View.OnClickListener Implementation ****/

    @Override
     public void onClick(final View v) {
        final TaskView tv = this;
        final boolean delayViewClick = (v != this) && (v != mActionButtonView);
        if (delayViewClick) {
            // We purposely post the handler delayed to allow for the touch feedback to draw
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (Constants.DebugFlags.App.EnableTaskFiltering && v == mHeaderView.mApplicationIcon) {
                        if (mCb != null) {
                            mCb.onTaskViewAppIconClicked(tv);
                        }
                    } else if (v == mHeaderView.mDismissButton) {
                        dismissTask();
                    }
                }
            }, 125);
        } else {
            if (v == mActionButtonView) {
                // Reset the translation of the action button before we animate it out
                mActionButtonView.setTranslationZ(0f);
            }
            if (mCb != null) {
                mCb.onTaskViewClicked(tv, tv.getTask(), (v == mActionButtonView));
            }
        }
    }

    /**** View.OnLongClickListener Implementation ****/

    @Override
    public boolean onLongClick(View v) {
        if (v == mHeaderView.mApplicationIcon) {
            if (mCb != null) {
                mCb.onTaskViewAppInfoClicked(this);
                return true;
            }
        }
        return false;
    }
}
