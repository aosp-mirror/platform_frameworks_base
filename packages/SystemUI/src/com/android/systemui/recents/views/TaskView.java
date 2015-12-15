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
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.ui.DismissTaskViewEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

/* A task view */
public class TaskView extends FrameLayout implements Task.TaskCallbacks,
        View.OnClickListener, View.OnLongClickListener {

    private final static String TAG = "TaskView";
    private final static boolean DEBUG = false;

    /** The TaskView callbacks */
    interface TaskViewCallbacks {
        void onTaskViewClipStateChanged(TaskView tv);
    }

    float mTaskProgress;
    ObjectAnimator mTaskProgressAnimator;
    float mMaxDimScale;
    int mDimAlpha;
    AccelerateInterpolator mDimInterpolator = new AccelerateInterpolator(3f);
    PorterDuffColorFilter mDimColorFilter = new PorterDuffColorFilter(0, PorterDuff.Mode.SRC_ATOP);
    Paint mDimLayerPaint = new Paint();
    float mActionButtonTranslationZ;

    Task mTask;
    boolean mTaskDataLoaded;
    boolean mClipViewInStack;
    AnimateableViewBounds mViewBounds;
    private AnimatorSet mClipAnimation;

    View mContent;
    TaskViewThumbnail mThumbnailView;
    TaskViewHeader mHeaderView;
    View mActionButtonView;
    TaskViewCallbacks mCb;

    Point mDownTouchPos = new Point();

    Interpolator mFastOutSlowInInterpolator;
    Interpolator mFastOutLinearInInterpolator;
    Interpolator mQuintOutInterpolator;

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
        RecentsConfiguration config = Recents.getConfiguration();
        Resources res = context.getResources();
        mMaxDimScale = res.getInteger(R.integer.recents_max_task_stack_view_dim) / 255f;
        mClipViewInStack = true;
        mViewBounds = new AnimateableViewBounds(this, res.getDimensionPixelSize(
                R.dimen.recents_task_view_rounded_corners_radius));
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_linear_in);
        mQuintOutInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.decelerate_quint);
        setTaskProgress(getTaskProgress());
        setDim(getDim());
        if (config.fakeShadows) {
            setBackground(new FakeShadowDrawable(res, config));
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
    public Task getTask() {
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mHeaderView.onTaskViewSizeChanged(w, h);
        mThumbnailView.onTaskViewSizeChanged(w, h);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mDownTouchPos.set((int) (ev.getX() * getScaleX()), (int) (ev.getY() * getScaleY()));
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        int widthWithoutPadding = width - mPaddingLeft - mPaddingRight;
        int heightWithoutPadding = height - mPaddingTop - mPaddingBottom;
        int taskBarHeight = getResources().getDimensionPixelSize(R.dimen.recents_task_bar_height);

        // Measure the content
        mContent.measure(MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightWithoutPadding, MeasureSpec.EXACTLY));

        // Measure the bar view, and action button
        mHeaderView.measure(MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(taskBarHeight, MeasureSpec.EXACTLY));
        mActionButtonView.measure(
                MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(heightWithoutPadding, MeasureSpec.AT_MOST));
        // Measure the thumbnail to be square
        mThumbnailView.measure(
                MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightWithoutPadding, MeasureSpec.EXACTLY));
        mThumbnailView.updateClipToTaskBar(mHeaderView);

        setMeasuredDimension(width, height);
        invalidateOutline();
    }

    /** Synchronizes this view's properties with the task's transform */
    void updateViewPropertiesToTaskTransform(TaskViewTransform toTransform, int clipBottom,
            int duration, Interpolator interpolator,
            ValueAnimator.AnimatorUpdateListener updateCallback) {
        RecentsConfiguration config = Recents.getConfiguration();
        Utilities.cancelAnimationWithoutCallbacks(mClipAnimation);

        // Apply the transform
        toTransform.applyToTaskView(this, duration, interpolator, false,
                !config.fakeShadows, updateCallback);

        // Update the clipping
        if (duration > 0) {
            mClipAnimation = new AnimatorSet();
            mClipAnimation.playTogether(
                    ObjectAnimator.ofInt(mViewBounds, AnimateableViewBounds.CLIP_BOTTOM,
                            mViewBounds.getClipBottom(), clipBottom),
                    ObjectAnimator.ofInt(this, TaskViewTransform.LEFT, getLeft(),
                            (int) toTransform.rect.left),
                    ObjectAnimator.ofInt(this, TaskViewTransform.TOP, getTop(),
                            (int) toTransform.rect.top),
                    ObjectAnimator.ofInt(this, TaskViewTransform.RIGHT, getRight(),
                            (int) toTransform.rect.right),
                    ObjectAnimator.ofInt(this, TaskViewTransform.BOTTOM, getBottom(),
                            (int) toTransform.rect.bottom),
                    ObjectAnimator.ofFloat(mThumbnailView, TaskViewThumbnail.BITMAP_SCALE,
                            mThumbnailView.getBitmapScale(), toTransform.thumbnailScale));
            mClipAnimation.setStartDelay(toTransform.startDelay);
            mClipAnimation.setDuration(duration);
            mClipAnimation.setInterpolator(interpolator);
            mClipAnimation.start();
        } else {
            mViewBounds.setClipBottom(clipBottom, false /* forceUpdate */);
            mThumbnailView.setBitmapScale(toTransform.thumbnailScale);
            setLeftTopRightBottom((int) toTransform.rect.left, (int) toTransform.rect.top,
                    (int) toTransform.rect.right, (int) toTransform.rect.bottom);
        }
        if (!config.useHardwareLayers) {
            mThumbnailView.updateThumbnailVisibility(clipBottom - getPaddingBottom());
        }

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
        setVisibility(View.VISIBLE);
        getViewBounds().reset();
        TaskViewTransform.reset(this);
        if (mActionButtonView != null) {
            mActionButtonView.setScaleX(1f);
            mActionButtonView.setScaleY(1f);
            mActionButtonView.setAlpha(1f);
            mActionButtonView.setTranslationZ(mActionButtonTranslationZ);
        }
    }

    /** Prepares this task view for the enter-recents animations.  This is called earlier in the
     * first layout because the actual animation into recents may take a long time. */
    void prepareEnterRecentsAnimation(boolean isTaskViewLaunchTargetTask, boolean hideTask,
            boolean occludesLaunchTarget, int offscreenY) {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        int initialDim = getDim();
        if (hideTask) {
            setVisibility(View.INVISIBLE);
        } else if (launchState.launchedHasConfigurationChanged) {
            // Just load the views as-is
        } else if (launchState.launchedFromAppWithThumbnail) {
            if (isTaskViewLaunchTargetTask) {
                // Set the dim to 0 so we can animate it in
                initialDim = 0;
                // Hide the action button
                mActionButtonView.setAlpha(0f);
            } else if (occludesLaunchTarget) {
                // Move the task view off screen (below) so we can animate it in
                setTranslationY(offscreenY);
            }

        } else if (launchState.launchedFromHome) {
            // Move the task view off screen (below) so we can animate it in
            setTranslationY(offscreenY);
            setTranslationZ(0);
            setScaleX(1f);
            setScaleY(1f);
        }
        // Apply the current dim
        setDim(initialDim);
    }

    /** Animates this task view as it enters recents */
    void startEnterRecentsAnimation(final ViewAnimation.TaskViewEnterContext ctx) {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        Resources res = mContext.getResources();
        final TaskViewTransform transform = ctx.currentTaskTransform;
        final int taskViewEnterFromAppDuration = res.getInteger(
                R.integer.recents_task_enter_from_app_duration);
        final int taskViewEnterFromHomeDuration = res.getInteger(
                R.integer.recents_task_enter_from_home_duration);
        final int taskViewEnterFromHomeStaggerDelay = res.getInteger(
                R.integer.recents_task_enter_from_home_stagger_delay);
        final int taskViewAffiliateGroupEnterOffset = res.getDimensionPixelSize(
                R.dimen.recents_task_view_affiliate_group_enter_offset);

        if (launchState.launchedFromAppWithThumbnail) {
            if (mTask.isLaunchTarget) {
                // Immediately start the dim animation
                animateDimToProgress(taskViewEnterFromAppDuration,
                        ctx.postAnimationTrigger.decrementOnAnimationEnd());
                ctx.postAnimationTrigger.increment();

                // Animate the action button in
                fadeInActionButton(taskViewEnterFromAppDuration);
            } else {
                // Animate the task up if it was occluding the launch target
                if (ctx.currentTaskOccludesLaunchTarget) {
                    setTranslationY(taskViewAffiliateGroupEnterOffset);
                    setAlpha(0f);
                    animate().alpha(1f)
                            .translationY(0)
                            .setUpdateListener(null)
                            .setListener(new AnimatorListenerAdapter() {
                                private boolean hasEnded;

                                // We use the animation listener instead of withEndAction() to
                                // ensure that onAnimationEnd() is called when the animator is
                                // cancelled
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    if (hasEnded) return;
                                    ctx.postAnimationTrigger.decrement();
                                    hasEnded = true;
                                }
                            })
                            .setInterpolator(mFastOutSlowInInterpolator)
                            .setDuration(taskViewEnterFromHomeDuration)
                            .start();
                    ctx.postAnimationTrigger.increment();
                }
            }

        } else if (launchState.launchedFromHome) {
            // Animate the tasks up
            int frontIndex = (ctx.currentStackViewCount - ctx.currentStackViewIndex - 1);
            int delay = frontIndex * taskViewEnterFromHomeStaggerDelay;

            setScaleX(transform.scale);
            setScaleY(transform.scale);
            if (!config.fakeShadows) {
                animate().translationZ(transform.translationZ);
            }
            animate()
                    .translationY(0)
                    .setStartDelay(delay)
                    .setUpdateListener(ctx.updateListener)
                    .setListener(new AnimatorListenerAdapter() {
                        private boolean hasEnded;

                        // We use the animation listener instead of withEndAction() to ensure that
                        // onAnimationEnd() is called when the animator is cancelled
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (hasEnded) return;
                            ctx.postAnimationTrigger.decrement();
                            hasEnded = true;
                        }
                    })
                    .setInterpolator(mQuintOutInterpolator)
                    .setDuration(taskViewEnterFromHomeDuration +
                            frontIndex * taskViewEnterFromHomeStaggerDelay)
                    .start();
            ctx.postAnimationTrigger.increment();
        }
    }

    public void cancelEnterRecentsAnimation() {
        animate().cancel();
    }

    public void fadeInActionButton(int duration) {
        // Hide the action button
        mActionButtonView.setAlpha(0f);

        // Animate the action button in
        mActionButtonView.animate().alpha(1f)
                .setDuration(duration)
                .setInterpolator(PhoneStatusBar.ALPHA_IN)
                .start();
    }

    /** Animates this task view as it leaves recents by pressing home. */
    void startExitToHomeAnimation(ViewAnimation.TaskViewExitContext ctx) {
        int taskViewExitToHomeDuration = getResources().getInteger(
                R.integer.recents_task_exit_to_home_duration);
        animate()
                .translationY(ctx.offscreenTranslationY)
                .setStartDelay(0)
                .setUpdateListener(null)
                .setListener(null)
                .setInterpolator(mFastOutLinearInInterpolator)
                .setDuration(taskViewExitToHomeDuration)
                .withEndAction(ctx.postAnimationTrigger.decrementAsRunnable())
                .start();
        ctx.postAnimationTrigger.increment();
    }

    /** Animates this task view as it exits recents */
    void startLaunchTaskAnimation(final Runnable postAnimRunnable, boolean isLaunchingTask,
            boolean occludesLaunchTarget, boolean lockToTask) {
        final int taskViewExitToAppDuration = mContext.getResources().getInteger(
                R.integer.recents_task_exit_to_app_duration);
        final int taskViewAffiliateGroupEnterOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_task_view_affiliate_group_enter_offset);

        if (isLaunchingTask) {
            // Animate the dim
            if (mDimAlpha > 0) {
                ObjectAnimator anim = ObjectAnimator.ofInt(this, "dim", 0);
                anim.setDuration(taskViewExitToAppDuration);
                anim.setInterpolator(mFastOutLinearInInterpolator);
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
                    .setDuration(taskViewExitToAppDuration)
                    .setInterpolator(mFastOutLinearInInterpolator)
                    .withEndAction(postAnimRunnable)
                    .start();
        } else {
            // Hide the dismiss button
            mHeaderView.startLaunchTaskDismissAnimation(postAnimRunnable);
            // If this is another view in the task grouping and is in front of the launch task,
            // animate it away first
            if (occludesLaunchTarget) {
                animate().alpha(0f)
                    .translationY(getTranslationY() + taskViewAffiliateGroupEnterOffset)
                    .setStartDelay(0)
                    .setUpdateListener(null)
                    .setListener(null)
                    .setInterpolator(mFastOutLinearInInterpolator)
                    .setDuration(taskViewExitToAppDuration)
                    .start();
            }
        }
    }

    /** Animates the deletion of this task view */
    void startDeleteTaskAnimation(final Runnable r, int delay) {
        int taskViewRemoveAnimDuration = getResources().getInteger(
                R.integer.recents_animate_task_view_remove_duration);
        int taskViewRemoveAnimTranslationXPx = getResources().getDimensionPixelSize(
                R.dimen.recents_task_view_remove_anim_translation_x);

        // Disabling clipping with the stack while the view is animating away
        setClipViewInStack(false);

        animate().translationX(taskViewRemoveAnimTranslationXPx)
            .alpha(0f)
            .setStartDelay(delay)
            .setUpdateListener(null)
            .setListener(null)
            .setInterpolator(mFastOutSlowInInterpolator)
            .setDuration(taskViewRemoveAnimDuration)
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (r != null) {
                        r.run();
                    }

                    // Re-enable clipping with the stack (we will reuse this view)
                    setClipViewInStack(true);
                }
            })
            .start();
    }

    /** Enables/disables handling touch on this task view. */
    void setTouchEnabled(boolean enabled) {
        setOnClickListener(enabled ? this : null);
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
                EventBus.getDefault().send(new DismissTaskViewEvent(mTask, tv));
            }
        }, 0);
    }

    /**
     * Returns whether this view should be clipped, or any views below should clip against this
     * view.
     */
    boolean shouldClipViewInStack() {
        // Never clip for freeform tasks or if invisible
        if (mTask.isFreeformTask() || getVisibility() != View.VISIBLE) {
            return false;
        }
        return mClipViewInStack;
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
        RecentsConfiguration config = Recents.getConfiguration();

        mDimAlpha = dim;
        if (config.useHardwareLayers) {
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
    void animateDimToProgress(int duration, Animator.AnimatorListener postAnimRunnable) {
        // Animate the dim into view as well
        int toDim = getDimFromTaskProgress();
        if (toDim != getDim()) {
            ObjectAnimator anim = ObjectAnimator.ofInt(TaskView.this, "dim", toDim);
            anim.setDuration(duration);
            if (postAnimRunnable != null) {
                anim.addListener(postAnimRunnable);
            }
            anim.start();
        } else {
            postAnimRunnable.onAnimationEnd(null);
        }
    }

    /** Compute the dim as a function of the scale of this view. */
    int getDimFromTaskProgress() {
        float x = mTaskProgress < 0
                ? 1f
                : mDimInterpolator.getInterpolation(1f - mTaskProgress);
        float dim = mMaxDimScale * x;
        return (int) (dim * 255);
    }

    /** Update the dim as a function of the scale of this view. */
    void updateDimFromTaskProgress() {
        setDim(getDimFromTaskProgress());
    }

    /**** View focus state ****/

    /**
     * Explicitly sets the focused state of this task.
     */
    public void setFocusedState(boolean isFocused, boolean animated, boolean requestViewFocus) {
        if (DEBUG) {
            Log.d(TAG, "setFocusedState: " + mTask.activityLabel + " focused: " + isFocused +
                    " animated: " + animated + " requestViewFocus: " + requestViewFocus +
                    " isFocused(): " + isFocused() +
                    " isAccessibilityFocused(): " + isAccessibilityFocused());
        }

        SystemServicesProxy ssp = Recents.getSystemServices();
        if (isFocused) {
            if (requestViewFocus && !isFocused()) {
                requestFocus();
            }
            if (requestViewFocus && !isAccessibilityFocused() && ssp.isTouchExplorationEnabled()) {
                requestAccessibilityFocus();
            }
        } else {
            if (isAccessibilityFocused() && ssp.isTouchExplorationEnabled()) {
                clearAccessibilityFocus();
            }
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
            mActionButtonView.setOnClickListener(this);
            setOnLongClickListener(this);
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
            mActionButtonView.setOnClickListener(null);
        }
        mTaskDataLoaded = false;
    }

    @Override
    public void onTaskStackIdChanged() {
        mHeaderView.rebindToTask(mTask);
    }

    /**** View.OnClickListener Implementation ****/

    @Override
     public void onClick(final View v) {
        boolean screenPinningRequested = false;
        if (v == mActionButtonView) {
            // Reset the translation of the action button before we animate it out
            mActionButtonView.setTranslationZ(0f);
            screenPinningRequested = true;
        }
        EventBus.getDefault().send(new LaunchTaskEvent(this, mTask, null, INVALID_STACK_ID,
                screenPinningRequested));
    }

    /**** View.OnLongClickListener Implementation ****/

    @Override
    public boolean onLongClick(View v) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        // Since we are clipping the view to the bounds, manually do the hit test
        Rect clipBounds = new Rect(mViewBounds.mClipBounds);
        clipBounds.scale(getScaleX());
        boolean inBounds = clipBounds.contains(mDownTouchPos.x, mDownTouchPos.y);
        if (v == this && inBounds && !ssp.hasDockedTask()) {
            // Start listening for drag events
            setClipViewInStack(false);

            // Enlarge the view slightly
            final float finalScale = getScaleX() * 1.05f;
            animate()
                    .scaleX(finalScale)
                    .scaleY(finalScale)
                    .setDuration(175)
                    .setUpdateListener(null)
                    .setListener(null)
                    .setInterpolator(mFastOutSlowInInterpolator)
                    .start();

            mDownTouchPos.x += ((1f - getScaleX()) * getWidth()) / 2;
            mDownTouchPos.y += ((1f - getScaleY()) * getHeight()) / 2;

            EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
            EventBus.getDefault().send(new DragStartEvent(mTask, this, mDownTouchPos));
            return true;
        }
        return false;
    }

    /**** Events ****/

    public final void onBusEvent(DragEndEvent event) {
        if (!(event.dropTarget instanceof TaskStack.DockState)) {
            event.postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    // Animate the drag view back from where it is, to the view location, then after
                    // it returns, update the clip state
                    setClipViewInStack(true);
                }
            });
        }
        EventBus.getDefault().unregister(this);
    }
}
