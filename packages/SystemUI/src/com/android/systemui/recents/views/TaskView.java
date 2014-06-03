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

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.Console;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.model.Task;


/* A task view */
public class TaskView extends FrameLayout implements Task.TaskCallbacks, View.OnClickListener,
        View.OnLongClickListener {
    /** The TaskView callbacks */
    interface TaskViewCallbacks {
        public void onTaskIconClicked(TaskView tv);
        public void onTaskAppInfoClicked(TaskView tv);
        public void onTaskFocused(TaskView tv);
        public void onTaskDismissed(TaskView tv);
    }

    RecentsConfiguration mConfig;

    int mDim;
    int mMaxDim;
    TimeInterpolator mDimInterpolator = new AccelerateInterpolator();

    Task mTask;
    boolean mTaskDataLoaded;
    boolean mIsFocused;
    boolean mClipViewInStack;
    Point mLastTouchDown = new Point();
    Path mRoundedRectClipPath = new Path();
    Rect mTmpRect = new Rect();

    TaskThumbnailView mThumbnailView;
    TaskBarView mBarView;
    TaskViewCallbacks mCb;

    // Optimizations
    ValueAnimator.AnimatorUpdateListener mUpdateDimListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    updateDimOverlayFromScale();
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
        setWillNotDraw(false);
    }

    @Override
    protected void onFinishInflate() {
        mMaxDim = mConfig.taskStackMaxDim;

        // By default, all views are clipped to other views in their stack
        mClipViewInStack = true;

        // Bind the views
        mThumbnailView = (TaskThumbnailView) findViewById(R.id.task_view_thumbnail);
        mBarView = (TaskBarView) findViewById(R.id.task_view_bar);

        if (mTaskDataLoaded) {
            onTaskDataLoaded(false);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Update the rounded rect clip path
        float radius = mConfig.taskViewRoundedCornerRadiusPx;
        mRoundedRectClipPath.reset();
        mRoundedRectClipPath.addRoundRect(new RectF(0, 0, getMeasuredWidth(), getMeasuredHeight()),
                radius, radius, Path.Direction.CW);

        // Update the outline
        Outline o = new Outline();
        o.setRoundRect(0, 0, getMeasuredWidth(), getMeasuredHeight() -
                mConfig.taskViewShadowOutlineBottomInsetPx, radius);
        setOutline(o);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                mLastTouchDown.set((int) ev.getX(), (int) ev.getY());
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    /** Set callback */
    void setCallbacks(TaskViewCallbacks cb) {
        mCb = cb;
    }

    /** Gets the task */
    Task getTask() {
        return mTask;
    }

    /** Synchronizes this view's properties with the task's transform */
    void updateViewPropertiesToTaskTransform(TaskViewTransform animateFromTransform,
                                             TaskViewTransform toTransform, int duration) {
        if (Console.Enabled) {
            Console.log(Constants.Log.UI.Draw, "[TaskView|updateViewPropertiesToTaskTransform]",
                    "duration: " + duration, Console.AnsiPurple);
        }

        // Update the bar view
        mBarView.updateViewPropertiesToTaskTransform(animateFromTransform, toTransform, duration);

        // Update this task view
        if (duration > 0) {
            if (animateFromTransform != null) {
                setTranslationY(animateFromTransform.translationY);
                if (Constants.DebugFlags.App.EnableShadows) {
                    setTranslationZ(animateFromTransform.translationZ);
                }
                setScaleX(animateFromTransform.scale);
                setScaleY(animateFromTransform.scale);
                setAlpha(animateFromTransform.alpha);
            }
            if (Constants.DebugFlags.App.EnableShadows) {
                animate().translationZ(toTransform.translationZ);
            }
            animate().translationY(toTransform.translationY)
                    .scaleX(toTransform.scale)
                    .scaleY(toTransform.scale)
                    .alpha(toTransform.alpha)
                    .setStartDelay(0)
                    .setDuration(duration)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .setUpdateListener(mUpdateDimListener)
                    .start();
        } else {
            setTranslationY(toTransform.translationY);
            if (Constants.DebugFlags.App.EnableShadows) {
                setTranslationZ(toTransform.translationZ);
            }
            setScaleX(toTransform.scale);
            setScaleY(toTransform.scale);
            setAlpha(toTransform.alpha);
        }
        updateDimOverlayFromScale();
        invalidate();
    }

    /** Resets this view's properties */
    void resetViewProperties() {
        setTranslationX(0f);
        setTranslationY(0f);
        if (Constants.DebugFlags.App.EnableShadows) {
            setTranslationZ(0f);
        }
        setScaleX(1f);
        setScaleY(1f);
        setAlpha(1f);
        mDim = 0;
        invalidate();
    }

    /**
     * When we are un/filtering, this method will set up the transform that we are animating to,
     * in order to hide the task.
     */
    void prepareTaskTransformForFilterTaskHidden(TaskViewTransform toTransform) {
        // Fade the view out and slide it away
        toTransform.alpha = 0f;
        toTransform.translationY += 200;
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
    public void prepareAnimateEnterRecents(boolean isTaskViewFrontMost, int offsetY, int offscreenY,
                                           Rect taskRect) {
        if (mConfig.launchedFromAppWithScreenshot) {
            if (isTaskViewFrontMost) {
                // Hide the task view as we are going to animate the full screenshot into view
                // and then replace it with this view once we are done
                setVisibility(View.INVISIBLE);
                // Also hide the front most task bar view so we can animate it in
                mBarView.prepareAnimateEnterRecents();
            } else {
                // Top align the task views
                setTranslationY(offsetY);
                setScaleX(1f);
                setScaleY(1f);
            }

        } else if (mConfig.launchedFromAppWithThumbnail) {
            if (isTaskViewFrontMost) {
                // Hide the front most task bar view so we can animate it in
                mBarView.prepareAnimateEnterRecents();
            }

        } else if (mConfig.launchedFromHome) {
            // Move the task view off screen (below) so we can animate it in
            setTranslationY(offscreenY);
            setScaleX(1f);
            setScaleY(1f);
        }
    }

    /** Animates this task view as it enters recents */
    public void animateOnEnterRecents(ViewAnimation.TaskViewEnterContext ctx) {
        TaskViewTransform transform = ctx.transform;

        if (mConfig.launchedFromAppWithScreenshot) {
            if (ctx.isFrontMost) {
                // Animate the full screenshot down first, before swapping with this task view
                ctx.fullScreenshot.animateOnEnterRecents(ctx, new Runnable() {
                    @Override
                    public void run() {
                        // Animate the task bar of the first task view
                        mBarView.animateOnEnterRecents(0);
                        setVisibility(View.VISIBLE);
                    }
                });
            } else {
                // Animate the tasks down behind the full screenshot
                animate()
                        .scaleX(transform.scale)
                        .scaleY(transform.scale)
                        .translationY(transform.translationY)
                        .setStartDelay(0)
                        .setInterpolator(mConfig.linearOutSlowInInterpolator)
                        .setDuration(475)
                        .withLayer()
                        .start();
            }

        } else if (mConfig.launchedFromAppWithThumbnail) {
            if (ctx.isFrontMost) {
                // Animate the task bar of the first task view
                mBarView.animateOnEnterRecents(-1);
            }

        } else if (mConfig.launchedFromHome) {
            // Animate the tasks up
            int frontIndex = (ctx.stackViewCount - ctx.stackViewIndex - 1);
            int delay = mConfig.taskBarEnterAnimDelay +
                    frontIndex * mConfig.taskViewEnterFromHomeDelay;
            animate()
                    .scaleX(transform.scale)
                    .scaleY(transform.scale)
                    .translationY(transform.translationY)
                    .setStartDelay(delay)
                    .setInterpolator(mConfig.quintOutInterpolator)
                    .setDuration(mConfig.taskViewEnterFromHomeDuration)
                    .withLayer()
                    .start();
        }
    }

    /** Animates this task view as it leaves recents */
    public void animateOnExitRecents(ViewAnimation.TaskViewExitContext ctx) {
        animate()
                .translationY(ctx.offscreenTranslationY)
                .setStartDelay(0)
                .setInterpolator(mConfig.fastOutSlowInInterpolator)
                .setDuration(mConfig.taskViewEnterFromHomeDuration)
                .withLayer()
                .withEndAction(ctx.postAnimationTrigger.decrementAsRunnable())
                .start();
        ctx.postAnimationTrigger.increment();
    }

    /** Animates this task view as it exits recents */
    public void animateOnLaunchingTask(final Runnable r) {
        mBarView.animateOnLaunchingTask(r);
    }

    /** Animates the deletion of this task view */
    public void animateRemoval(final Runnable r) {
        // Disabling clipping with the stack while the view is animating away
        setClipViewInStack(false);

        animate().translationX(mConfig.taskViewRemoveAnimTranslationXPx)
            .alpha(0f)
            .setStartDelay(0)
            .setInterpolator(mConfig.fastOutSlowInInterpolator)
            .setDuration(mConfig.taskViewRemoveAnimDuration)
            .withLayer()
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

    /** Returns the rect we want to clip (it may not be the full rect) */
    Rect getClippingRect(Rect outRect) {
        getHitRect(outRect);
        // XXX: We should get the hit rect of the thumbnail view and intersect, but this is faster
        outRect.right = outRect.left + mThumbnailView.getRight();
        outRect.bottom = outRect.top + mThumbnailView.getBottom();
        return outRect;
    }

    /** Enable the hw layers on this task view */
    void enableHwLayers() {
        mThumbnailView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mBarView.enableHwLayers();
    }

    /** Disable the hw layers on this task view */
    void disableHwLayers() {
        mThumbnailView.setLayerType(View.LAYER_TYPE_NONE, null);
        mBarView.disableHwLayers();
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
            if (getParent() instanceof View) {
                getHitRect(mTmpRect);
                ((View) getParent()).invalidate(mTmpRect);
            }
        }
    }

    /** Update the dim as a function of the scale of this view. */
    void updateDimOverlayFromScale() {
        float minScale = Constants.Values.TaskStackView.StackPeekMinScale;
        float scaleRange = 1f - minScale;
        float dim = (1f - getScaleX()) / scaleRange;
        dim = mDimInterpolator.getInterpolation(Math.min(dim, 1f));
        mDim = Math.max(0, Math.min(mMaxDim, (int) (dim * 255)));
    }

    @Override
    public void draw(Canvas canvas) {
        // Apply the rounded rect clip path on the whole view
        canvas.clipPath(mRoundedRectClipPath);

        super.draw(canvas);

        // Apply the dim if necessary
        if (mDim > 0) {
            canvas.drawColor(mDim << 24);
        }
    }

    /**
     * Sets the focused task explicitly. We need a separate flag because requestFocus() won't happen
     * if the view is not currently visible, or we are in touch state (where we still want to keep
     * track of focus).
     */
    public void setFocusedTask() {
        mIsFocused = true;
        requestFocus();
        invalidate();
        mCb.onTaskFocused(this);
    }

    /**
     * Updates the explicitly focused state when the view focus changes.
     */
    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) {
            mIsFocused = false;
            invalidate();
        }
    }

    /**
     * Returns whether we have explicitly been focused.
     */
    public boolean isFocusedTask() {
        return mIsFocused || isFocused();
    }

    /**** TaskCallbacks Implementation ****/

    /** Binds this task view to the task */
    public void onTaskBound(Task t) {
        mTask = t;
        mTask.setCallbacks(this);
    }

    @Override
    public void onTaskDataLoaded(boolean reloadingTaskData) {
        if (mThumbnailView != null && mBarView != null) {
            // Bind each of the views to the new task data
            mThumbnailView.rebindToTask(mTask, reloadingTaskData);
            mBarView.rebindToTask(mTask, reloadingTaskData);
            // Rebind any listeners
            mBarView.mApplicationIcon.setOnClickListener(this);
            mBarView.mDismissButton.setOnClickListener(this);
            if (Constants.DebugFlags.App.EnableDevAppInfoOnLongPress) {
                if (mConfig.developerOptionsEnabled) {
                    mBarView.mApplicationIcon.setOnLongClickListener(this);
                }
            }
        }
        mTaskDataLoaded = true;
    }

    @Override
    public void onTaskDataUnloaded() {
        if (mThumbnailView != null && mBarView != null) {
            // Unbind each of the views from the task data and remove the task callback
            mTask.setCallbacks(null);
            mThumbnailView.unbindFromTask();
            mBarView.unbindFromTask();
            // Unbind any listeners
            mBarView.mApplicationIcon.setOnClickListener(null);
            mBarView.mDismissButton.setOnClickListener(null);
            if (Constants.DebugFlags.App.EnableDevAppInfoOnLongPress) {
                mBarView.mApplicationIcon.setOnLongClickListener(null);
            }
        }
        mTaskDataLoaded = false;
    }

    @Override
    public void onClick(View v) {
        if (v == mBarView.mApplicationIcon) {
            mCb.onTaskIconClicked(this);
        } else if (v == mBarView.mDismissButton) {
            // Animate out the view and call the callback
            final TaskView tv = this;
            animateRemoval(new Runnable() {
                @Override
                public void run() {
                    mCb.onTaskDismissed(tv);
                }
            });
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mBarView.mApplicationIcon) {
            mCb.onTaskAppInfoClicked(this);
            return true;
        }
        return false;
    }
}
