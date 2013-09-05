/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

public class BatteryMeterView extends View implements DemoMode {
    public static final String TAG = BatteryMeterView.class.getSimpleName();
    public static final String ACTION_LEVEL_TEST = "com.android.systemui.BATTERY_LEVEL_TEST";

    public static final boolean ENABLE_PERCENT = true;
    public static final boolean SINGLE_DIGIT_PERCENT = false;
    public static final boolean SHOW_100_PERCENT = false;

    public static final int FULL = 96;
    public static final int EMPTY = 4;

    int[] mColors;

    boolean mShowPercent = true;
    Paint mFramePaint, mBatteryPaint, mWarningTextPaint, mTextPaint;
    int mButtonHeight;
    private float mTextHeight, mWarningTextHeight;
    Drawable mLightning;

    private int mHeight;
    private int mWidth;
    private String mWarningString;
    private final int mChargeColor;

    private class BatteryTracker extends BroadcastReceiver {
        // current battery status
        int level;
        String percentStr;
        int plugType;
        boolean plugged;
        int health;
        int status;
        String technology;
        int voltage;
        int temperature;
        boolean testmode = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                if (testmode && ! intent.getBooleanExtra("testmode", false)) return;

                level = (int)(100f
                        * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                        / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));

                plugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                plugged = plugType != 0;
                health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH,
                        BatteryManager.BATTERY_HEALTH_UNKNOWN);
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);

                setContentDescription(
                        context.getString(R.string.accessibility_battery_level, level));
                postInvalidate();
            } else if (action.equals(ACTION_LEVEL_TEST)) {
                testmode = true;
                post(new Runnable() {
                    int curLevel = 0;
                    int incr = 1;
                    int saveLevel = level;
                    int savePlugged = plugType;
                    Intent dummy = new Intent(Intent.ACTION_BATTERY_CHANGED);
                    @Override
                    public void run() {
                        if (curLevel < 0) {
                            testmode = false;
                            dummy.putExtra("level", saveLevel);
                            dummy.putExtra("plugged", savePlugged);
                            dummy.putExtra("testmode", false);
                        } else {
                            dummy.putExtra("level", curLevel);
                            dummy.putExtra("plugged", incr > 0 ? BatteryManager.BATTERY_PLUGGED_AC : 0);
                            dummy.putExtra("testmode", true);
                        }
                        getContext().sendBroadcast(dummy);

                        if (!testmode) return;

                        curLevel += incr;
                        if (curLevel == 100) {
                            incr *= -1;
                        }
                        postDelayed(this, 200);
                    }
                });
            }
        }
    }

    BatteryTracker mTracker = new BatteryTracker();

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ACTION_LEVEL_TEST);
        getContext().registerReceiver(mTracker, filter);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        getContext().unregisterReceiver(mTracker);
    }

    public BatteryMeterView(Context context) {
        this(context, null, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources res = context.getResources();
        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        mColors = new int[2*N];
        for (int i=0; i<N; i++) {
            mColors[2*i] = levels.getInt(i, 0);
            mColors[2*i+1] = colors.getColor(i, 0);
        }

        mShowPercent = ENABLE_PERCENT && 0 != Settings.System.getInt(
                context.getContentResolver(), "status_bar_show_battery_percent", 0);

        mWarningString = context.getString(R.string.battery_meter_very_low_overlay_symbol);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(res.getColor(R.color.batterymeter_frame_color));
        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setColor(0xFF00FF00); // will be replaced by something from mColors

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(0xFFFFFFFF);
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mWarningTextPaint.setColor(mColors[1]);
        font = Typeface.create("sans-serif", Typeface.BOLD);
        mWarningTextPaint.setTypeface(font);
        mWarningTextPaint.setTextAlign(Paint.Align.CENTER);

        mLightning = getResources().getDrawable(R.drawable.lightning);
        mChargeColor = getResources().getColor(R.color.batterymeter_charge_color);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mHeight = h;
        mWidth = w;
        mWarningTextPaint.setTextSize(h * 0.75f);
        mWarningTextHeight = -mWarningTextPaint.getFontMetrics().ascent;
    }

    private int getColorForLevel(int percent) {
        int thresh, color = 0;
        for (int i=0; i<mColors.length; i+=2) {
            thresh = mColors[i];
            color = mColors[i+1];
            if (percent <= thresh) return color;
        }
        return color;
    }

    @Override
    public void draw(Canvas c) {
        BatteryTracker tracker = mDemoMode ? mDemoTracker : mTracker;
        final int level = tracker.level;
        float drawFrac = (float) level / 100f;
        final int pt = getPaddingTop();
        final int pl = getPaddingLeft();
        final int pr = getPaddingRight();
        final int pb = getPaddingBottom();
        int height = mHeight - pt - pb;
        int width = mWidth - pl - pr;

        mButtonHeight = (int) (height * 0.12f);

        final RectF frame = new RectF(0, 0, width, height);
        frame.offset(pl, pt);

        // Log.v("BatteryGauge", String.format("canvas: %dx%d frame: %s",
        // c.getWidth(), c.getHeight(), frame.toString()));

        final RectF buttonframe = new RectF(
                frame.left + width * 0.25f,
                frame.top,
                frame.right - width * 0.25f,
                frame.top + mButtonHeight);

        frame.top += mButtonHeight;

        // first, draw the battery shape
        c.drawRect(frame, mFramePaint);

        // fill 'er up
        final int pct = tracker.level;
        final int color = tracker.plugged ? mChargeColor : getColorForLevel(pct);
        mBatteryPaint.setColor(color);

        if (level >= FULL) {
            drawFrac = 1f;
        } else if (level <= EMPTY) {
            drawFrac = 0f;
        }

        c.drawRect(buttonframe,
                drawFrac == 1f ? mBatteryPaint : mFramePaint);

        RectF clip = new RectF(frame);
        clip.top += (frame.height() * (1f - drawFrac));

        c.save(Canvas.CLIP_SAVE_FLAG);
        c.clipRect(clip);
        c.drawRect(frame, mBatteryPaint);
        c.restore();

        if (level <= EMPTY) {
            final float x = mWidth * 0.5f;
            final float y = (mHeight + mWarningTextHeight) * 0.48f;
            c.drawText(mWarningString, x, y, mWarningTextPaint);
        } else if (tracker.plugged) {
            final Rect r = new Rect(
                    (int)frame.left + width / 4,  (int)frame.top + height / 5,
                    (int)frame.right - width / 4, (int)frame.bottom - height / 6);
            mLightning.setBounds(r);
            mLightning.draw(c);
        } else if (mShowPercent && !(tracker.level == 100 && !SHOW_100_PERCENT)) {
            mTextPaint.setTextSize(height *
                    (SINGLE_DIGIT_PERCENT ? 0.75f
                            : (tracker.level == 100 ? 0.38f : 0.5f)));
            mTextHeight = -mTextPaint.getFontMetrics().ascent;

            final String str = String.valueOf(SINGLE_DIGIT_PERCENT ? (pct/10) : pct);
            final float x = mWidth * 0.5f;
            final float y = (mHeight + mTextHeight) * 0.47f;
            c.drawText(str,
                    x,
                    y,
                    mTextPaint);

//            Paint pt = new Paint();
//            pt.setStrokeWidth(1f);
//            pt.setStyle(Paint.Style.STROKE);
//            pt.setColor(0xFFFF0000);
//            c.drawRect(x, y-mTextHeight, x+tw, y, pt);
//
//            Slog.v(TAG, "tw=" + tw + " th=" + mTextHeight);
//
//            pt.setColor(0xFFFF00FF);
//            c.drawRect(1, 1, mWidth, mHeight, pt);
        }
    }

    private boolean mDemoMode;
    private BatteryTracker mDemoTracker = new BatteryTracker();

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
            mDemoTracker.level = mTracker.level;
            mDemoTracker.plugged = mTracker.plugged;
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            postInvalidate();
        } else if (mDemoMode && command.equals(COMMAND_BATTERY)) {
           String level = args.getString("level");
           String plugged = args.getString("plugged");
           if (level != null) {
               mDemoTracker.level = Math.min(Math.max(Integer.parseInt(level), 0), 100);
           }
           if (plugged != null) {
               mDemoTracker.plugged = Boolean.parseBoolean(plugged);
           }
           postInvalidate();
        }
    }
}
