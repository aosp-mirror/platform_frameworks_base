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

package com.android.systemui.navigationbar.gestural;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.navigationbar.RotationButton;
import com.android.systemui.navigationbar.RotationButtonController;
import com.android.systemui.navigationbar.buttons.KeyButtonDrawable;
import com.android.systemui.navigationbar.buttons.KeyButtonView;
import com.android.systemui.navigationbar.gestural.FloatingRotationButtonPositionCalculator.Position;

/**
 * Containing logic for the rotation button on the physical left bottom corner of the screen.
 */
public class FloatingRotationButton implements RotationButton {

    private static final float BACKGROUND_ALPHA = 0.92f;
    private static final int MARGIN_ANIMATION_DURATION_MILLIS = 300;

    private final WindowManager mWindowManager;
    private final ViewGroup mKeyButtonContainer;
    private final KeyButtonView mKeyButtonView;

    private final int mContainerSize;

    private KeyButtonDrawable mKeyButtonDrawable;
    private boolean mIsShowing;
    private boolean mCanShow = true;
    private int mDisplayRotation;

    private boolean mIsTaskbarVisible = false;
    private boolean mIsTaskbarStashed = false;

    private final FloatingRotationButtonPositionCalculator mPositionCalculator;

    private RotationButtonController mRotationButtonController;
    private RotationButtonUpdatesCallback mUpdatesCallback;
    private Position mPosition;

    public FloatingRotationButton(Context context) {
        mWindowManager = context.getSystemService(WindowManager.class);
        mKeyButtonContainer = (ViewGroup) LayoutInflater.from(context).inflate(
                R.layout.rotate_suggestion, null);
        mKeyButtonView = mKeyButtonContainer.findViewById(R.id.rotate_suggestion);
        mKeyButtonView.setVisibility(View.VISIBLE);

        Resources res = context.getResources();

        int defaultMargin = Math.max(
                res.getDimensionPixelSize(R.dimen.floating_rotation_button_min_margin),
                res.getDimensionPixelSize(R.dimen.rounded_corner_content_padding));

        int taskbarMarginLeft =
                res.getDimensionPixelSize(R.dimen.floating_rotation_button_taskbar_left_margin);
        int taskbarMarginBottom =
                res.getDimensionPixelSize(R.dimen.floating_rotation_button_taskbar_bottom_margin);

        mPositionCalculator = new FloatingRotationButtonPositionCalculator(defaultMargin,
                taskbarMarginLeft, taskbarMarginBottom);

        final int diameter = res.getDimensionPixelSize(R.dimen.floating_rotation_button_diameter);
        mContainerSize = diameter + Math.max(defaultMargin, Math.max(taskbarMarginLeft,
                taskbarMarginBottom));
    }

    @Override
    public void setRotationButtonController(RotationButtonController rotationButtonController) {
        mRotationButtonController = rotationButtonController;
        updateIcon(mRotationButtonController.getLightIconColor(),
                mRotationButtonController.getDarkIconColor());
    }

    @Override
    public void setUpdatesCallback(RotationButtonUpdatesCallback updatesCallback) {
        mUpdatesCallback = updatesCallback;
    }

    @Override
    public View getCurrentView() {
        return mKeyButtonView;
    }

    @Override
    public boolean show() {
        if (!mCanShow || mIsShowing) {
            return false;
        }

        mIsShowing = true;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mContainerSize,
                mContainerSize,
                0, 0, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, flags,
                PixelFormat.TRANSLUCENT);

        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("FloatingRotationButton");
        lp.setFitInsetsTypes(0 /*types */);

        mDisplayRotation = mWindowManager.getDefaultDisplay().getRotation();
        mPosition = mPositionCalculator
                .calculatePosition(mDisplayRotation, mIsTaskbarVisible, mIsTaskbarStashed);

        lp.gravity = mPosition.getGravity();
        ((FrameLayout.LayoutParams) mKeyButtonView.getLayoutParams()).gravity =
                mPosition.getGravity();

        updateTranslation(mPosition, /* animate */ false);

        mWindowManager.addView(mKeyButtonContainer, lp);
        if (mKeyButtonDrawable != null && mKeyButtonDrawable.canAnimate()) {
            mKeyButtonDrawable.resetAnimation();
            mKeyButtonDrawable.startAnimation();
        }

        if (mUpdatesCallback != null) {
            mUpdatesCallback.onVisibilityChanged(true);
        }

        return true;
    }

    @Override
    public boolean hide() {
        if (!mIsShowing) {
            return false;
        }
        mWindowManager.removeViewImmediate(mKeyButtonContainer);
        mIsShowing = false;
        if (mUpdatesCallback != null) {
            mUpdatesCallback.onVisibilityChanged(false);
        }
        return true;
    }

    @Override
    public boolean isVisible() {
        return mIsShowing;
    }

    @Override
    public void updateIcon(int lightIconColor, int darkIconColor) {
        Color ovalBackgroundColor = Color.valueOf(Color.red(darkIconColor),
                Color.green(darkIconColor), Color.blue(darkIconColor), BACKGROUND_ALPHA);
        mKeyButtonDrawable = KeyButtonDrawable.create(mRotationButtonController.getContext(),
                lightIconColor, darkIconColor, mRotationButtonController.getIconResId(),
                false /* shadow */, ovalBackgroundColor);
        mKeyButtonView.setImageDrawable(mKeyButtonDrawable);
    }

    @Override
    public void setOnClickListener(View.OnClickListener onClickListener) {
        mKeyButtonView.setOnClickListener(onClickListener);
    }

    @Override
    public void setOnHoverListener(View.OnHoverListener onHoverListener) {
        mKeyButtonView.setOnHoverListener(onHoverListener);
    }

    @Override
    public KeyButtonDrawable getImageDrawable() {
        return mKeyButtonDrawable;
    }

    @Override
    public void setDarkIntensity(float darkIntensity) {
        mKeyButtonView.setDarkIntensity(darkIntensity);
    }

    @Override
    public void setCanShowRotationButton(boolean canShow) {
        mCanShow = canShow;
        if (!mCanShow) {
            hide();
        }
    }

    public void onTaskbarStateChanged(boolean taskbarVisible, boolean taskbarStashed) {
        mIsTaskbarVisible = taskbarVisible;
        mIsTaskbarStashed = taskbarStashed;

        if (!mIsShowing) return;

        final Position newPosition = mPositionCalculator
                .calculatePosition(mDisplayRotation, mIsTaskbarVisible, mIsTaskbarStashed);

        if (newPosition.getTranslationX() != mPosition.getTranslationX()
                || newPosition.getTranslationY() != mPosition.getTranslationY()) {
            updateTranslation(newPosition, /* animate */ true);
            mPosition = newPosition;
        }
    }

    private void updateTranslation(Position position, boolean animate) {
        final int translationX = position.getTranslationX();
        final int translationY = position.getTranslationY();

        if (animate) {
            mKeyButtonView
                    .animate()
                    .translationX(translationX)
                    .translationY(translationY)
                    .setDuration(MARGIN_ANIMATION_DURATION_MILLIS)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (mUpdatesCallback != null && mIsShowing) {
                            mUpdatesCallback.onPositionChanged();
                        }
                    })
                    .start();
        } else {
            mKeyButtonView.setTranslationX(translationX);
            mKeyButtonView.setTranslationY(translationY);
        }
    }
}
