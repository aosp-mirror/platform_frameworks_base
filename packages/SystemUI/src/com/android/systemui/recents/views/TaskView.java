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
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.BakedBezierInterpolator;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.Utilities;
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
    }

    @Override
    protected void onFinishInflate() {
        // Bind the views
        mThumbnailView = (TaskThumbnailView) findViewById(R.id.task_view_thumbnail);
        mBarView = (TaskBarView) findViewById(R.id.task_view_bar);
        mBarView.mApplicationIcon.setOnClickListener(this);
        if (mTaskDataLoaded) {
            onTaskDataLoaded(false);
        }
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
        if (duration > 0) {
            if (animateFromTransform != null) {
                setTranslationY(animateFromTransform.translationY);
                setScaleX(animateFromTransform.scale);
                setScaleY(animateFromTransform.scale);
                setAlpha(animateFromTransform.alpha);
            }
            animate().translationY(toTransform.translationY)
                    .scaleX(toTransform.scale)
                    .scaleY(toTransform.scale)
                    .alpha(toTransform.alpha)
                    .setDuration(duration)
                    .setInterpolator(BakedBezierInterpolator.INSTANCE)
                    .withLayer()
                    .start();
        } else {
            setTranslationY(toTransform.translationY);
            setScaleX(toTransform.scale);
            setScaleY(toTransform.scale);
            setAlpha(toTransform.alpha);
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
                .setInterpolator(BakedBezierInterpolator.INSTANCE)
                .setDuration(config.taskBarEnterAnimDuration)
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
            .setInterpolator(BakedBezierInterpolator.INSTANCE)
            .setDuration(Utilities.calculateTranslationAnimationDuration(translate))
            .withLayer()
            .withEndAction(new Runnable() {
                @Override
                public void run() {
                    post(r);
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