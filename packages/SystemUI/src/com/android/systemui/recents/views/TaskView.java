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
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.systemui.recents.Console;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.model.Task;


/** The task thumbnail view */
class TaskThumbnailView extends ImageView {
    Task mTask;
    int mBarColor;

    Path mRoundedRectClipPath = new Path();

    public TaskThumbnailView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_XY);
    }

    /** Binds the thumbnail view to the task */
    void rebindToTask(Task t, boolean animate) {
        mTask = t;
        if (t.thumbnail != null) {
            // Update the bar color
            if (Constants.Values.TaskView.DrawColoredTaskBars) {
                int[] colors = {0xFFCC0C39, 0xFFE6781E, 0xFFC8CF02, 0xFF1693A7};
                mBarColor = colors[mTask.key.intent.getComponent().getPackageName().length() % colors.length];
            }

            setImageBitmap(t.thumbnail);
            if (animate) {
                setAlpha(0f);
                animate().alpha(1f)
                        .setDuration(Constants.Values.TaskView.Animation.TaskDataUpdatedFadeDuration)
                        .start();
            }
        }
    }

    /** Unbinds the thumbnail view from the task */
    void unbindFromTask() {
        mTask = null;
        setImageDrawable(null);
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

        if (Constants.Values.TaskView.DrawColoredTaskBars) {
            RecentsConfiguration config = RecentsConfiguration.getInstance();
            int taskBarHeight = config.pxFromDp(Constants.Values.TaskView.TaskBarHeightDps);
            // XXX: If we actually use this, this should be pulled out into a TextView that we
            // inflate

            // Draw the task bar
            Rect r = new Rect();
            Paint p = new Paint();
            p.setAntiAlias(true);
            p.setSubpixelText(true);
            p.setColor(mBarColor);
            p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            canvas.drawRect(0, 0, getMeasuredWidth(), taskBarHeight, p);
            p.setColor(0xFFffffff);
            p.setTextSize(68);
            p.getTextBounds("X", 0, 1, r);
            int offset = (int) (taskBarHeight - r.height()) / 2;
            canvas.drawText(mTask.title, offset, offset + r.height(), p);
        }
    }
}

/* The task icon view */
class TaskIconView extends ImageView {
    Task mTask;

    Path mClipPath = new Path();
    float mClipRadius;
    Point mClipOrigin = new Point();
    ObjectAnimator mCircularClipAnimator;

    public TaskIconView(Context context) {
        super(context);
        mClipPath = new Path();
        mClipRadius = 1f;
    }

    /** Binds the icon view to the task */
    void rebindToTask(Task t, boolean animate) {
        mTask = t;
        if (t.icon != null) {
            setImageDrawable(t.icon);
            if (animate) {
                setAlpha(0f);
                animate().alpha(1f)
                        .setDuration(Constants.Values.TaskView.Animation.TaskDataUpdatedFadeDuration)
                        .start();
            }
        }
    }

    /** Unbinds the icon view from the task */
    void unbindFromTask() {
        mTask = null;
        setImageDrawable(null);
    }

    /** Sets the circular clip radius on the icon */
    public void setCircularClipRadius(float r) {
        Console.log(Constants.DebugFlags.UI.Clipping, "[TaskView|setCircularClip]", "" + r);
        mClipRadius = r;
        invalidate();
    }

    /** Gets the circular clip radius on the icon */
    public float getCircularClipRadius() {
        return mClipRadius;
    }

