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
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;


/* The task bar view */
class TaskViewHeader extends FrameLayout {

    RecentsConfiguration mConfig;

    ImageView mDismissButton;
    ImageView mApplicationIcon;
    TextView mActivityDescription;

    RippleDrawable mBackground;
    ColorDrawable mBackgroundColor;
    Drawable mLightDismissDrawable;
    Drawable mDarkDismissDrawable;
    AnimatorSet mFocusAnimator;
    ValueAnimator backgroundColorAnimator;

    boolean mIsFullscreen;
    boolean mCurrentPrimaryColorIsDark;
    int mCurrentPrimaryColor;

    static Paint sHighlightPaint;

    public TaskViewHeader(Context context) {
        this(context, null);
    }

    public TaskViewHeader(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskViewHeader(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskViewHeader(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        setWillNotDraw(false);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
            }
        });

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
    public boolean onTouchEvent(MotionEvent event) {
        // We ignore taps on the task bar except on the filter and dismiss buttons
        if (!Constants.DebugFlags.App.EnableTaskBarTouchEvents) return true;

        return super.onTouchEvent(event);
    }

    @Override
    protected void onFinishInflate() {
        // Set the outline provider
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
            }
        });

        // Initialize the icon and description views
        mApplicationIcon = (ImageView) findViewById(R.id.application_icon);
        mActivityDescription = (TextView) findViewById(R.id.activity_description);
        mDismissButton = (ImageView) findViewById(R.id.dismiss_task);

        // Hide the backgrounds if they are ripple drawables
        if (!Constants.DebugFlags.App.EnableTaskFiltering) {
            if (mApplicationIcon.getBackground() instanceof RippleDrawable) {
                mApplicationIcon.setBackground(null);
            }
        }

        mBackgroundColor = new ColorDrawable(0);
        // Copy the ripple drawable since we are going to be manipulating it
        mBackground = (RippleDrawable)
                getResources().getDrawable(R.drawable.recents_task_view_header_bg);
        mBackground = (RippleDrawable) mBackground.mutate().getConstantState().newDrawable();
        mBackground.setColor(ColorStateList.valueOf(0));
        mBackground.setDrawableByLayerId(mBackground.getId(0), mBackgroundColor);
        setBackground(mBackground);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mIsFullscreen) {
            // Draw the highlight at the top edge (but put the bottom edge just out of view)
            float offset = (float) Math.ceil(mConfig.taskViewHighlightPx / 2f);
            float radius = mConfig.taskViewRoundedCornerRadiusPx;
            canvas.drawRoundRect(-offset, 0f, (float) getMeasuredWidth() + offset,
                    getMeasuredHeight() + radius, radius, radius, sHighlightPaint);
        }
    }

    /** Sets whether the current task is full screen or not. */
    void setIsFullscreen(boolean isFullscreen) {
        mIsFullscreen = isFullscreen;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Returns the secondary color for a primary color. */
    int getSecondaryColor(int primaryColor, boolean useLightOverlayColor) {
        int overlayColor = useLightOverlayColor ? Color.WHITE : Color.BLACK;
        return Utilities.getColorWithOverlay(primaryColor, overlayColor, 0.8f);
    }

    /** Binds the bar view to the task */
    void rebindToTask(Task t) {
        // If an activity icon is defined, then we use that as the primary icon to show in the bar,
        // otherwise, we fall back to the application icon
        if (t.activityIcon != null) {
            mApplicationIcon.setImageDrawable(t.activityIcon);
        } else if (t.applicationIcon != null) {
            mApplicationIcon.setImageDrawable(t.applicationIcon);
        }
        mApplicationIcon.setContentDescription(t.activityLabel);
        if (!mActivityDescription.getText().toString().equals(t.activityLabel)) {
            mActivityDescription.setText(t.activityLabel);
        }
        // Try and apply the system ui tint
        int existingBgColor = (getBackground() instanceof ColorDrawable) ?
                ((ColorDrawable) getBackground()).getColor() : 0;
        if (existingBgColor != t.colorPrimary) {
            mBackgroundColor.setColor(t.colorPrimary);
        }
        mCurrentPrimaryColor = t.colorPrimary;
        mCurrentPrimaryColorIsDark = t.useLightOnPrimaryColor;
        mActivityDescription.setTextColor(t.useLightOnPrimaryColor ?
                mConfig.taskBarViewLightTextColor : mConfig.taskBarViewDarkTextColor);
        mDismissButton.setImageDrawable(t.useLightOnPrimaryColor ?
                mLightDismissDrawable : mDarkDismissDrawable);
        mDismissButton.setContentDescription(
                getContext().getString(R.string.accessibility_recents_item_will_be_dismissed,
                        t.activityLabel));
    }

    /** Unbinds the bar view from the task */
    void unbindFromTask() {
        mApplicationIcon.setImageDrawable(null);
    }

    /** Prepares this task view for the enter-recents animations.  This is called earlier in the
     * first layout because the actual animation into recents may take a long time. */
    void prepareEnterRecentsAnimation() {
        setVisibility(View.INVISIBLE);
    }

    /** Animates this task bar as it enters recents */
    void startEnterRecentsAnimation(int delay, Runnable postAnimRunnable) {
        // Animate the task bar of the first task view
        setVisibility(View.VISIBLE);
        setAlpha(0f);
        animate()
                .alpha(1f)
                .setStartDelay(delay)
                .setInterpolator(mConfig.linearOutSlowInInterpolator)
                .setDuration(mConfig.taskBarEnterAnimDuration)
                .withEndAction(postAnimRunnable)
                .withLayer()
                .start();
    }

    /** Animates this task bar as it exits recents */
    void startLaunchTaskAnimation(Runnable preAnimRunnable, final Runnable postAnimRunnable,
            boolean isFocused) {
        if (isFocused) {
            onTaskViewFocusChanged(false);
        }

        // Animate the task bar out of the first task view
        animate()
                .alpha(0f)
                .setStartDelay(0)
                .setInterpolator(mConfig.linearOutSlowInInterpolator)
                .setDuration(mConfig.taskBarExitAnimDuration)
                .withStartAction(preAnimRunnable)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        post(postAnimRunnable);
                    }
                })
                .withLayer()
                .start();
    }

    /** Animates this task bar dismiss button when launching a task. */
    void startLaunchTaskDismissAnimation() {
        if (mDismissButton.getVisibility() == View.VISIBLE) {
            mDismissButton.animate().cancel();
            mDismissButton.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .setDuration(mConfig.taskBarExitAnimDuration)
                    .withLayer()
                    .start();
        }
    }

    /** Animates this task bar if the user does not interact with the stack after a certain time. */
    void startNoUserInteractionAnimation() {
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
    void setNoUserInteractionState() {
        if (mDismissButton.getVisibility() != View.VISIBLE) {
            mDismissButton.animate().cancel();
            mDismissButton.setVisibility(View.VISIBLE);
            mDismissButton.setAlpha(1f);
        }
    }

    /** Notifies the associated TaskView has been focused. */
    void onTaskViewFocusChanged(boolean focused) {
        boolean isRunning = false;
        if (mFocusAnimator != null) {
            isRunning = mFocusAnimator.isRunning();
            mFocusAnimator.removeAllListeners();
            mFocusAnimator.cancel();
        }
        if (focused) {
            int secondaryColor = getSecondaryColor(mCurrentPrimaryColor, mCurrentPrimaryColorIsDark);
            int[][] states = new int[][] {
                    new int[] { android.R.attr.state_enabled },
                    new int[] { android.R.attr.state_pressed }
            };
            int[] newStates = new int[]{
                    android.R.attr.state_enabled,
                    android.R.attr.state_pressed
            };
            int[] colors = new int[] {
                    secondaryColor,
                    secondaryColor
            };
            mBackground.setColor(new ColorStateList(states, colors));
            mBackground.setState(newStates);
            // Pulse the background color
            int currentColor = mBackgroundColor.getColor();
            int lightPrimaryColor = getSecondaryColor(mCurrentPrimaryColor, mCurrentPrimaryColorIsDark);
            ValueAnimator backgroundColor = ValueAnimator.ofObject(new ArgbEvaluator(),
                    lightPrimaryColor, currentColor);
            backgroundColor.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mBackground.setState(new int[]{});
                }
            });
            backgroundColor.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mBackgroundColor.setColor((Integer) animation.getAnimatedValue());
                }
            });
            backgroundColor.setRepeatCount(ValueAnimator.INFINITE);
            backgroundColor.setRepeatMode(ValueAnimator.REVERSE);
            // Pulse the translation
            ObjectAnimator translation = ObjectAnimator.ofFloat(this, "translationZ", 15f);
            translation.setRepeatCount(ValueAnimator.INFINITE);
            translation.setRepeatMode(ValueAnimator.REVERSE);

            mFocusAnimator = new AnimatorSet();
            mFocusAnimator.playTogether(backgroundColor, translation);
            mFocusAnimator.setStartDelay(750);
            mFocusAnimator.setDuration(750);
            mFocusAnimator.start();
        } else {
            if (isRunning) {
                // Restore the background color
                int currentColor = mBackgroundColor.getColor();
                ValueAnimator backgroundColor = ValueAnimator.ofObject(new ArgbEvaluator(),
                        currentColor, mCurrentPrimaryColor);
                backgroundColor.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mBackgroundColor.setColor((Integer) animation.getAnimatedValue());
                    }
                });
                // Restore the translation
                ObjectAnimator translation = ObjectAnimator.ofFloat(this, "translationZ", 0f);

                mFocusAnimator = new AnimatorSet();
                mFocusAnimator.playTogether(backgroundColor, translation);
                mFocusAnimator.setDuration(150);
                mFocusAnimator.start();
            } else {
                mBackground.setState(new int[] {});
                setTranslationZ(0f);
            }
        }
    }
}
