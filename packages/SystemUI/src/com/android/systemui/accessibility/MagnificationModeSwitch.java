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

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.ImageView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.util.Collections;

/**
 * Shows/hides a {@link android.widget.ImageView} on the screen and changes the values of
 * {@link Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE} when the UI is toggled.
 * The button icon is movable by dragging. And the button UI would automatically be dismissed after
 * displaying for a period of time.
 */
class MagnificationModeSwitch implements MagnificationGestureDetector.OnGestureListener {

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
    private final ImageView mImageView;
    private int mMagnificationMode = Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
    private final LayoutParams mParams;
    private boolean mIsVisible = false;
    private final MagnificationGestureDetector mGestureDetector;
    private boolean mSingleTapDetected = false;

    MagnificationModeSwitch(Context context) {
        this(context, createView(context));
    }

    @VisibleForTesting
    MagnificationModeSwitch(Context context, @NonNull ImageView imageView) {
        mContext = context;
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mWindowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        mParams = createLayoutParams(context);
        mImageView = imageView;
        applyResourcesValues();
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
                        R.string.magnification_mode_switch_click_label));
                info.addAction(clickAction);
                info.setClickable(true);
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityAction.ACTION_CLICK.getId()) {
                    handleSingleTap();
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
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

    private void applyResourcesValues() {
        final int padding = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnification_switch_button_padding);
        mImageView.setPadding(padding, padding, padding, padding);
        mImageView.setImageResource(getIconResId(mMagnificationMode));
    }

    private boolean onTouch(View v, MotionEvent event) {
        if (!mIsVisible) {
            return false;
        }
        return mGestureDetector.onTouch(event);
    }

    @Override
    public boolean onSingleTap() {
        mSingleTapDetected = true;
        handleSingleTap();
        return true;
    }

    @Override
    public boolean onDrag(float offsetX, float offsetY) {
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
        if (!mSingleTapDetected) {
            showButton(mMagnificationMode);
        }
        mSingleTapDetected = false;
        return true;
    }

    private void moveButton(float offsetX, float offsetY) {
        mParams.x -= offsetX;
        mParams.y -= offsetY;
        mWindowManager.updateViewLayout(mImageView, mParams);
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
        mWindowManager.removeView(mImageView);
        mIsVisible = false;
        mParams.x = 0;
        mParams.y = 0;
    }

    void showButton(int mode) {
        if (mMagnificationMode != mode) {
            mMagnificationMode = mode;
            mImageView.setImageResource(getIconResId(mode));
        }
        if (!mIsVisible) {
            mWindowManager.addView(mImageView, mParams);
            // Exclude magnification switch button from system gesture area.
            setSystemGestureExclusion();
            mIsVisible = true;
            mImageView.postOnAnimation(mFadeInAnimationTask);
            mUiTimeout = mAccessibilityManager.getRecommendedTimeoutMillis(
                    DEFAULT_FADE_OUT_ANIMATION_DELAY_MS,
                    AccessibilityManager.FLAG_CONTENT_ICONS
                            | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        // Refresh the time slot of the fade-out task whenever this method is called.
        stopFadeOutAnimation();
        mImageView.postOnAnimationDelayed(mFadeOutAnimationTask, mUiTimeout);
    }

    private void stopFadeOutAnimation() {
        mImageView.removeCallbacks(mFadeOutAnimationTask);
        if (mIsFadeOutAnimating) {
            mImageView.animate().cancel();
            mImageView.setAlpha(1f);
            mIsFadeOutAnimating = false;
        }
    }

    void onConfigurationChanged(int configDiff) {
        if ((configDiff & ActivityInfo.CONFIG_DENSITY) != 0) {
            applyResourcesValues();
            if (mIsVisible) {
                mWindowManager.updateViewLayout(mImageView, mParams);
                // Exclude magnification switch button from system gesture area.
                setSystemGestureExclusion();
            }
            return;
        }
        if ((configDiff & ActivityInfo.CONFIG_LOCALE) != 0) {
            updateAccessibilityWindowTitle();
            return;
        }
    }

    private void updateAccessibilityWindowTitle() {
        mParams.accessibilityTitle = getAccessibilityWindowTitle(mContext);
        if (mIsVisible) {
            mWindowManager.updateViewLayout(mImageView, mParams);
        }
    }

    private void toggleMagnificationMode() {
        final int newMode =
                mMagnificationMode ^ Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
        mMagnificationMode = newMode;
        mImageView.setImageResource(getIconResId(newMode));
        Settings.Secure.putIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE,
                newMode,
                UserHandle.USER_CURRENT);
    }

    private void handleSingleTap() {
        removeButton();
        toggleMagnificationMode();
    }

    private static ImageView createView(Context context) {
        ImageView imageView = new ImageView(context);
        imageView.setClickable(true);
        imageView.setFocusable(true);
        imageView.setAlpha(0f);
        return imageView;
    }

    @VisibleForTesting
    static int getIconResId(int mode) {
        return (mode == Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN)
                ? R.drawable.ic_open_in_new_window
                : R.drawable.ic_open_in_new_fullscreen;
    }

    private static LayoutParams createLayoutParams(Context context) {
        final LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        params.accessibilityTitle = getAccessibilityWindowTitle(context);
        return params;
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
