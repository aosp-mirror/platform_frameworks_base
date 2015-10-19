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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Environment;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import java.io.File;

public class MinitBattery extends ImageView {
    public interface OnMinitBatterySetupListener {
        public boolean onSetupComplete(boolean completedSuccessfully);
    }
    private class ResourceManager {
        private Context mResourceContext;
        private Resources mRes;
        private boolean mResExsist = false;

        public ResourceManager(Context context) {

            try {
                mResourceContext = context.createPackageContext(
                        "com.three.minit.batteryresources", Context.CONTEXT_IGNORE_SECURITY);
                mRes = mResourceContext.getResources();
                mResExsist = true;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }

        @SuppressWarnings("deprecation")
        public Drawable getDrawable(String name) {
            return mRes.getDrawable(getResourceId(name, "drawable"));
        }

        public int getResourceId(String name, String type) {

            return mRes.getIdentifier(name, type,
                    mResourceContext.getPackageName());
        }

        public boolean ResourcesExsist() {
            return mResExsist;
        }

    }
    private String mBatteryIconsLoaction;
    private String mDownloadBatteryIconsLoaction;
    private File mFile;
    private int mLevel;
    private int mStatus;
    private Paint mPaint;
    private int mTextColor = Color.WHITE;
    private int mTextSize = 30;
    private int mBatteryColor = Color.WHITE;
    private int mBatteryMidColor = 0xFFFFC400;
    private int mBatteryLowColor = 0xFFDE2904;

    private int mMidLevel = 50, mLowLevel = 20;

    private boolean mSetup = false;
    private OnMinitBatterySetupListener mListener;
    private Typeface mTypeface;
    private boolean mIsColorable = false;
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
                mLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                mStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
            } else if (intent.getAction().equals(
                    "com.three.minit.BATTERY_TYPE_CHANGED")) {
            }