    /** Animates the circular clip radius on the icon */
    void animateCircularClip(boolean brNotTl, float newRadius, int duration, int startDelay,
                             TimeInterpolator interpolator,
                             AnimatorListenerAdapter listener) {
        if (mCircularClipAnimator != null) {
            mCircularClipAnimator.cancel();
            mCircularClipAnimator.removeAllListeners();
        }
        if (brNotTl) {
            mClipOrigin.set(0, 0);
        } else {
            mClipOrigin.set(getMeasuredWidth(), getMeasuredHeight());
        }
        mCircularClipAnimator = ObjectAnimator.ofFloat(this, "circularClipRadius", newRadius);
        mCircularClipAnimator.setStartDelay(startDelay);
        mCircularClipAnimator.setDuration(duration);
        mCircularClipAnimator.setInterpolator(interpolator);
        if (listener != null) {
            mCircularClipAnimator.addListener(listener);
        }
        mCircularClipAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int saveCount = canvas.save(Canvas.CLIP_SAVE_FLAG);
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        int maxSize = (int) Math.ceil(Math.sqrt(width * width + height * height));
        mClipPath.reset();
        mClipPath.addCircle(mClipOrigin.x, mClipOrigin.y, mClipRadius * maxSize, Path.Direction.CW);
        canvas.clipPath(mClipPath);
        super.onDraw(canvas);
        canvas.restoreToCount(saveCount);
    }
}

/* A task view */
public class TaskView extends FrameLayout implements View.OnClickListener, Task.TaskCallbacks {
    /** The TaskView callbacks */
    interface TaskViewCallbacks {
        public void onTaskIconClicked(TaskView tv);
        // public void onTaskViewReboundToTask(TaskView tv, Task t);
    }

    Task mTask;
    TaskThumbnailView mThumbnailView;
    TaskIconView mIconView;
    TaskViewCallbacks mCb;

    public TaskView(Context context) {
        super(context);
        mThumbnailView = new TaskThumbnailView(context);
        mIconView = new TaskIconView(context);
        mIconView.setOnClickListener(this);
        addView(mThumbnailView);
        addView(mIconView);

        RecentsConfiguration config = RecentsConfiguration.getInstance();
        int barHeight = config.pxFromDp(Constants.Values.TaskView.TaskBarHeightDps);
        int iconSize = config.pxFromDp(Constants.Values.TaskView.TaskIconSizeDps);
        int offset = barHeight - (iconSize / 2);

        // XXX: Lets keep the icon in the corner for the time being
        offset = iconSize / 4;

        /*
        ((LayoutParams) mThumbnailView.getLayoutParams()).leftMargin = barHeight / 2;
        ((LayoutParams) mThumbnailView.getLayoutParams()).rightMargin = barHeight / 2;
        ((LayoutParams) mThumbnailView.getLayoutParams()).bottomMargin = barHeight;
        */
        ((LayoutParams) mIconView.getLayoutParams()).gravity = Gravity.END;
        ((LayoutParams) mIconView.getLayoutParams()).width = iconSize;
        ((LayoutParams) mIconView.getLayoutParams()).height = iconSize;
        ((LayoutParams) mIconView.getLayoutParams()).topMargin = offset;
        ((LayoutParams) mIconView.getLayoutParams()).rightMargin = offset;
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
        mIconView.setCircularClipRadius(0f);
        mIconView.animateCircularClip(true, 1f,
            Constants.Values.TaskView.Animation.TaskIconCircularClipInDuration,
            300, new AccelerateInterpolator(), null);
    }

    /** Animates this task view as it exits recents */
    public void animateOnLeavingRecents(final Runnable r) {
        if (Constants.Values.TaskView.AnimateFrontTaskIconOnLeavingUseClip) {
            mIconView.animateCircularClip(false, 0f,
                Constants.Values.TaskView.Animation.TaskIconCircularClipOutDuration, 0,
                new DecelerateInterpolator(),
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        r.run();
                    }
                });
        } else {
            mIconView.animate()
                .alpha(0f)
                .setDuration(Constants.Values.TaskView.Animation.TaskIconCircularClipOutDuration)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(
                    new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            r.run();
                        }
                    })
                .start();
        }
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
    public void onTaskDataLoaded() {
        // Bind each of the views to the new task data
        mThumbnailView.rebindToTask(mTask, false);
        mIconView.rebindToTask(mTask, false);
    }

    @Override
    public void onTaskDataUnloaded() {
        // Unbind each of the views from the task data and remove the task callback
        mTask.setCallbacks(null);
        mThumbnailView.unbindFromTask();
        mIconView.unbindFromTask();
    }

    @Override
    public void onClick(View v) {
        mCb.onTaskIconClicked(this);
    }
}