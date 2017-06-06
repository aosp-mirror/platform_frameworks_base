/*Copyright 2014 Gary Harrington

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import java.io.File;

import com.android.systemui.statusbar.policy.MinitBatteryController.MinitSettings;
import com.android.systemui.statusbar.policy.MinitBatteryController.MinitState;
import com.android.systemui.statusbar.policy.MinitBatteryController.ResourceManager;

public class MinitBattery extends ImageView {
    private static final String TAG = MinitBattery.class.getSimpleName();
    private static final boolean DEBUG = true;

    public interface OnMinitBatteryErrorListener {
        public void onError();
    }

    private ResourceManager mRM;
    private int mLevel;
    private int mStatus;
    private Paint mPaint;
    private Typeface mTypeface;
    private MinitSettings mSettings;

    private OnMinitBatteryErrorListener mListener;

    public MinitBattery(Context context) {
        super(context);
    }

    public MinitBattery(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MinitBattery(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    private void applyColorFilter() {
        if (mSettings.mIsColorable) {
            if (mLevel >= mSettings.mMidLevel && mLevel >= mSettings.mLowLevel) {
                setColorFilter(mSettings.mBatteryColor,
                        PorterDuff.Mode.MULTIPLY);
            } else if (mLevel < mSettings.mMidLevel
                    && mLevel > mSettings.mLowLevel) {
                setColorFilter(mSettings.mBatteryMidColor,
                        PorterDuff.Mode.MULTIPLY);
            } else if (mLevel < mSettings.mLowLevel) {
                setColorFilter(mSettings.mBatteryLowColor,
                        PorterDuff.Mode.MULTIPLY);
            }
        } else {
            setColorFilter(-1, PorterDuff.Mode.MULTIPLY);
        }
    }

    private AnimationDrawable getChargingAnimation(int level) {
        AnimationDrawable ad = new AnimationDrawable();

        switch (mSettings.mChargeAnim) {
            case 0:
                ad.addFrame(getChargingDrawable(level), 1500);
                ad.addFrame(getNormalDrawable(level), 500);
                break;
            case 1:
                for (int i = 1; i < 100; i++) {
                    ad.addFrame(getChargingDrawable(i), 20);
                }
                ad.addFrame(getChargingDrawable(100), 500);
                ad.addFrame(getNormalDrawable(level), 2500);
                break;
            case 2:
                ad.addFrame(getNormalDrawable(level), 2000);
                for (int i = level; i < 101; i++) {
                    ad.addFrame(getChargingDrawable(i), 80);
                }
                break;
            case 3:
                int l = level;
                if (l == 0)
                    l = 1;

                for (int i = 0; i < l; i++) {
                    ad.addFrame(getChargingDrawable(i), 20);
                }
                ad.addFrame(getNormalDrawable(level), 2500);
                break;
            case 4:
                for (int i = 0; i < 101; i++) {
                    ad.addFrame(getChargingDrawable(i), 20);
                }
                ad.addFrame(getNormalDrawable(level), 250);
                ad.addFrame(getChargingDrawable(level), 100);
                ad.addFrame(getNormalDrawable(level), 250);
                ad.addFrame(getChargingDrawable(level), 100);
                ad.addFrame(getNormalDrawable(level), 2000);
                break;
        }
        return ad;
    }

    private Drawable getChargingDrawable(int level) {
        Drawable drawable = null;

        switch (mSettings.mWorkingType) {
            case 0:// Offline
                drawable = getDefaultBattery(level, true);
                break;

            case 1:// Downloaded
                File f = new File(mSettings.mDownloadBatteryIconsLoaction
                        + "stat_sys_battery_charge_" + String.valueOf(level)
                        + ".png");

                if (f.exists()) {
                    drawable = Drawable.createFromPath(f.getAbsolutePath());
                    setBatterySize(f, null);
                } else {
                    drawable = getDefaultBattery(level, true);
                }
                break;

            case 2:// Folder
                File fi = new File(MinitSettings.mBatteryIconsLocation,
                        "stat_sys_battery_charge_anim" + String.valueOf(level)
                                + ".png");

                if (fi.exists()) {
                    drawable = Drawable.createFromPath(fi.getAbsolutePath());
                    setBatterySize(fi, null);
                } else {
                    drawable = getDefaultBattery(level, true);
                }
                break;
        }

        return drawable;
    }

    private Drawable getDefaultBattery(int level, boolean charge) {
        if (!mRM.resourcesExists()) {
            dispatchError();
            // we've done all we possibly can. This should be
            // impossible but let's catch it and handle it gracefully
            throw new Resources.NotFoundException();
        }
        Drawable d = null;

        if (charge)
            d = mRM.getDrawable("battery_"
                    + String.valueOf(mSettings.mBatteryType) + "_charge_anim"
                    + String.valueOf(level));
        else
            d = mRM.getDrawable("battery_"
                    + String.valueOf(mSettings.mBatteryType) + "_"
                    + String.valueOf(level));

        setBatterySize(null, d);
        return d;
    }

    private Drawable getNormalDrawable(int level) {
        Drawable drawable = null;

        switch (mSettings.mWorkingType) {
            case 0:// Offline
                drawable = getDefaultBattery(level, false);
                break;

            case 1:// Downloaded
                File f = new File(mSettings.mDownloadBatteryIconsLoaction
                        + "stat_sys_battery_" + String.valueOf(level) + ".png");

                if (f.exists()) {
                    drawable = Drawable.createFromPath(f.getAbsolutePath());
                    setBatterySize(f, null);
                } else {
                    drawable = getDefaultBattery(level, false);
                }
                break;

            case 2:// Folder
                File fi = new File(MinitSettings.mBatteryIconsLocation,
                        "stat_sys_battery_" + String.valueOf(level) + ".png");

                if (fi.exists()) {
                    drawable = Drawable.createFromPath(fi.getAbsolutePath());
                    setBatterySize(fi, null);
                } else {
                    drawable = getDefaultBattery(level, false);
                }
                break;
        }

        return drawable;
    }

    public void updateSettings(MinitState state, MinitSettings settings) {
        mSettings = settings;
        mPaint.setColor(mSettings.mTextColor);
        mPaint.setTextSize(mSettings.mTextSize);

        if (state == MinitState.SETUP) {
            invalidate();
            setVisibility(mSettings.mVisible ? View.VISIBLE : View.GONE);
        } else {
            setVisibility(View.GONE);
        }
    }

    public void updateBattery(int status, int level) {
        mStatus = status;
        mLevel = level;
    }

    public void updateImage() {
        // if we fail here, it will be catastrophic
        // better to shut down and try again
        try {
            updateImageView();
        } catch (Exception e) {
            dispatchError();
        }
    }

    public void init(Context context, ResourceManager rm, MinitSettings settings) {
        mRM = rm;
        mSettings = settings;
        mPaint = new Paint();
        mPaint.setColor(mSettings.mTextColor);
        mPaint.setTextSize(mSettings.mTextSize);
        mPaint.setTypeface(mTypeface);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int xPos = (canvas.getWidth() / 2);
        int yPos = (int) ((canvas.getHeight() / 2) - ((mPaint.descent() + mPaint
                .ascent()) / 2));

        if (mSettings.mIsColorable && mPaint != null) {
            canvas.drawText(String.valueOf(mLevel), xPos, yPos, mPaint);
        }
    }

    private void setBatterySize(File file, Drawable drawable) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap b = null;

        if (file != null) {
            b = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }

        if (drawable != null) {
            b = ((BitmapDrawable) drawable).getBitmap();
        }

        int width = b.getWidth();
        int height = b.getHeight();

        int size = Settings.System.getInt(getContext().getContentResolver(),
                "minit_battery_size", 0);
        int t = 0;

        if (size < 0) {
            t = Integer.valueOf(String.valueOf(size).substring(1));
            getLayoutParams().height = (height - t);
            getLayoutParams().width = (width - t);
        } else if (size > 0) {
            t = size;
            getLayoutParams().height = (height + t);
            getLayoutParams().width = (width + t);
        } else {
            getLayoutParams().height = height;
            getLayoutParams().width = width;
        }

        setLayoutParams(this.getLayoutParams());
    }

    public void setOnMinitBatteryErrorListener(
            OnMinitBatteryErrorListener listener) {
        if (listener != null) {
            mListener = listener;
        }
    }

    public void removeOnMinitBatteryErrorListener(
            OnMinitBatteryErrorListener listener) {
        mListener = null;
    }

    private void dispatchError() {
        if (mListener != null) {
            mListener.onError();
        }
    }

    private void updateImageView() {
        switch (mStatus) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                AnimationDrawable ad = getChargingAnimation(mLevel);
                setImageDrawable(ad);
                ad.setOneShot(false);
                ad.start();
                break;
            case BatteryManager.BATTERY_STATUS_FULL:
                setImageDrawable(getNormalDrawable(100));
                break;
            default:
                setImageDrawable(getNormalDrawable(mLevel));
                break;
        }
        applyColorFilter();
    }
}
