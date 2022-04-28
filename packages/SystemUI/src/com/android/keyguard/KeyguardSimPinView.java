/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import androidx.core.graphics.drawable.DrawableCompat;

import com.android.systemui.R;

/**
 * Displays a PIN pad for unlocking.
 */
public class KeyguardSimPinView extends KeyguardPinBasedInputView {
    private ImageView mSimImageView;
    public static final String TAG = "KeyguardSimPinView";

    public KeyguardSimPinView(Context context) {
        this(context, null);
    }

    public KeyguardSimPinView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setEsimLocked(boolean locked, int subscriptionId) {
        KeyguardEsimArea esimButton = findViewById(R.id.keyguard_esim_area);
        esimButton.setSubscriptionId(subscriptionId);
        esimButton.setVisibility(locked ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resetState();
    }

    @Override
    protected int getPromptReasonStringRes(int reason) {
        // No message on SIM Pin
        return 0;
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.simPinEntry;
    }

    @Override
    protected void onFinishInflate() {
        mSimImageView = findViewById(R.id.keyguard_sim);
        super.onFinishInflate();

        if (mEcaView instanceof EmergencyCarrierArea) {
            ((EmergencyCarrierArea) mEcaView).setCarrierTextVisible(true);
        }
    }

    @Override
    public void startAppearAnimation() {
        // noop.
    }

    @Override
    public CharSequence getTitle() {
        return getContext().getString(
                com.android.internal.R.string.keyguard_accessibility_sim_pin_unlock);
    }

    @Override
    public void reloadColors() {
        super.reloadColors();

        int[] customAttrs = {android.R.attr.textColorSecondary};
        TypedArray a = getContext().obtainStyledAttributes(customAttrs);
        int imageColor = a.getColor(0, 0);
        a.recycle();
        Drawable wrappedDrawable = DrawableCompat.wrap(mSimImageView.getDrawable());
        DrawableCompat.setTint(wrappedDrawable, imageColor);
    }
}