            getSettings();
            updateImageView();
        }
    };
    private ResourceManager mRM;
    private int mChargeAnim = 0;
    private int mBatteryType = 8;

    private int mWorkingType = 0;

    public MinitBattery(Context context) {
        super(context);
        init(context);
    }

    public MinitBattery(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MinitBattery(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void applyColorFilter() {
        if (mIsColorable) {
            if (mLevel >= mMidLevel && mLevel >= mLowLevel) {
                setColorFilter(mBatteryColor, PorterDuff.Mode.MULTIPLY);
            } else if (mLevel < mMidLevel && mLevel > mLowLevel) {
                setColorFilter(mBatteryMidColor, PorterDuff.Mode.MULTIPLY);
            } else if (mLevel < mLowLevel) {
                setColorFilter(mBatteryLowColor, PorterDuff.Mode.MULTIPLY);
            }
        } else {
            setColorFilter(-1, PorterDuff.Mode.MULTIPLY);
        }
    }

    private AnimationDrawable getChargingAnimation(int level) {
        AnimationDrawable ad = new AnimationDrawable();

        switch (mChargeAnim) {
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

        switch (mWorkingType) {
            case 0:// Offline
                drawable = getDefaultBattery(level, true);
                break;

            case 1:// Downloaded
                File f = new File(mDownloadBatteryIconsLoaction
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
                File fi = new File(mBatteryIconsLoaction,
                        "stat_sys_battery_charge_anim" + String.valueOf(level)
                                + ".png"
                        );

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
        Drawable d = null;

        if (charge)
            d = mRM.getDrawable("battery_" + String.valueOf(mBatteryType)
                    + "_charge_anim" + String.valueOf(level));
        else
            d = mRM.getDrawable("battery_" + String.valueOf(mBatteryType)
                    + "_" + String.valueOf(level));


        setBatterySize(null, d);
        return d;

    }

    private Drawable getNormalDrawable(int level) {
        Drawable drawable = null;

        switch (mWorkingType) {
            case 0:// Offline
                drawable = getDefaultBattery(level, false);
                break;

            case 1:// Downloaded
                File f = new File(mDownloadBatteryIconsLoaction
                        + "stat_sys_battery_" + String.valueOf(level) + ".png");

                if (f.exists()) {
                    drawable = Drawable.createFromPath(f.getAbsolutePath());
                    setBatterySize(f, null);
                } else {
                    drawable = getDefaultBattery(level, false);
                }
                break;

            case 2:// Folder
                File fi = new File(mBatteryIconsLoaction, "stat_sys_battery_"
                        + String.valueOf(level) + ".png");

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

    private String getSaveLocation(Context context) {
        String t = Settings.System.getString(context.getContentResolver(), "save_loc");

        if (t != null) {
            return t + "/3Minit Downloads/BatteryIcons/";
        } else {
            return Environment.getExternalStorageDirectory().getPath()
                    + "/3Minit Downloads/BatteryIcons/";
        }
    }

    private void getSettings() {
        ContentResolver cr = getContext().getContentResolver();
        mDownloadBatteryIconsLoaction = getSaveLocation(getContext());
        mChargeAnim = Settings.System.getInt(cr, "minit_anim_type", 0);
        mBatteryType = Settings.System.getInt(cr, "minit_battery_type", 8);
        mWorkingType = Settings.System.getInt(cr, "minit_working_type", 0);
        mIsColorable = Settings.System.getInt(cr, "minit_colorable", 0) == 1;
        mBatteryColor = Settings.System.getInt(cr, "minit_battery_color", mBatteryColor);
        mBatteryMidColor = Settings.System.getInt(cr, "minit_battery_mid_color", mBatteryMidColor);
        mBatteryLowColor = Settings.System.getInt(cr, "minit_battery_low_color", mBatteryLowColor);
        mMidLevel = Settings.System.getInt(cr, "minit_mid_level", mMidLevel);
        mLowLevel = Settings.System.getInt(cr, "minit_low_level", mLowLevel);
        mTextSize = Settings.System.getInt(cr, "minit_battery_text_size", 30);
        mTextColor = Settings.System.getInt(cr, "minit_battery_text_color", mTextColor);

        mPaint.setColor(mTextColor);
        mPaint.setTextSize(mTextSize);

        invalidate();

        if (Settings.System.getInt(cr, "minit_battery_visible", 1) == 1)
            setVisibility(View.VISIBLE);
        else
            setVisibility(View.GONE);
    }

    private void init(Context context) {
        mLevel = 0;
        mStatus = 0;

        try {

            mRM = new ResourceManager(context);

            if (!mRM.ResourcesExsist()) {
                if (mListener != null)
                    mListener.onSetupComplete(false);

                mSetup = false;
                setVisibility(View.GONE);
                return;
            } else {
                if (mListener != null)
                    mListener.onSetupComplete(true);
                mSetup = true;
            }

            mBatteryIconsLoaction = Environment.getExternalStorageDirectory()
                    .getAbsolutePath() + File.separator + "3MinitBatteryIcons";
            mFile = new File(mBatteryIconsLoaction);
            mFile.mkdirs();

            mDownloadBatteryIconsLoaction = getSaveLocation(context);

            mPaint = new Paint();
            mPaint.setColor(mTextColor);
            mPaint.setTextSize(mTextSize);
            mPaint.setTypeface(mTypeface);
            mPaint.setTextAlign(Paint.Align.CENTER);
            mPaint.setStyle(Paint.Style.FILL);

            getSettings();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public boolean isSetup() {
        return mSetup;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mSetup) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction("com.three.minit.BATTERY_TYPE_CHANGED");
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            getContext().registerReceiver(mReceiver, filter);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getContext().unregisterReceiver(mReceiver);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int xPos = (canvas.getWidth() / 2);
        int yPos = (int) ((canvas.getHeight() / 2) - ((mPaint.descent() + mPaint.ascent()) / 2));

        if (mIsColorable && mPaint != null) {
            canvas.drawText(String.valueOf(mLevel), xPos, yPos, mPaint);
        }
    }

    private void setBatterySize(File file, Drawable drawable) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap b = null;

        if (file != null) {
            b = BitmapFactory.decodeFile(file.getAbsolutePath(),
                    options);
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

    public void setOnMinitBatterySetupListener(OnMinitBatterySetupListener listener) {
        mListener = listener;
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
