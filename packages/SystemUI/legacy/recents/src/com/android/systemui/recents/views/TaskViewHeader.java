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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.CountDownTimer;
import androidx.core.graphics.ColorUtils;
import android.util.AttributeSet;
import android.util.IconDrawableFactory;
import android.view.Gravity;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.LegacyRecentsImpl;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.ui.ShowApplicationInfoEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.recents.utilities.Utilities;
import com.android.systemui.shared.recents.model.Task;

/* The task bar view */
public class TaskViewHeader extends FrameLayout
        implements View.OnClickListener, View.OnLongClickListener {

    private static IconDrawableFactory sDrawableFactory;

    private static final float HIGHLIGHT_LIGHTNESS_INCREMENT = 0.075f;
    private static final float OVERLAY_LIGHTNESS_INCREMENT = -0.0625f;
    private static final int OVERLAY_REVEAL_DURATION = 250;
    private static final long FOCUS_INDICATOR_INTERVAL_MS = 30;

    /**
     * A color drawable that draws a slight highlight at the top to help it stand out.
     */
    private class HighlightColorDrawable extends Drawable {

        private Paint mHighlightPaint = new Paint();
        private Paint mBackgroundPaint = new Paint();
        private int mColor;
        private float mDimAlpha;

        public HighlightColorDrawable() {
            mBackgroundPaint.setColor(Color.argb(255, 0, 0, 0));
            mBackgroundPaint.setAntiAlias(true);
            mHighlightPaint.setColor(Color.argb(255, 255, 255, 255));
            mHighlightPaint.setAntiAlias(true);
        }

        public void setColorAndDim(int color, float dimAlpha) {
            if (mColor != color || Float.compare(mDimAlpha, dimAlpha) != 0) {
                mColor = color;
                mDimAlpha = dimAlpha;
                if (mShouldDarkenBackgroundColor) {
                    color = getSecondaryColor(color, false /* useLightOverlayColor */);
                }
                mBackgroundPaint.setColor(color);

                ColorUtils.colorToHSL(color, mTmpHSL);
                // TODO: Consider using the saturation of the color to adjust the lightness as well
                mTmpHSL[2] = Math.min(1f,
                        mTmpHSL[2] + HIGHLIGHT_LIGHTNESS_INCREMENT * (1.0f - dimAlpha));
                mHighlightPaint.setColor(ColorUtils.HSLToColor(mTmpHSL));

                invalidateSelf();
            }
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

        public int getColor() {
            return mColor;
        }
    }

    Task mTask;

    // Header views
    ImageView mIconView;
    TextView mTitleView;
    ImageView mMoveTaskButton;
    ImageView mDismissButton;
    FrameLayout mAppOverlayView;
    ImageView mAppIconView;
    ImageView mAppInfoView;
    TextView mAppTitleView;
    ProgressBar mFocusTimerIndicator;

    // Header drawables
    @ViewDebug.ExportedProperty(category="recents")
    Rect mTaskViewRect = new Rect();
    int mHeaderBarHeight;
    int mHeaderButtonPadding;
    int mCornerRadius;
    int mHighlightHeight;
    @ViewDebug.ExportedProperty(category="recents")
    float mDimAlpha;
    Drawable mLightDismissDrawable;
    Drawable mDarkDismissDrawable;
    Drawable mLightFullscreenIcon;
    Drawable mDarkFullscreenIcon;
    Drawable mLightInfoIcon;
    Drawable mDarkInfoIcon;
    int mTaskBarViewLightTextColor;
    int mTaskBarViewDarkTextColor;
    int mDisabledTaskBarBackgroundColor;
    String mDismissDescFormat;
    String mAppInfoDescFormat;
    int mTaskWindowingMode = WINDOWING_MODE_UNDEFINED;

    // Header background
    private HighlightColorDrawable mBackground;
    private HighlightColorDrawable mOverlayBackground;
    private float[] mTmpHSL = new float[3];

    // Header dim, which is only used when task view hardware layers are not used
    private Paint mDimLayerPaint = new Paint();

    // Whether the background color should be darkened to differentiate from the primary color.
    // Used in grid layout.
    private boolean mShouldDarkenBackgroundColor = false;

    private CountDownTimer mFocusTimerCountDown;

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
        mCornerRadius = LegacyRecentsImpl.getConfiguration().isGridEnabled ?
                res.getDimensionPixelSize(R.dimen.recents_grid_task_view_rounded_corners_radius) :
                res.getDimensionPixelSize(R.dimen.recents_task_view_rounded_corners_radius);
        mHighlightHeight = res.getDimensionPixelSize(R.dimen.recents_task_view_highlight);
        mTaskBarViewLightTextColor = context.getColor(R.color.recents_task_bar_light_text_color);
        mTaskBarViewDarkTextColor = context.getColor(R.color.recents_task_bar_dark_text_color);
        mLightFullscreenIcon = context.getDrawable(R.drawable.recents_move_task_fullscreen_light);
        mDarkFullscreenIcon = context.getDrawable(R.drawable.recents_move_task_fullscreen_dark);
        mLightInfoIcon = context.getDrawable(R.drawable.recents_info_light);
        mDarkInfoIcon = context.getDrawable(R.drawable.recents_info_dark);
        mDisabledTaskBarBackgroundColor =
                context.getColor(R.color.recents_task_bar_disabled_background_color);
        mDismissDescFormat = mContext.getString(
                R.string.accessibility_recents_item_will_be_dismissed);
        mAppInfoDescFormat = mContext.getString(R.string.accessibility_recents_item_open_app_info);

        // Configure the background and dim
        mBackground = new HighlightColorDrawable();
        mBackground.setColorAndDim(Color.argb(255, 0, 0, 0), 0f);
        setBackground(mBackground);
        mOverlayBackground = new HighlightColorDrawable();
        mDimLayerPaint.setColor(Color.argb(255, 0, 0, 0));
        mDimLayerPaint.setAntiAlias(true);
    }

    /**
     * Resets this header along with the TaskView.
     */
    public void reset() {
        hideAppOverlay(true /* immediate */);
    }

    @Override
    protected void onFinishInflate() {
        SystemServicesProxy ssp = LegacyRecentsImpl.getSystemServices();

        // Initialize the icon and description views
        mIconView = findViewById(R.id.icon);
        mIconView.setOnLongClickListener(this);
        mTitleView = findViewById(R.id.title);
        mDismissButton = findViewById(R.id.dismiss_task);

        onConfigurationChanged();
    }

    /**
     * Programmatically sets the layout params for a header bar layout.  This is necessary because
     * we can't get resources based on the current configuration, but instead need to get them
     * based on the device configuration.
     */
    private void updateLayoutParams(View icon, View title, View secondaryButton, View button) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mHeaderBarHeight, Gravity.TOP);
        setLayoutParams(lp);
        lp = new FrameLayout.LayoutParams(mHeaderBarHeight, mHeaderBarHeight, Gravity.START);
        icon.setLayoutParams(lp);
        lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.setMarginStart(mHeaderBarHeight);
        lp.setMarginEnd(mMoveTaskButton != null
                ? 2 * mHeaderBarHeight
                : mHeaderBarHeight);
        title.setLayoutParams(lp);
        if (secondaryButton != null) {
            lp = new FrameLayout.LayoutParams(mHeaderBarHeight, mHeaderBarHeight, Gravity.END);
            lp.setMarginEnd(mHeaderBarHeight);
            secondaryButton.setLayoutParams(lp);
            secondaryButton.setPadding(mHeaderButtonPadding, mHeaderButtonPadding,
                    mHeaderButtonPadding, mHeaderButtonPadding);
        }
        lp = new FrameLayout.LayoutParams(mHeaderBarHeight, mHeaderBarHeight, Gravity.END);
        button.setLayoutParams(lp);
        button.setPadding(mHeaderButtonPadding, mHeaderButtonPadding, mHeaderButtonPadding,
                mHeaderButtonPadding);
    }

    /**
     * Update the header view when the configuration changes.
     */
    public void onConfigurationChanged() {
        // Update the dimensions of everything in the header. We do this because we need to use
        // resources for the display, and not the current configuration.
        Resources res = getResources();
        int headerBarHeight = TaskStackLayoutAlgorithm.getDimensionForDevice(getContext(),
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height_tablet_land,
                R.dimen.recents_task_view_header_height,
                R.dimen.recents_task_view_header_height_tablet_land,
                R.dimen.recents_grid_task_view_header_height);
        int headerButtonPadding = TaskStackLayoutAlgorithm.getDimensionForDevice(getContext(),
                R.dimen.recents_task_view_header_button_padding,
                R.dimen.recents_task_view_header_button_padding,
                R.dimen.recents_task_view_header_button_padding,
                R.dimen.recents_task_view_header_button_padding_tablet_land,
                R.dimen.recents_task_view_header_button_padding,
                R.dimen.recents_task_view_header_button_padding_tablet_land,
                R.dimen.recents_grid_task_view_header_button_padding);
        if (headerBarHeight != mHeaderBarHeight || headerButtonPadding != mHeaderButtonPadding) {
            mHeaderBarHeight = headerBarHeight;
            mHeaderButtonPadding = headerButtonPadding;
            updateLayoutParams(mIconView, mTitleView, mMoveTaskButton, mDismissButton);
            if (mAppOverlayView != null) {
                updateLayoutParams(mAppIconView, mAppTitleView, null, mAppInfoView);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Since we update the position of children based on the width of the parent and this view
        // recompute these changes with the new view size
        onTaskViewSizeChanged(mTaskViewRect.width(), mTaskViewRect.height());
    }

    /**
     * Called when the task view frame changes, allowing us to move the contents of the header
     * to match the frame changes.
     */
    public void onTaskViewSizeChanged(int width, int height) {
        mTaskViewRect.set(0, 0, width, height);

        boolean showTitle = true;
        boolean showMoveIcon = true;
        boolean showDismissIcon = true;
        int rightInset = width - getMeasuredWidth();

        mTitleView.setVisibility(showTitle ? View.VISIBLE : View.INVISIBLE);
        if (mMoveTaskButton != null) {
            mMoveTaskButton.setVisibility(showMoveIcon ? View.VISIBLE : View.INVISIBLE);
            mMoveTaskButton.setTranslationX(rightInset);
        }
        mDismissButton.setVisibility(showDismissIcon ? View.VISIBLE : View.INVISIBLE);
        mDismissButton.setTranslationX(rightInset);

        setLeftTopRightBottom(0, 0, width, getMeasuredHeight());
    }

    @Override
    public void onDrawForeground(Canvas canvas) {
        super.onDrawForeground(canvas);

        // Draw the dim layer with the rounded corners
        canvas.drawRoundRect(0, 0, mTaskViewRect.width(), getHeight() + mCornerRadius,
                mCornerRadius, mCornerRadius, mDimLayerPaint);
    }

    /** Starts the focus timer. */
    public void startFocusTimerIndicator(int duration) {
        if (mFocusTimerIndicator == null) {
            return;
        }

        mFocusTimerIndicator.setVisibility(View.VISIBLE);
        mFocusTimerIndicator.setMax(duration);
        mFocusTimerIndicator.setProgress(duration);
        if (mFocusTimerCountDown != null) {
            mFocusTimerCountDown.cancel();
        }
        mFocusTimerCountDown = new CountDownTimer(duration,
                FOCUS_INDICATOR_INTERVAL_MS) {
            public void onTick(long millisUntilFinished) {
                mFocusTimerIndicator.setProgress((int) millisUntilFinished);
            }

            public void onFinish() {
                // Do nothing
            }
        }.start();
    }

    /** Cancels the focus timer. */
    public void cancelFocusTimerIndicator() {
        if (mFocusTimerIndicator == null) {
            return;
        }

        if (mFocusTimerCountDown != null) {
            mFocusTimerCountDown.cancel();
            mFocusTimerIndicator.setProgress(0);
            mFocusTimerIndicator.setVisibility(View.INVISIBLE);
        }
    }

    /** Only exposed for the workaround for b/27815919. */
    public ImageView getIconView() {
        return mIconView;
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
    public void setDimAlpha(float dimAlpha) {
        if (Float.compare(mDimAlpha, dimAlpha) != 0) {
            mDimAlpha = dimAlpha;
            mTitleView.setAlpha(1f - dimAlpha);
            updateBackgroundColor(mBackground.getColor(), dimAlpha);
        }
    }

    /**
     * Updates the background and highlight colors for this header.
     */
    private void updateBackgroundColor(int color, float dimAlpha) {
        if (mTask != null) {
            mBackground.setColorAndDim(color, dimAlpha);
            // TODO: Consider using the saturation of the color to adjust the lightness as well
            ColorUtils.colorToHSL(color, mTmpHSL);
            mTmpHSL[2] = Math.min(1f, mTmpHSL[2] + OVERLAY_LIGHTNESS_INCREMENT * (1.0f - dimAlpha));
            mOverlayBackground.setColorAndDim(ColorUtils.HSLToColor(mTmpHSL), dimAlpha);
            mDimLayerPaint.setAlpha((int) (dimAlpha * 255));
            invalidate();
        }
    }

    /**
     * Sets whether the background color should be darkened to differentiate from the primary color.
     */
    public void setShouldDarkenBackgroundColor(boolean flag) {
        mShouldDarkenBackgroundColor = flag;
    }

    /**
     * Binds the bar view to the task.
     */
    public void bindToTask(Task t, boolean touchExplorationEnabled, boolean disabledInSafeMode) {
        mTask = t;

        int primaryColor = disabledInSafeMode
                ? mDisabledTaskBarBackgroundColor
                : t.colorPrimary;
        if (mBackground.getColor() != primaryColor) {
            updateBackgroundColor(primaryColor, mDimAlpha);
        }
        if (!mTitleView.getText().toString().equals(t.title)) {
            mTitleView.setText(t.title);
        }
        mTitleView.setContentDescription(t.titleDescription);
        mTitleView.setTextColor(t.useLightOnPrimaryColor ?
                mTaskBarViewLightTextColor : mTaskBarViewDarkTextColor);
        mDismissButton.setImageDrawable(t.useLightOnPrimaryColor ?
                mLightDismissDrawable : mDarkDismissDrawable);
        mDismissButton.setContentDescription(String.format(mDismissDescFormat, t.titleDescription));
        mDismissButton.setOnClickListener(this);
        mDismissButton.setClickable(false);
        ((RippleDrawable) mDismissButton.getBackground()).setForceSoftware(true);

        // In accessibility, a single click on the focused app info button will show it
        if (touchExplorationEnabled) {
            mIconView.setContentDescription(String.format(mAppInfoDescFormat, t.titleDescription));
            mIconView.setOnClickListener(this);
            mIconView.setClickable(true);
        }
    }

    /**
     * Called when the bound task's data has loaded and this view should update to reflect the
     * changes.
     */
    public void onTaskDataLoaded() {
        if (mTask != null && mTask.icon != null) {
            mIconView.setImageDrawable(mTask.icon);
        }
    }

    /** Unbinds the bar view from the task */
    void unbindFromTask(boolean touchExplorationEnabled) {
        mTask = null;
        mIconView.setImageDrawable(null);
        if (touchExplorationEnabled) {
            mIconView.setClickable(false);
        }
    }

    /** Animates this task bar if the user does not interact with the stack after a certain time. */
    void startNoUserInteractionAnimation() {
        int duration = getResources().getInteger(R.integer.recents_task_enter_from_app_duration);
        mDismissButton.setVisibility(View.VISIBLE);
        mDismissButton.setClickable(true);
        if (mDismissButton.getVisibility() == VISIBLE) {
            mDismissButton.animate()
                    .alpha(1f)
                    .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                    .setDuration(duration)
                    .start();
        } else {
            mDismissButton.setAlpha(1f);
        }
        if (mMoveTaskButton != null) {
            if (mMoveTaskButton.getVisibility() == VISIBLE) {
                mMoveTaskButton.setVisibility(View.VISIBLE);
                mMoveTaskButton.setClickable(true);
                mMoveTaskButton.animate()
                        .alpha(1f)
                        .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                        .setDuration(duration)
                        .start();
            } else {
                mMoveTaskButton.setAlpha(1f);
            }
        }
    }

    /**
     * Mark this task view that the user does has not interacted with the stack after a certain
     * time.
     */
    public void setNoUserInteractionState() {
        mDismissButton.setVisibility(View.VISIBLE);
        mDismissButton.animate().cancel();
        mDismissButton.setAlpha(1f);
        mDismissButton.setClickable(true);
        if (mMoveTaskButton != null) {
            mMoveTaskButton.setVisibility(View.VISIBLE);
            mMoveTaskButton.animate().cancel();
            mMoveTaskButton.setAlpha(1f);
            mMoveTaskButton.setClickable(true);
        }
    }

    /**
     * Resets the state tracking that the user has not interacted with the stack after a certain
     * time.
     */
    void resetNoUserInteractionState() {
        mDismissButton.setVisibility(View.INVISIBLE);
        mDismissButton.setAlpha(0f);
        mDismissButton.setClickable(false);
        if (mMoveTaskButton != null) {
            mMoveTaskButton.setVisibility(View.INVISIBLE);
            mMoveTaskButton.setAlpha(0f);
            mMoveTaskButton.setClickable(false);
        }
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
            EventBus.getDefault().send(new LaunchTaskEvent(tv, mTask, null, false,
                    mTaskWindowingMode, ACTIVITY_TYPE_UNDEFINED));
        } else if (v == mAppInfoView) {
            EventBus.getDefault().send(new ShowApplicationInfoEvent(mTask));
        } else if (v == mAppIconView) {
            hideAppOverlay(false /* immediate */);
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mIconView) {
            showAppOverlay();
            return true;
        } else if (v == mAppIconView) {
            hideAppOverlay(false /* immediate */);
            return true;
        }
        return false;
    }

    /**
     * Shows the application overlay.
     */
    private void showAppOverlay() {
        // Skip early if the task is invalid
        SystemServicesProxy ssp = LegacyRecentsImpl.getSystemServices();
        ComponentName cn = mTask.key.getComponent();
        int userId = mTask.key.userId;
        ActivityInfo activityInfo = PackageManagerWrapper.getInstance().getActivityInfo(cn, userId);
        if (activityInfo == null) {
            return;
        }

        // Inflate the overlay if necessary
        if (mAppOverlayView == null) {
            mAppOverlayView = (FrameLayout) Utilities.findViewStubById(this,
                    R.id.app_overlay_stub).inflate();
            mAppOverlayView.setBackground(mOverlayBackground);
            mAppIconView = (ImageView) mAppOverlayView.findViewById(R.id.app_icon);
            mAppIconView.setOnClickListener(this);
            mAppIconView.setOnLongClickListener(this);
            mAppInfoView = (ImageView) mAppOverlayView.findViewById(R.id.app_info);
            mAppInfoView.setOnClickListener(this);
            mAppTitleView = (TextView) mAppOverlayView.findViewById(R.id.app_title);
            updateLayoutParams(mAppIconView, mAppTitleView, null, mAppInfoView);
        }

        // Update the overlay contents for the current app
        mAppTitleView.setText(ActivityManagerWrapper.getInstance().getBadgedApplicationLabel(
                activityInfo.applicationInfo, userId));
        mAppTitleView.setTextColor(mTask.useLightOnPrimaryColor ?
                mTaskBarViewLightTextColor : mTaskBarViewDarkTextColor);
        mAppIconView.setImageDrawable(getIconDrawableFactory().getBadgedIcon(
                activityInfo.applicationInfo, userId));
        mAppInfoView.setImageDrawable(mTask.useLightOnPrimaryColor
                ? mLightInfoIcon
                : mDarkInfoIcon);
        mAppOverlayView.setVisibility(View.VISIBLE);

        int x = mIconView.getLeft() + mIconView.getWidth() / 2;
        int y = mIconView.getTop() + mIconView.getHeight() / 2;
        Animator revealAnim = ViewAnimationUtils.createCircularReveal(mAppOverlayView, x, y, 0,
                getWidth());
        revealAnim.setDuration(OVERLAY_REVEAL_DURATION);
        revealAnim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        revealAnim.start();
    }

    /**
     * Hide the application overlay.
     */
    private void hideAppOverlay(boolean immediate) {
        // Skip if we haven't even loaded the overlay yet
        if (mAppOverlayView == null) {
            return;
        }

        if (immediate) {
            mAppOverlayView.setVisibility(View.GONE);
        } else {
            int x = mIconView.getLeft() + mIconView.getWidth() / 2;
            int y = mIconView.getTop() + mIconView.getHeight() / 2;
            Animator revealAnim = ViewAnimationUtils.createCircularReveal(mAppOverlayView, x, y,
                    getWidth(), 0);
            revealAnim.setDuration(OVERLAY_REVEAL_DURATION);
            revealAnim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            revealAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mAppOverlayView.setVisibility(View.GONE);
                }
            });
            revealAnim.start();
        }
    }

    private static IconDrawableFactory getIconDrawableFactory() {
        if (sDrawableFactory == null) {
            sDrawableFactory = IconDrawableFactory.newInstance(AppGlobals.getInitialApplication());
        }
        return sDrawableFactory;
    }
}
