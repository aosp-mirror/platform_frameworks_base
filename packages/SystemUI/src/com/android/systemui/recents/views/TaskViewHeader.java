/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.support.v4.graphics.ColorUtils;
import android.os.CountDownTimer;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.android.internal.logging.MetricsLogger;

import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.ui.ShowApplicationInfoEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;

import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

/* The task bar view */
public class TaskViewHeader extends FrameLayout
        implements View.OnClickListener, View.OnLongClickListener {

    private static final float HIGHLIGHT_LIGHTNESS_INCREMENT = 0.125f;
    private static final long FOCUS_INDICATOR_INTERVAL_MS = 30;

    /**
     * A color drawable that draws a slight highlight at the top to help it stand out.
     */
    private class HighlightColorDrawable extends Drawable {

        private Paint mHighlightPaint = new Paint();
        private Paint mBackgroundPaint = new Paint();

        private float[] mTmpHSL = new float[3];

        public HighlightColorDrawable() {
            mBackgroundPaint.setColor(Color.argb(255, 0, 0, 0));
            mBackgroundPaint.setAntiAlias(true);
            mHighlightPaint.setColor(Color.argb(255, 255, 255, 255));
            mHighlightPaint.setAntiAlias(true);
        }

        public void setColorAndDim(int color, float dimAlpha) {
            mBackgroundPaint.setColor(color);

            ColorUtils.colorToHSL(color, mTmpHSL);
            // TODO: Consider using the saturation of the color to adjust the lightness as well
            mTmpHSL[2] = Math.min(1f,
                    mTmpHSL[2] + HIGHLIGHT_LIGHTNESS_INCREMENT * (1.0f - dimAlpha));
            mHighlightPaint.setColor(ColorUtils.HSLToColor(mTmpHSL));

            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            // Do nothing
        }

        @Override
        public void setAlpha(int alpha) {
            // Do nothing
        }

        @Override
        public void draw(Canvas canvas) {
            // Draw the highlight at the top edge (but put the bottom edge just out of view)
            canvas.drawRoundRect(0, 0, mTaskViewRect.width(),
                    2 * Math.max(mHighlightHeight, mCornerRadius),
                    mCornerRadius, mCornerRadius, mHighlightPaint);

            // Draw the background with the rounded corners
            canvas.drawRoundRect(0, mHighlightHeight, mTaskViewRect.width(),
                    getHeight() + mCornerRadius,
                    mCornerRadius, mCornerRadius, mBackgroundPaint);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    Task mTask;

    // Header views
    ImageView mMoveTaskButton;
    ImageView mDismissButton;
    ImageView mIconView;
    TextView mTitleView;
    int mMoveTaskTargetStackId = INVALID_STACK_ID;
    ProgressBar mFocusTimerIndicator;

    // Header drawables
    Rect mTaskViewRect = new Rect();
    int mCornerRadius;
    int mHighlightHeight;
    float mDimAlpha;
    Drawable mLightDismissDrawable;
    Drawable mDarkDismissDrawable;
    Drawable mLightFreeformIcon;
    Drawable mDarkFreeformIcon;
    Drawable mLightFullscreenIcon;
    Drawable mDarkFullscreenIcon;
    int mTaskBarViewLightTextColor;
    int mTaskBarViewDarkTextColor;

    // Header background
    private HighlightColorDrawable mBackground;

    // Header dim, which is only used when task view hardware layers are not used
    private Paint mDimLayerPaint = new Paint();

    Interpolator mFastOutSlowInInterpolator;
    Interpolator mFastOutLinearInInterpolator;

    long mFocusIndicatorProgress;
    private CountDownTimer mFocusTimerCountDown;
    long mFocusTimerDuration;

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
        setWillNotDraw(false);

        // Load the dismiss resources
        Resources res = context.getResources();
        mLightDismissDrawable = context.getDrawable(R.drawable.recents_dismiss_light);
        mDarkDismissDrawable = context.getDrawable(R.drawable.recents_dismiss_dark);
        mCornerRadius = res.getDimensionPixelSize(R.dimen.recents_task_view_rounded_corners_radius);
        mHighlightHeight = res.getDimensionPixelSize(R.dimen.recents_task_view_highlight);
        mTaskBarViewLightTextColor = context.getColor(R.color.recents_task_bar_light_text_color);
        mTaskBarViewDarkTextColor = context.getColor(R.color.recents_task_bar_dark_text_color);
        mLightFreeformIcon = context.getDrawable(R.drawable.recents_move_task_freeform_light);
        mDarkFreeformIcon = context.getDrawable(R.drawable.recents_move_task_freeform_dark);
        mLightFullscreenIcon = context.getDrawable(R.drawable.recents_move_task_fullscreen_light);
        mDarkFullscreenIcon = context.getDrawable(R.drawable.recents_move_task_fullscreen_dark);

        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_linear_in);

        // Configure the background and dim
        mBackground = new HighlightColorDrawable();
        mBackground.setColorAndDim(Color.argb(255, 0, 0, 0), 0f);
        setBackground(mBackground);
        mDimLayerPaint.setColor(Color.argb(255, 0, 0, 0));
        mDimLayerPaint.setAntiAlias(true);
        mFocusTimerDuration = res.getInteger(R.integer.recents_auto_advance_duration);
    }

    @Override
    protected void onFinishInflate() {
        // Initialize the icon and description views
        mIconView = (ImageView) findViewById(R.id.icon);
        mIconView.setOnLongClickListener(this);
        mTitleView = (TextView) findViewById(R.id.title);
        mDismissButton = (ImageView) findViewById(R.id.dismiss_task);
        mDismissButton.setOnClickListener(this);
        mMoveTaskButton = (ImageView) findViewById(R.id.move_task);
        mFocusTimerIndicator = (ProgressBar) findViewById(R.id.focus_timer_indicator);

        // Hide the backgrounds if they are ripple drawables
        if (mIconView.getBackground() instanceof RippleDrawable) {
            mIconView.setBackground(null);
        }
    }

    /**
     * Called when the task view frame changes, allowing us to move the contents of the header
     * to match the frame changes.
     */
    public void onTaskViewSizeChanged(int width, int height) {
        // Return early if the bounds have not changed
        if (mTaskViewRect.width() == width && mTaskViewRect.height() == height) {
            return;
        }

        mTaskViewRect.set(0, 0, width, height);
        boolean updateMoveTaskButton = mMoveTaskButton.getVisibility() != View.GONE;
        int appIconWidth = mIconView.getMeasuredWidth();
        int activityDescWidth = (mTask != null)
                ? (int) mTitleView.getPaint().measureText(mTask.title)
                : mTitleView.getMeasuredWidth();
        int dismissIconWidth = mDismissButton.getMeasuredWidth();
        int moveTaskIconWidth = mMoveTaskButton.getVisibility() == View.VISIBLE
                ? mMoveTaskButton.getMeasuredWidth()
                : 0;

        // Priority-wise, we show the activity icon first, the dismiss icon if there is room, the
        // move-task icon if there is room, and then finally, the activity label if there is room
        if (width < (appIconWidth + dismissIconWidth)) {
            mTitleView.setVisibility(View.INVISIBLE);
            if (updateMoveTaskButton) {
                mMoveTaskButton.setVisibility(View.INVISIBLE);
            }
            mDismissButton.setVisibility(View.INVISIBLE);
        } else if (width < (appIconWidth + dismissIconWidth + moveTaskIconWidth)) {
            mTitleView.setVisibility(View.INVISIBLE);
            if (updateMoveTaskButton) {
                mMoveTaskButton.setVisibility(View.INVISIBLE);
            }
            mDismissButton.setVisibility(View.VISIBLE);
        } else if (width < (appIconWidth + dismissIconWidth + moveTaskIconWidth +
                activityDescWidth)) {
            mTitleView.setVisibility(View.INVISIBLE);
            if (updateMoveTaskButton) {
                mMoveTaskButton.setVisibility(View.VISIBLE);
            }
            mDismissButton.setVisibility(View.VISIBLE);
        } else {
            mTitleView.setVisibility(View.VISIBLE);
            if (updateMoveTaskButton) {
                mMoveTaskButton.setVisibility(View.VISIBLE);
            }
            mDismissButton.setVisibility(View.VISIBLE);
        }
        if (updateMoveTaskButton) {
            mMoveTaskButton.setTranslationX(width - getMeasuredWidth());
        }
        mDismissButton.setTranslationX(width - getMeasuredWidth());
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || (who == mBackground);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        // Draw the dim layer with the rounded corners
        canvas.drawRoundRect(0, 0, mTaskViewRect.width(), getHeight() + mCornerRadius,
                mCornerRadius, mCornerRadius, mDimLayerPaint);
    }

    /** Starts the focus timer. */
    public void startFocusTimerIndicator() {
        mFocusTimerIndicator.setVisibility(View.VISIBLE);
        mFocusTimerIndicator.setMax((int) mFocusTimerDuration);
        if (mFocusTimerCountDown == null) {
            mFocusTimerCountDown = new CountDownTimer(mFocusTimerDuration,
                    FOCUS_INDICATOR_INTERVAL_MS) {
                public void onTick(long millisUntilFinished) {
                    mFocusTimerIndicator.setProgress((int) millisUntilFinished);
                }

                public void onFinish() {
                    mFocusTimerIndicator.setProgress((int) mFocusTimerDuration);
                }
            }.start();
        } else {
            mFocusTimerCountDown.start();
        }
    }

    /** Cancels the focus timer. */
    public void cancelFocusTimerIndicator() {
        if (mFocusTimerCountDown != null && mFocusTimerIndicator != null) {
            mFocusTimerCountDown.cancel();
            mFocusTimerIndicator.setProgress(0);
            mFocusTimerIndicator.setVisibility(View.INVISIBLE);
        }
    }

    /** Returns the secondary color for a primary color. */
    int getSecondaryColor(int primaryColor, boolean useLightOverlayColor) {
        int overlayColor = useLightOverlayColor ? Color.WHITE : Color.BLACK;
        return Utilities.getColorWithOverlay(primaryColor, overlayColor, 0.8f);
    }

    /**
     * Sets the dim alpha, only used when we are not using hardware layers.
     * (see RecentsConfiguration.useHardwareLayers)
     */
    void setDimAlpha(float dimAlpha) {
        mDimAlpha = dimAlpha;
        updateBackgroundColor(dimAlpha);
        invalidate();
    }

    /**
     * Updates the background and highlight colors for this header.
     */
    private void updateBackgroundColor(float dimAlpha) {
        if (mTask != null) {
            mBackground.setColorAndDim(mTask.colorPrimary, dimAlpha);
            mDimLayerPaint.setAlpha((int) (dimAlpha * 255));
        }
    }

    /** Binds the bar view to the task */
    public void rebindToTask(Task t) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        mTask = t;

        // If an activity icon is defined, then we use that as the primary icon to show in the bar,
        // otherwise, we fall back to the application icon
        updateBackgroundColor(mDimAlpha);
        if (t.icon != null) {
            mIconView.setImageDrawable(t.icon);
        }
        if (!mTitleView.getText().toString().equals(t.title)) {
            mTitleView.setText(t.title);
        }
        mTitleView.setContentDescription(t.contentDescription);
        mTitleView.setTextColor(t.useLightOnPrimaryColor ?
                mTaskBarViewLightTextColor : mTaskBarViewDarkTextColor);
        mDismissButton.setImageDrawable(t.useLightOnPrimaryColor ?
                mLightDismissDrawable : mDarkDismissDrawable);
        mDismissButton.setContentDescription(t.dismissDescription);

        // When freeform workspaces are enabled, then update the move-task button depending on the
        // current task
        if (ssp.hasFreeformWorkspaceSupport()) {
            if (t.isFreeformTask()) {
                mMoveTaskTargetStackId = FULLSCREEN_WORKSPACE_STACK_ID;
                mMoveTaskButton.setImageDrawable(t.useLightOnPrimaryColor
                        ? mLightFullscreenIcon
                        : mDarkFullscreenIcon);
            } else {
                mMoveTaskTargetStackId = FREEFORM_WORKSPACE_STACK_ID;
                mMoveTaskButton.setImageDrawable(t.useLightOnPrimaryColor
                        ? mLightFreeformIcon
                        : mDarkFreeformIcon);
            }
            if (mMoveTaskButton.getVisibility() != View.VISIBLE) {
                mMoveTaskButton.setVisibility(View.VISIBLE);
            }
            mMoveTaskButton.setOnClickListener(this);
        }

        mFocusTimerIndicator.getProgressDrawable()
                .setColorFilter(
                        getSecondaryColor(t.colorPrimary, t.useLightOnPrimaryColor),
                        PorterDuff.Mode.SRC_IN);

        // In accessibility, a single click on the focused app info button will show it
        if (ssp.isTouchExplorationEnabled()) {
            mIconView.setOnClickListener(this);
        }
    }

    /** Unbinds the bar view from the task */
    void unbindFromTask() {
        mTask = null;
        mIconView.setImageDrawable(null);
        mIconView.setOnClickListener(null);
        mMoveTaskButton.setOnClickListener(null);
    }

    /** Animates this task bar if the user does not interact with the stack after a certain time. */
    void startNoUserInteractionAnimation() {
        if (mDismissButton.getVisibility() != View.VISIBLE) {
            mDismissButton.setVisibility(View.VISIBLE);
            mDismissButton.setAlpha(0f);
            mDismissButton.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setInterpolator(mFastOutLinearInInterpolator)
                    .setDuration(getResources().getInteger(
                            R.integer.recents_task_enter_from_app_duration))
                    .start();
        }
    }

    /**
     * Mark this task view that the user does has not interacted with the stack after a certain
     * time.
     */
    void setNoUserInteractionState() {
        if (mDismissButton.getVisibility() != View.VISIBLE) {
            mDismissButton.animate().cancel();
            mDismissButton.setVisibility(View.VISIBLE);
            mDismissButton.setAlpha(1f);
        }
    }

    /**
     * Resets the state tracking that the user has not interacted with the stack after a certain
     * time.
     */
    void resetNoUserInteractionState() {
        mDismissButton.setVisibility(View.INVISIBLE);
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {

        // Don't forward our state to the drawable - we do it manually in onTaskViewFocusChanged.
        // This is to prevent layer trashing when the view is pressed.
        return new int[] {};
    }

    @Override
    public void onClick(View v) {
        if (v == mIconView) {
            // In accessibility, a single click on the focused app info button will show it
            EventBus.getDefault().send(new ShowApplicationInfoEvent(mTask));
        } else if (v == mDismissButton) {
            TaskView tv = Utilities.findParent(this, TaskView.class);
            tv.dismissTask();

            // Keep track of deletions by the dismiss button
            MetricsLogger.histogram(getContext(), "overview_task_dismissed_source",
                    Constants.Metrics.DismissSourceHeaderButton);
        } else if (v == mMoveTaskButton) {
            TaskView tv = Utilities.findParent(this, TaskView.class);
            Rect bounds = mMoveTaskTargetStackId == FREEFORM_WORKSPACE_STACK_ID
                    ? new Rect(mTaskViewRect)
                    : new Rect();
            EventBus.getDefault().send(new LaunchTaskEvent(tv, mTask, bounds,
                    mMoveTaskTargetStackId, false));
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mIconView) {
            EventBus.getDefault().send(new ShowApplicationInfoEvent(mTask));
            return true;
        }
        return false;
    }
}
