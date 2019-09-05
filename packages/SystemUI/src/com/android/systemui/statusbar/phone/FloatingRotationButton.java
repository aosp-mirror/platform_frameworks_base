/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.KeyButtonView;

/** Containing logic for the rotation button on the physical left bottom corner of the screen. */
public class FloatingRotationButton implements RotationButton {

    private static final float BACKGROUND_ALPHA = 0.92f;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final KeyButtonView mKeyButtonView;
    private final int mDiameter;
    private final int mMargin;
    private KeyButtonDrawable mKeyButtonDrawable;
    private boolean mIsShowing;
    private boolean mCanShow = true;

    private RotationButtonController mRotationButtonController;

    FloatingRotationButton(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mKeyButtonView = (KeyButtonView) LayoutInflater.from(mContext).inflate(
                R.layout.rotate_suggestion, null);
        mKeyButtonView.setVisibility(View.VISIBLE);

        Resources res = mContext.getResources();
        mDiameter = res.getDimensionPixelSize(R.dimen.floating_rotation_button_diameter);
        mMargin = Math.max(res.getDimensionPixelSize(R.dimen.floating_rotation_button_min_margin),
                res.getDimensionPixelSize(R.dimen.rounded_corner_content_padding));
    }

    @Override
    public void setRotationButtonController(RotationButtonController rotationButtonController) {
        mRotationButtonController = rotationButtonController;
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
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(mDiameter, mDiameter,
                mMargin, mMargin, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, flags,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("FloatingRotationButton");
        switch (mWindowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_0:
                lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
                break;
            case Surface.ROTATION_90:
                lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                break;
            case Surface.ROTATION_180:
                lp.gravity = Gravity.TOP | Gravity.RIGHT;
                break;
            case Surface.ROTATION_270:
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                break;
            default:
                break;
        }
        updateIcon();
        mWindowManager.addView(mKeyButtonView, lp);
        if (mKeyButtonDrawable != null && mKeyButtonDrawable.canAnimate()) {
            mKeyButtonDrawable.resetAnimation();
            mKeyButtonDrawable.startAnimation();
        }
        return true;
    }

    @Override
    public boolean hide() {
        if (!mIsShowing) {
            return false;
        }
        mWindowManager.removeViewImmediate(mKeyButtonView);
        mIsShowing = false;
        return true;
    }

    @Override
    public boolean isVisible() {
        return mIsShowing;
    }

    @Override
    public void updateIcon() {
        if (!mIsShowing) {
            return;
        }
        mKeyButtonDrawable = getImageDrawable();
        mKeyButtonView.setImageDrawable(mKeyButtonDrawable);
        mKeyButtonDrawable.setCallback(mKeyButtonView);
        if (mKeyButtonDrawable != null && mKeyButtonDrawable.canAnimate()) {
            mKeyButtonDrawable.resetAnimation();
            mKeyButtonDrawable.startAnimation();
        }
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
        Context context = new ContextThemeWrapper(mContext.getApplicationContext(),
                mRotationButtonController.getStyleRes());
        final int dualToneDarkTheme = Utils.getThemeAttr(context, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(context, R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(context, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(context, dualToneDarkTheme);
        @ColorInt int darkColor = Utils.getColorAttrDefaultColor(darkContext,
                R.attr.singleToneColor);
        Color ovalBackgroundColor = Color.valueOf(Color.red(darkColor), Color.green(darkColor),
                Color.blue(darkColor), BACKGROUND_ALPHA);

        return KeyButtonDrawable.create(lightContext,
                Utils.getColorAttrDefaultColor(lightContext, R.attr.singleToneColor), darkColor,
                R.drawable.ic_sysbar_rotate_button, false /* shadow */, ovalBackgroundColor);
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
}
