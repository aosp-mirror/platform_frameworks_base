/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.util.MathUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;

/**
 * UDFPS animations that should be shown when authenticating on keyguard.
 */
public class UdfpsAnimationKeyguard extends UdfpsAnimation implements DozeReceiver,
        StatusBarStateController.StateListener {

    private static final String TAG = "UdfpsAnimationKeyguard";

    @NonNull private final Context mContext;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;

    // AOD anti-burn-in offsets
    private float mInterpolatedDarkAmount;
    private float mBurnInOffsetX;
    private float mBurnInOffsetY;

    UdfpsAnimationKeyguard(@NonNull Context context,
            @NonNull StatusBarStateController statusBarStateController) {
        super(context);
        mContext = context;
        mStatusBarStateController = statusBarStateController;

        mMaxBurnInOffsetX = context.getResources()
                .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x);
        mMaxBurnInOffsetY = context.getResources()
                .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);

        statusBarStateController.addCallback(this);
    }

    private void updateAodPositionAndColor() {
        mBurnInOffsetX = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                        - mMaxBurnInOffsetX,
                mInterpolatedDarkAmount);
        mBurnInOffsetY = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                        - mMaxBurnInOffsetY,
                mInterpolatedDarkAmount);
        updateColor();
        postInvalidateView();
    }

    @Override
    public void dozeTimeTick() {
        updateAodPositionAndColor();
    }

    @Override
    public void onDozeAmountChanged(float linear, float eased) {
        mInterpolatedDarkAmount = eased;
        updateAodPositionAndColor();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (isIlluminationShowing()) {
            return;
        }

        canvas.save();
        canvas.translate(mBurnInOffsetX, mBurnInOffsetY);
        mFingerprintDrawable.draw(canvas);
        canvas.restore();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return 0;
    }

    @Override
    protected void updateColor() {
        final int lockScreenIconColor = Utils.getColorAttrDefaultColor(mContext,
                R.attr.wallpaperTextColor);
        final int ambientDisplayIconColor = Color.WHITE;
        mFingerprintDrawable.setTint(ColorUtils.blendARGB(lockScreenIconColor,
                ambientDisplayIconColor, mInterpolatedDarkAmount));
    }

    @Override
    protected void onDestroy() {
        mStatusBarStateController.removeCallback(this);
    }
}
