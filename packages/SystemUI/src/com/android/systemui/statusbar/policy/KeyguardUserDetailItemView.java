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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.android.app.animation.Interpolators;
import com.android.keyguard.KeyguardConstants;
import com.android.systemui.qs.tiles.UserDetailItemView;
import com.android.systemui.res.R;

/**
 * Displays a user on the keyguard user switcher.
 */
public class KeyguardUserDetailItemView extends UserDetailItemView {

    private static final String TAG = "KeyguardUserDetailItemView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private static final int ANIMATION_DURATION_FADE_NAME = 240;

    private float mDarkAmount;
    private int mTextColor;

    public KeyguardUserDetailItemView(Context context) {
        this(context, null);
    }

    public KeyguardUserDetailItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardUserDetailItemView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public KeyguardUserDetailItemView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected int getFontSizeDimen() {
        return R.dimen.kg_user_switcher_text_size;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTextColor = mName.getCurrentTextColor();
        updateDark();
    }

    /**
     * Update visibility of this view.
     *
     * @param showItem If true, this item is visible on the screen to the user. Generally this
     *                 means that the item would be clickable. If false, item visibility will be
     *                 set to GONE and hidden entirely.
     * @param showTextName Whether or not the name should be shown next to the icon. If false,
     *                     only the icon is shown.
     * @param animate Whether the transition should be animated. Note, this only applies to
     *                animating the text name. The item itself will not animate (i.e. fade in/out).
     *                Instead, we delegate that to the parent view.
     */
    void updateVisibilities(boolean showItem, boolean showTextName, boolean animate) {
        if (DEBUG) {
            Log.d(TAG, String.format("updateVisibilities itemIsShown=%b nameIsShown=%b animate=%b",
                    showItem, showTextName, animate));
        }

        getBackground().setAlpha((showItem && showTextName) ? 255 : 0);

        if (showItem) {
            if (showTextName) {
                mName.setVisibility(View.VISIBLE);
                if (animate) {
                    mName.setAlpha(0f);
                    mName.animate()
                            .alpha(1f)
                            .setDuration(ANIMATION_DURATION_FADE_NAME)
                            .setInterpolator(Interpolators.ALPHA_IN);
                } else {
                    mName.setAlpha(1f);
                }
            } else {
                if (animate) {
                    mName.setVisibility(View.VISIBLE);
                    mName.setAlpha(1f);
                    mName.animate()
                            .alpha(0f)
                            .setDuration(ANIMATION_DURATION_FADE_NAME)
                            .setInterpolator(Interpolators.ALPHA_OUT)
                            .withEndAction(() -> {
                                mName.setVisibility(View.GONE);
                                mName.setAlpha(1f);
                            });
                } else {
                    mName.setVisibility(View.GONE);
                    mName.setAlpha(1f);
                }
            }
            setVisibility(View.VISIBLE);
            setAlpha(1f);
        } else {
            // If item isn't shown, don't animate. The parent class will animate the view instead
            setVisibility(View.GONE);
            setAlpha(1f);
            mName.setVisibility(showTextName ? View.VISIBLE : View.GONE);
            mName.setAlpha(1f);
        }
    }

    /**
     * Set the amount (ratio) that the device has transitioned to doze.
     *
     * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
     */
    public void setDarkAmount(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        updateDark();
    }

    private void updateDark() {
        final int blendedTextColor = ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
        mName.setTextColor(blendedTextColor);
    }
}
