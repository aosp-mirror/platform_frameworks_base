
/**
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

package com.android.systemui.biometrics;

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInProgressOffset;

import android.content.Context;
import android.content.res.Resources;
import android.content.pm.PackageManager;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayUtils;
import android.util.MathUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.tuner.TunerService;

public class UdfpsAnimation extends ImageView {

    private static final boolean DEBUG = true;
    private static final String LOG_TAG = "UdfpsAnimations";

    private static final String UDFPS_ANIM =
            "system:" + Settings.System.UDFPS_ANIM;
    private static final String UDFPS_ANIM_STYLE =
            "system:" + Settings.System.UDFPS_ANIM_STYLE;

    private Context mContext;
    private int mAnimationSize;
    private int mAnimationOffset;
    private AnimationDrawable recognizingAnim;

    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();
    private WindowManager mWindowManager;
    private AuthController mAuthController;

    private boolean mIsKeyguard;
    private boolean mEnabled;

    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;

    private int mSelectedAnim;
    private String[] mStyleNames;

    private final String mUdfpsAnimationPackage;

    private Resources mApkResources;

    public UdfpsAnimation(Context context, WindowManager windowManager,
                   AuthController authController) {
        super(context);
        mContext = context;

        mWindowManager = windowManager;
        mAuthController = authController;

        FingerprintSensorPropertiesInternal props = mAuthController.getUdfpsProps().get(0);
        final SensorLocationInternal location = props.getLocation();

        final float scaleFactor = DisplayUtils.getScaleFactor(mContext);

        mMaxBurnInOffsetX = (int) (context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x) * scaleFactor);
        mMaxBurnInOffsetY = (int) (context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y) * scaleFactor);

        mUdfpsAnimationPackage = "com.xtended.udfps.resources";

        mAnimationSize = mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_size);
        mAnimationOffset = (int) (mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_offset) * scaleFactor);

        mAnimParams.height = mAnimationSize;
        mAnimParams.width = mAnimationSize;

        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind Udfps icon
        mAnimParams.flags =  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;
        mAnimParams.y = (int) (props.getLocation().sensorLocationY * scaleFactor) - (int) (props.getLocation().sensorRadius * scaleFactor)
                - (mAnimationSize / 2) + mAnimationOffset;

        try {
            PackageManager pm = mContext.getPackageManager();
            mApkResources = pm.getResourcesForApplication(mUdfpsAnimationPackage);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        int res = mApkResources.getIdentifier("udfps_animation_styles",
                "array", mUdfpsAnimationPackage);
        mStyleNames = mApkResources.getStringArray(res);

        setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        TunerService.Tunable tunable = (key, newValue) -> {
            switch (key) {
                case UDFPS_ANIM:
                    mEnabled = TunerService.parseIntegerSwitch(newValue, false);
                    break;
                case UDFPS_ANIM_STYLE:
                    mSelectedAnim = newValue == null ? 0 : Integer.parseInt(newValue);
                    updateAnimationStyle(mStyleNames[mSelectedAnim]);
                    break;
            }
        };
        Dependency.get(TunerService.class).addTunable(tunable, UDFPS_ANIM, UDFPS_ANIM_STYLE);
    }

    private void updateAnimationStyle(String drawableName) {
        if (DEBUG) Log.i(LOG_TAG, "Updating animation style to:" + drawableName);
        int resId = mApkResources.getIdentifier(drawableName, "drawable", mUdfpsAnimationPackage);
        if (DEBUG) Log.i(LOG_TAG, "Got resource id: "+ resId +" from package" );
        setBackgroundDrawable(mApkResources.getDrawable(resId));
        recognizingAnim = (AnimationDrawable) getBackground();
    }

    public void show() {
        if (mIsKeyguard && mEnabled) {
            try {
                if (getWindowToken() == null) {
                    mWindowManager.addView(this, mAnimParams);
                } else {
                    mWindowManager.updateViewLayout(this, mAnimParams);
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
                return;
            }
            if (recognizingAnim != null) {
                recognizingAnim.start();
            }
        }
    }

    public void hide() {
        if (mIsKeyguard && mEnabled) {
            if (recognizingAnim != null) {
                clearAnimation();
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
            }
            if (getWindowToken() != null) {
                mWindowManager.removeView(this);
            }
        }
    }

    public void setIsKeyguard(boolean isKeyguard) {
        mIsKeyguard = isKeyguard;
    }

    public void dozeTimeTick() {
        float amt = Dependency.get(StatusBarStateController.class).getDozeAmount();

        float mBurnInOffsetX = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                - mMaxBurnInOffsetX, amt);
        float mBurnInOffsetY = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                - mMaxBurnInOffsetY, amt);
        setTranslationX(mBurnInOffsetX);
        setTranslationY(mBurnInOffsetY);
    }
}
