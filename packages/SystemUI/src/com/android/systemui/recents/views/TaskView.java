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
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.model.Task;


/* A task view */
public class TaskView extends FrameLayout implements View.OnClickListener, Task.TaskCallbacks {
    /** The TaskView callbacks */
    interface TaskViewCallbacks {
        public void onTaskIconClicked(TaskView tv);
        // public void onTaskViewReboundToTask(TaskView tv, Task t);
    }

    Task mTask;
    boolean mTaskDataLoaded;

    TaskThumbnailView mThumbnailView;
    TaskBarView mBarView;
    TaskViewCallbacks mCb;

    Path mRoundedRectClipPath = new Path();


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
        float radius = config.pxFromDp(Constants.Values.TaskView.RoundedCornerRadiusDps);
        mRoundedRectClipPath.reset();
        mRoundedRectClipPath.addRoundRect(new RectF(0, 0, getMeasuredWidth(), getMeasuredHeight()),
                radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (Constants.Values.TaskView.UseRoundedCorners) {
            canvas.clipPath(mRoundedRectClipPath);
        }

        super.onDraw(canvas);
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
    void updateViewPropertiesFromTask(TaskViewTransform animateFromTransform,
                                      TaskViewTransform transform, int duration) {
        if (duration > 0) {
            if (animateFromTransform != null) {
                setTranslationY(animateFromTransform.translationY);
                setScaleX(animateFromTransform.scale);
                setScaleY(animateFromTransform.scale);
            }
            animate().translationY(transform.translationY)
                    .scaleX(transform.scale)
                    .scaleY(transform.scale)
                    .setDuration(duration)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        } else {
            setTranslationY(transform.translationY);
            setScaleX(transform.scale);
            setScaleY(transform.scale);
        }
    }

    /** Resets this view's properties */
    void resetViewProperties() {
        setTranslationX(0f);
        setTranslationY(0f);
        setScaleX(1f);
        setScaleY(1f);
        setAlpha(1f);
    }

    /** Animates this task view as it enters recents */
    public void animateOnEnterRecents() {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        int translate = config.pxFromDp(10);
        mBarView.setScaleX(1.25f);
        mBarView.setScaleY(1.25f);
        mBarView.setAlpha(0f);
        mBarView.setTranslationX(translate / 2);
        mBarView.setTranslationY(-translate);
        mBarView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0)
                .translationY(0)
                .setStartDelay(235)
                .setDuration(Constants.Values.TaskView.Animation.TaskIconOnEnterDuration)
                .withLayer()
                .start();
    }

    /** Animates this task view as it exits recents */
    public void animateOnLeavingRecents(final Runnable r) {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        int translate = config.pxFromDp(10);
        mBarView.animate()
            .alpha(0f)
            .scaleX(1.1f)
            .scaleY(1.1f)
            .translationX(translate / 2)
            .translationY(-translate)
            .setStartDelay(0)
            .setDuration(Constants.Values.TaskView.Animation.TaskIconOnLeavingDuration)
            .setInterpolator(new DecelerateInterpolator())
            .withLayer()
            .withEndAction(r)
            .start();
    }

    /** Returns the rect we want to clip (it may not be the full rect) */
    Rect getClippingRect(Rect outRect, boolean accountForRoundedRects) {
        getHitRect(outRect);
        // XXX: We should get the hit rect of the thumbnail view and intersect, but this is faster
        outRect.right = outRect.left + mThumbnailView.getRight();
        outRect.bottom = outRect.top + mThumbnailView.getBottom();
        // We need to shrink the next rect by the rounded corners since those are draw on
        // top of the current view
        if (accountForRoundedRects) {
            RecentsConfiguration config = RecentsConfiguration.getInstance();
            float radius = config.pxFromDp(Constants.Values.TaskView.RoundedCornerRadiusDps);
            outRect.inset((int) radius, (int) radius);
        }
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
        }
        mTaskDataLoaded = false;
    }

    @Override
    public void onClick(View v) {
        mCb.onTaskIconClicked(this);
    }
}