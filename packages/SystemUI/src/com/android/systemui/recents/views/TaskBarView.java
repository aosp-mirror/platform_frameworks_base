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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.Utilities;
import com.android.systemui.recents.model.Task;


/* The task bar view */
class TaskBarView extends FrameLayout {

    RecentsConfiguration mConfig;

    Task mTask;

    ImageView mDismissButton;
    ImageView mApplicationIcon;
    TextView mActivityDescription;

    Drawable mLightDismissDrawable;
    Drawable mDarkDismissDrawable;

    static Paint sHighlightPaint;

    public TaskBarView(Context context) {
        this(context, null);
    }

    public TaskBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskBarView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        setWillNotDraw(false);

        // Load the dismiss resources
        Resources res = context.getResources();
        mLightDismissDrawable = res.getDrawable(R.drawable.recents_dismiss_light);
        mDarkDismissDrawable = res.getDrawable(R.drawable.recents_dismiss_dark);

        // Configure the highlight paint
        if (sHighlightPaint == null) {
            sHighlightPaint = new Paint();
            sHighlightPaint.setStyle(Paint.Style.STROKE);
            sHighlightPaint.setStrokeWidth(mConfig.taskViewHighlightPx);
            sHighlightPaint.setColor(mConfig.taskBarViewHighlightColor);
            sHighlightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            sHighlightPaint.setAntiAlias(true);
        }
    }

    @Override
    protected void onFinishInflate() {
        // Initialize the icon and description views
        mApplicationIcon = (ImageView) findViewById(R.id.application_icon);
        mActivityDescription = (TextView) findViewById(R.id.activity_description);
        mDismissButton = (ImageView) findViewById(R.id.dismiss_task);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Draw the highlight at the top edge (but put the bottom edge just out of view)
        float offset = mConfig.taskViewHighlightPx / 2f;
        float radius = mConfig.taskViewRoundedCornerRadiusPx;
        canvas.drawRoundRect(-offset, 0f, (float) getMeasuredWidth() + offset,
                getMeasuredHeight() + radius, radius, radius, sHighlightPaint);
    }

    /** Synchronizes this bar view's properties with the task's transform */
    void updateViewPropertiesToTaskTransform(TaskViewTransform animateFromTransform,
                                             TaskViewTransform toTransform, int duration) {
        if (duration > 0 && (mDismissButton.getVisibility() == View.VISIBLE)) {
            if (animateFromTransform != null) {
                mDismissButton.setAlpha(animateFromTransform.dismissAlpha);
            }
            mDismissButton.animate()
                    .alpha(toTransform.dismissAlpha)
                    .setStartDelay(0)
                    .setDuration(duration)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .withLayer()
                    .start();
        } else {
            mDismissButton.setAlpha(toTransform.dismissAlpha);
        }
    }

    /** Binds the bar view to the task */
    void rebindToTask(Task t, boolean animate) {
        mTask = t;
        // If an activity icon is defined, then we use that as the primary icon to show in the bar,
        // otherwise, we fall back to the application icon
        if (t.activityIcon != null) {
            mApplicationIcon.setImageDrawable(t.activityIcon);
        } else if (t.applicationIcon != null) {
            mApplicationIcon.setImageDrawable(t.applicationIcon);
        }
        mActivityDescription.setText(t.activityLabel);
        // Try and apply the system ui tint
        int tint = t.colorPrimary;
        if (Constants.DebugFlags.App.EnableTaskBarThemeColors && tint != 0) {
            setBackgroundColor(tint);
            mActivityDescription.setTextColor(Utilities.getIdealColorForBackgroundColor(tint,
                    mConfig.taskBarViewLightTextColor, mConfig.taskBarViewDarkTextColor));
            mDismissButton.setImageDrawable(Utilities.getIdealResourceForBackgroundColor(tint,
                    mLightDismissDrawable, mDarkDismissDrawable));
        } else {
            setBackgroundColor(mConfig.taskBarViewDefaultBackgroundColor);
            mActivityDescription.setTextColor(mConfig.taskBarViewDefaultTextColor);
        }
    }

    /** Unbinds the bar view from the task */
    void unbindFromTask() {
        mTask = null;
        mApplicationIcon.setImageDrawable(null);
        mActivityDescription.setText("");
    }

    /** Prepares this task view for the enter-recents animations.  This is called earlier in the
     * first layout because the actual animation into recents may take a long time. */
    public void prepareAnimateEnterRecents() {
        setVisibility(View.INVISIBLE);
    }

    /** Animates this task bar as it enters recents */
    public void animateOnEnterRecents(int delay, Runnable postAnimRunnable) {
        // Animate the task bar of the first task view
        setVisibility(View.VISIBLE);
        setTranslationY(-getMeasuredHeight());
        animate()
                .translationY(0)
                .setStartDelay(delay)
                .setInterpolator(mConfig.fastOutSlowInInterpolator)
                .setDuration(mConfig.taskBarEnterAnimDuration)
                .withLayer()
                .withEndAction(postAnimRunnable)
                .start();
    }

    /** Animates this task bar as it exits recents */
    public void animateOnLaunchingTask(Runnable preAnimRunnable, final Runnable postAnimRunnable) {
        // Animate the task bar out of the first task view
        animate()
                .translationY(-getMeasuredHeight())
                .setStartDelay(0)
                .setInterpolator(mConfig.fastOutLinearInInterpolator)
                .setDuration(mConfig.taskBarExitAnimDuration)
                .withLayer()
                .withStartAction(preAnimRunnable)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        post(postAnimRunnable);
                    }
                })
                .start();
    }

    /** Animates this task bar if the user does not interact with the stack after a certain time. */
    public void animateOnNoUserInteraction() {
        mDismissButton.setVisibility(View.VISIBLE);
        mDismissButton.setAlpha(0f);
        mDismissButton.animate()
                .alpha(1f)
                .setStartDelay(0)
                .setInterpolator(mConfig.fastOutLinearInInterpolator)
                .setDuration(mConfig.taskBarEnterAnimDuration)
                .withLayer()
                .start();
    }

    /** Mark this task view that the user does has not interacted with the stack after a certain time. */
    public void setOnNoUserInteraction() {
        if (mDismissButton.getVisibility() != View.VISIBLE) {
            mDismissButton.animate().cancel();
            mDismissButton.setVisibility(View.VISIBLE);
            mDismissButton.setAlpha(1f);
        }
    }

    /** Enable the hw layers on this task view */
    void enableHwLayers() {
        mDismissButton.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /** Disable the hw layers on this task view */
    void disableHwLayers() {
        mDismissButton.setLayerType(View.LAYER_TYPE_NONE, null);
    }
}
