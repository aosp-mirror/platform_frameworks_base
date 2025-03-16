/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_NONE;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.UiContext;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.util.MathUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.ImageView;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.res.R;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * Shows/hides a {@link android.widget.ImageView} on the screen and changes the values of
 * {@link Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE} when the UI is toggled.
 * The button icon is movable by dragging and it would not overlap navigation bar window.
 * And the button UI would automatically be dismissed after displaying for a period of time.
 */
class MagnificationModeSwitch implements MagnificationGestureDetector.OnGestureListener,
        ComponentCallbacks {

    @VisibleForTesting
    static final long FADING_ANIMATION_DURATION_MS = 300;
    @VisibleForTesting
    static final int DEFAULT_FADE_OUT_ANIMATION_DELAY_MS = 5000;
    private int mUiTimeout;
    private final Runnable mFadeInAnimationTask;
    private final Runnable mFadeOutAnimationTask;
    @VisibleForTesting
    boolean mIsFadeOutAnimating = false;

    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;
    private final WindowManager mWindowManager;
    private final ViewCaptureAwareWindowManager mViewCaptureAwareWindowManager;
    private final ImageView mImageView;
    private final Runnable mWindowInsetChangeRunnable;
    private final SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    private int mMagnificationMode = ACCESSIBILITY_MAGNIFICATION_MODE_NONE;
    private final LayoutParams mParams;
    private final ClickListener mClickListener;
    private final Configuration mConfiguration;
    @VisibleForTesting
    final Rect mDraggableWindowBounds = new Rect();
    private boolean mIsVisible = false;
    private final MagnificationGestureDetector mGestureDetector;
    private boolean mSingleTapDetected = false;
    private boolean mToLeftScreenEdge = false;

    public interface ClickListener {
        /**
         * Called when the switch is clicked to change the magnification mode.
         * @param displayId the display id of the display to which the view's window has been
         *                  attached
         */
        void onClick(int displayId);
    }

    MagnificationModeSwitch(@UiContext Context context, ClickListener clickListener,
            ViewCaptureAwareWindowManager viewCaptureAwareWindowManager) {
        this(context, createView(context), new SfVsyncFrameCallbackProvider(), clickListener,
                viewCaptureAwareWindowManager);
    }

    @VisibleForTesting
    MagnificationModeSwitch(Context context, @NonNull ImageView imageView,
            SfVsyncFrameCallbackProvider sfVsyncFrameProvider, ClickListener clickListener,
            ViewCaptureAwareWindowManager viewCaptureAwareWindowManager) {
        mContext = context;
        mConfiguration = new Configuration(context.getResources().getConfiguration());
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mViewCaptureAwareWindowManager = viewCaptureAwareWindowManager;
        mSfVsyncFrameProvider = sfVsyncFrameProvider;
        mClickListener = clickListener;
        mParams = createLayoutParams(context);
        mImageView = imageView;
        mImageView.setOnTouchListener(this::onTouch);
        mImageView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setStateDescription(formatStateDescription());
                info.setContentDescription(mContext.getResources().getString(
                        R.string.magnification_mode_switch_description));
                final AccessibilityAction clickAction = new AccessibilityAction(
                        AccessibilityAction.ACTION_CLICK.getId(), mContext.getResources().getString(
                        R.string.magnification_open_settings_click_label));
                info.addAction(clickAction);
                info.setClickable(true);
                info.addAction(new AccessibilityAction(R.id.accessibility_action_move_up,
                        mContext.getString(R.string.accessibility_control_move_up)));
                info.addAction(new AccessibilityAction(R.id.accessibility_action_move_down,
                        mContext.getString(R.string.accessibility_control_move_down)));
                info.addAction(new AccessibilityAction(R.id.accessibility_action_move_left,
                        mContext.getString(R.string.accessibility_control_move_left)));
                info.addAction(new AccessibilityAction(R.id.accessibility_action_move_right,
                        mContext.getString(R.string.accessibility_control_move_right)));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (performA11yAction(action)) {
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }

            private boolean performA11yAction(int action) {
                final Rect windowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
                if (action == AccessibilityAction.ACTION_CLICK.getId()) {
                    handleSingleTap();
                } else if (action == R.id.accessibility_action_move_up) {
                    moveButton(0, -windowBounds.height());
                } else if (action == R.id.accessibility_action_move_down) {
                    moveButton(0, windowBounds.height());
                } else if (action == R.id.accessibility_action_move_left) {
                    moveButton(-windowBounds.width(), 0);
                } else if (action == R.id.accessibility_action_move_right) {
                    moveButton(windowBounds.width(), 0);
                } else {
                    return false;
                }
                return true;
            }
        });
        mWindowInsetChangeRunnable = this::onWindowInsetChanged;
        mImageView.setOnApplyWindowInsetsListener((v, insets) -> {
            // Adds a pending post check to avoiding redundant calculation because this callback
            // is sent frequently when the switch icon window dragged by the users.
            if (!mImageView.getHandler().hasCallbacks(mWindowInsetChangeRunnable)) {
                mImageView.getHandler().post(mWindowInsetChangeRunnable);
            }
            return v.onApplyWindowInsets(insets);
        });

        mFadeInAnimationTask = () -> {
            mImageView.animate()
                    .alpha(1f)
                    .setDuration(FADING_ANIMATION_DURATION_MS)
                    .start();
        };
        mFadeOutAnimationTask = () -> {
            mImageView.animate()
                    .alpha(0f)
                    .setDuration(FADING_ANIMATION_DURATION_MS)
                    .withEndAction(() -> removeButton())
                    .start();
            mIsFadeOutAnimating = true;
        };
        mGestureDetector = new MagnificationGestureDetector(context,
                context.getMainThreadHandler(), this);
    }

    private CharSequence formatStateDescription() {
        final int stringId = mMagnificationMode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
                ? R.string.magnification_mode_switch_state_window
                : R.string.magnification_mode_switch_state_full_screen;
        return mContext.getResources().getString(stringId);
    }

    private void applyResourcesValuesWithDensityChanged() {
        final int size = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnification_switch_button_size);
        mParams.height = size;
        mParams.width = size;
        if (mIsVisible) {
            stickToScreenEdge(mToLeftScreenEdge);
            // Reset button to make its window layer always above the mirror window.
            removeButton();
            showButton(mMagnificationMode, /* resetPosition= */false);
        }
    }

    private boolean onTouch(View v, MotionEvent event) {
        if (!mIsVisible) {
            return false;
        }
        return mGestureDetector.onTouch(v, event);
    }

    @Override
    public boolean onSingleTap(View v) {
        mSingleTapDetected = true;
        handleSingleTap();
        return true;
    }

    @Override
    public boolean onDrag(View v, float offsetX, float offsetY) {
        moveButton(offsetX, offsetY);
        return true;
    }

    @Override
    public boolean onStart(float x, float y) {
        stopFadeOutAnimation();
        return true;
    }

    @Override
    public boolean onFinish(float xOffset, float yOffset) {
        if (mIsVisible) {
            final int windowWidth = mWindowManager.getCurrentWindowMetrics().getBounds().width();
            final int halfWindowWidth = windowWidth / 2;
            mToLeftScreenEdge = (mParams.x < halfWindowWidth);
            stickToScreenEdge(mToLeftScreenEdge);
        }
        if (!mSingleTapDetected) {
            showButton(mMagnificationMode);
        }
        mSingleTapDetected = false;
        return true;
    }

    private void stickToScreenEdge(boolean toLeftScreenEdge) {
        mParams.x = toLeftScreenEdge
                ? mDraggableWindowBounds.left : mDraggableWindowBounds.right;
        updateButtonViewLayoutIfNeeded();
    }

    private void moveButton(float offsetX, float offsetY) {
        mSfVsyncFrameProvider.postFrameCallback(l -> {
            mParams.x += offsetX;
            mParams.y += offsetY;
            updateButtonViewLayoutIfNeeded();
        });
    }

    void removeButton() {
        if (!mIsVisible) {
            return;
        }
        // Reset button status.
        mImageView.removeCallbacks(mFadeInAnimationTask);
        mImageView.removeCallbacks(mFadeOutAnimationTask);
        mImageView.animate().cancel();
        mIsFadeOutAnimating = false;
        mImageView.setAlpha(0f);
        mViewCaptureAwareWindowManager.removeView(mImageView);
        mContext.unregisterComponentCallbacks(this);
        mIsVisible = false;
    }

    void showButton(int mode) {
        showButton(mode, true);
    }

    /**
     * Shows magnification switch button for the specified magnification mode.
     * When the button is going to be visible by calling this method, the layout position can be
     * reset depending on the flag.
     *
     * @param mode          The magnification mode
     * @param resetPosition if the button position needs be reset
     */
    private void showButton(int mode, boolean resetPosition) {
        if (mode != Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN) {
            return;
        }
        if (mMagnificationMode != mode) {
            mMagnificationMode = mode;
            mImageView.setImageResource(getIconResId());
        }
        if (!mIsVisible) {
            onConfigurationChanged(mContext.getResources().getConfiguration());
            mContext.registerComponentCallbacks(this);
            if (resetPosition) {
                mDraggableWindowBounds.set(getDraggableWindowBounds());
                mParams.x = mDraggableWindowBounds.right;
                mParams.y = mDraggableWindowBounds.bottom;
                mToLeftScreenEdge = false;
            }
            mViewCaptureAwareWindowManager.addView(mImageView, mParams);
            // Exclude magnification switch button from system gesture area.
            setSystemGestureExclusion();
            mIsVisible = true;
            mImageView.postOnAnimation(mFadeInAnimationTask);
            mUiTimeout = mAccessibilityManager.getRecommendedTimeoutMillis(
                    DEFAULT_FADE_OUT_ANIMATION_DELAY_MS,
                    AccessibilityManager.FLAG_CONTENT_ICONS
                            | AccessibilityManager.FLAG_CONTENT_CONTROLS);
            if (shouldAlwaysShowSettings()) {
                mUiTimeout = -1;
            }
        }
        // Refresh the time slot of the fade-out task whenever this method is called.
        stopFadeOutAnimation();
        if (mUiTimeout >= 0) {
            mImageView.postOnAnimationDelayed(mFadeOutAnimationTask, mUiTimeout);
        }
    }

    private boolean shouldAlwaysShowSettings() {
        try {
            var serviceNamesArray = mContext.getResources().getStringArray(
                    R.array.services_always_show_magnification_settings);
            if (serviceNamesArray.length == 0) {
                return false;
            }
            Set serviceNamesSet = Set.of(serviceNamesArray);

            var serviceInfoList = mAccessibilityManager
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (var serviceInfo : serviceInfoList) {
                var serviceName = Optional.ofNullable(serviceInfo)
                        .map(AccessibilityServiceInfo::getResolveInfo)
                        .map(resolveInfo -> resolveInfo.serviceInfo)
                        .map(resolvedServiceInfo -> resolvedServiceInfo.name)
                        .orElse(null);
                if (serviceName == null) {
                    continue;
                }

                if (serviceNamesSet.contains(serviceName)) {
                    return true;
                }
            }
        } catch (Resources.NotFoundException nfe) {
            // No-op. Do not crash for not finding resources.
        }
        return false;
    }

    private void stopFadeOutAnimation() {
        mImageView.removeCallbacks(mFadeOutAnimationTask);
        if (mIsFadeOutAnimating) {
            mImageView.animate().cancel();
            mImageView.setAlpha(1f);
            mIsFadeOutAnimating = false;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        final int configDiff = newConfig.diff(mConfiguration);
        mConfiguration.setTo(newConfig);
        onConfigurationChanged(configDiff);
    }

    @Override
    public void onLowMemory() {
    }

    void onConfigurationChanged(int configDiff) {
        if (configDiff == 0) {
            return;
        }
        if ((configDiff & (ActivityInfo.CONFIG_ORIENTATION | ActivityInfo.CONFIG_SCREEN_SIZE))
                != 0) {
            final Rect previousDraggableBounds = new Rect(mDraggableWindowBounds);
            mDraggableWindowBounds.set(getDraggableWindowBounds());
            // Keep the Y position with the same height ratio before the window bounds and
            // draggable bounds are changed.
            final float windowHeightFraction = (float) (mParams.y - previousDraggableBounds.top)
                    / previousDraggableBounds.height();
            mParams.y = (int) (windowHeightFraction * mDraggableWindowBounds.height())
                    + mDraggableWindowBounds.top;
            stickToScreenEdge(mToLeftScreenEdge);
            return;
        }
        if ((configDiff & ActivityInfo.CONFIG_DENSITY) != 0) {
            applyResourcesValuesWithDensityChanged();
            return;
        }
        if ((configDiff & ActivityInfo.CONFIG_LOCALE) != 0) {
            updateAccessibilityWindowTitle();
            return;
        }
    }

    private void onWindowInsetChanged() {
        final Rect newBounds = getDraggableWindowBounds();
        if (mDraggableWindowBounds.equals(newBounds)) {
            return;
        }
        mDraggableWindowBounds.set(newBounds);
        stickToScreenEdge(mToLeftScreenEdge);
    }

    private void updateButtonViewLayoutIfNeeded() {
        if (mIsVisible) {
            mParams.x = MathUtils.constrain(mParams.x, mDraggableWindowBounds.left,
                    mDraggableWindowBounds.right);
            mParams.y = MathUtils.constrain(mParams.y, mDraggableWindowBounds.top,
                    mDraggableWindowBounds.bottom);
            mWindowManager.updateViewLayout(mImageView, mParams);
        }
    }

    private void updateAccessibilityWindowTitle() {
        mParams.accessibilityTitle = getAccessibilityWindowTitle(mContext);
        if (mIsVisible) {
            mWindowManager.updateViewLayout(mImageView, mParams);
        }
    }

    private void handleSingleTap() {
        removeButton();
        mClickListener.onClick(mContext.getDisplayId());
    }

    private static ImageView createView(Context context) {
        ImageView imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setClickable(true);
        imageView.setFocusable(true);
        imageView.setAlpha(0f);
        return imageView;
    }

    @VisibleForTesting
    static int getIconResId() {
        return R.drawable.ic_open_in_new_window;
    }

    private static LayoutParams createLayoutParams(Context context) {
        final int size = context.getResources().getDimensionPixelSize(
                R.dimen.magnification_switch_button_size);
        final LayoutParams params = new LayoutParams(
                size,
                size,
                LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.accessibilityTitle = getAccessibilityWindowTitle(context);
        params.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return params;
    }

    private Rect getDraggableWindowBounds() {
        final int layoutMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnification_switch_button_margin);
        final WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        final Insets windowInsets = windowMetrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        final Rect boundRect = new Rect(windowMetrics.getBounds());
        boundRect.offsetTo(0, 0);
        boundRect.inset(0, 0, mParams.width, mParams.height);
        boundRect.inset(windowInsets);
        boundRect.inset(layoutMargin, layoutMargin);
        return boundRect;
    }

    private static String getAccessibilityWindowTitle(Context context) {
        return context.getString(com.android.internal.R.string.android_system_label);
    }

    private void setSystemGestureExclusion() {
        mImageView.post(() -> {
            mImageView.setSystemGestureExclusionRects(
                    Collections.singletonList(
                            new Rect(0, 0, mImageView.getWidth(), mImageView.getHeight())));
        });
    }
}