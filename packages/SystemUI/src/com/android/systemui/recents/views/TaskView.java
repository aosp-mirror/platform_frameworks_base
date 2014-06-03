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
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import com.android.systemui.R;
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

        // public void onTaskViewReboundToTask(TaskView tv, Task t);
    }

    int mDim;
    int mMaxDim;
    TimeInterpolator mDimInterpolator = new AccelerateInterpolator();

    Task mTask;
    boolean mTaskDataLoaded;
    boolean mIsFocused;
    boolean mClipViewInStack;
    Point mLastTouchDown = new Point();
    Path mRoundedRectClipPath = new Path();

    TaskThumbnailView mThumbnailView;
    TaskBarView mBarView;
    TaskViewCallbacks mCb;


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
        setWillNotDraw(false);
    }

    @Override
    protected void onFinishInflate() {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        mMaxDim = config.taskStackMaxDim;

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
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        float radius = config.taskViewRoundedCornerRadiusPx;
        mRoundedRectClipPath.reset();
        mRoundedRectClipPath.addRoundRect(new RectF(0, 0, getMeasuredWidth(), getMeasuredHeight()),
                radius, radius, Path.Direction.CW);

        // Update the outline
        Outline o = new Outline();
        o.setRoundRect(0, 0, getMeasuredWidth(), getMeasuredHeight() -
                config.taskViewShadowOutlineBottomInsetPx, radius);
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
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        int minZ = config.taskViewTranslationZMinPx;
        int incZ = config.taskViewTranslationZIncrementPx;

        // Update the bar view
        mBarView.updateViewPropertiesToTaskTransform(animateFromTransform, toTransform, duration);

        // Update this task view
        if (duration > 0) {
            if (animateFromTransform != null) {
                setTranslationY(animateFromTransform.translationY);
                if (Constants.DebugFlags.App.EnableShadows) {
                    setTranslationZ(Math.max(minZ, minZ + (animateFromTransform.t * incZ)));
                }
                setScaleX(animateFromTransform.scale);
                setScaleY(animateFromTransform.scale);
                setAlpha(animateFromTransform.alpha);
            }
            if (Constants.DebugFlags.App.EnableShadows) {
                animate().translationZ(Math.max(minZ, minZ + (toTransform.t * incZ)));
            }
            animate().translationY(toTransform.translationY)
                    .scaleX(toTransform.scale)
                    .scaleY(toTransform.scale)
                    .alpha(toTransform.alpha)
                    .setDuration(duration)
                    .setInterpolator(config.fastOutSlowInInterpolator)
                    .withLayer()
                    .setUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            updateDimOverlayFromScale();
                        }
                    })
                    .start();
        } else {
            setTranslationY(toTransform.translationY);
            if (Constants.DebugFlags.App.EnableShadows) {
                setTranslationZ(Math.max(minZ, minZ + (toTransform.t * incZ)));
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
    public void prepareAnimateOnEnterRecents() {
        mBarView.setVisibility(View.INVISIBLE);
    }

    /** Animates this task view as it enters recents */
    public void animateOnEnterRecents() {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        mBarView.setVisibility(View.VISIBLE);
        mBarView.setTranslationY(-mBarView.getMeasuredHeight());
        mBarView.animate()
                .translationY(0)
                .setStartDelay(config.taskBarEnterAnimDelay)
                .setInterpolator(config.fastOutSlowInInterpolator)
                .setDuration(config.taskBarEnterAnimDuration)
                .withLayer()
                .start();
    }

    /** Animates this task view as it exits recents */
    public void animateOnLeavingRecents(final Runnable r) {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        mBarView.animate()
            .translationY(-mBarView.getMeasuredHeight())
            .setStartDelay(0)
            .setInterpolator(config.fastOutLinearInInterpolator)
            .setDuration(config.taskBarExitAnimDuration)
            .withLayer()
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    post(r);
                }
            })
            .start();
    }

    /** Animates the deletion of this task view */
    public void animateRemoval(final Runnable r) {
        // Disabling clipping with the stack while the view is animating away
        setClipViewInStack(false);

        RecentsConfiguration config = RecentsConfiguration.getInstance();
        animate().translationX(config.taskViewRemoveAnimTranslationXPx)
            .alpha(0f)
            .setStartDelay(0)
            .setInterpolator(config.fastOutSlowInInterpolator)
            .setDuration(config.taskViewRemoveAnimDuration)
            .withLayer()
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    post(r);

                    // Re-enable clipping with the stack (we will reuse this view)
                    setClipViewInStack(false);
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
    }

    /** Disable the hw layers on this task view */
    void disableHwLayers() {
        mThumbnailView.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    /**
     * Returns whether this view should be clipped, or any views below should clip against this
     * view.
     */
    boolean shouldClipViewInStack() {
        return mClipViewInStack;
    }

    /** Sets whether this view should be clipped, or clipped against. */
    void setClipViewInStack(boolean clip) {
        if (clip != mClipViewInStack) {
            mClipViewInStack = clip;
            if (getParent() instanceof View) {
                Rect r = new Rect();
                getHitRect(r);
                ((View) getParent()).invalidate(r);
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
                RecentsConfiguration config = RecentsConfiguration.getInstance();
                if (config.developerOptionsEnabled) {
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
