/*
* Copyright (C) 2019 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.app.WallpaperInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.palette.graphics.Palette;

import com.android.settingslib.Utils;
import com.android.systemui.R;

import java.util.Random;

public class NotificationLightsView extends RelativeLayout {
    private static final boolean DEBUG = false;
    private static final String TAG = "NotificationLightsView";
    private static final String CANCEL_NOTIFICATION_PULSE_ACTION = "cancel_notification_pulse";
    private ValueAnimator mLightAnimator;

    public NotificationLightsView(Context context) {
        this(context, null);
    }

    public NotificationLightsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationLightsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationLightsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void stopAnimateNotification() {
        if (mLightAnimator != null) {
            mLightAnimator.end();
            mLightAnimator = null;
        }
    }

    public void animateNotification() {
        animateNotificationWithColor(getNotificationLightsColor());
    }

    public int getNotificationLightsColor() {
        int color = 0xFFFFFFFF;
        int colorMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NOTIFICATION_PULSE_COLOR_MODE,
                0, UserHandle.USER_CURRENT);
        int customColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NOTIFICATION_PULSE_COLOR, 0xFFFFFFFF,
                UserHandle.USER_CURRENT);
        switch (colorMode) {
            case 1: // Wallpaper
                try {
                    WallpaperManager wallpaperManager = WallpaperManager.getInstance(mContext);
                    WallpaperInfo wallpaperInfo = wallpaperManager.getWallpaperInfo();
                    if (wallpaperInfo == null) { // if not a live wallpaper
                        Drawable wallpaperDrawable = wallpaperManager.getDrawable();
                        Bitmap bitmap = ((BitmapDrawable)wallpaperDrawable).getBitmap();
                        if (bitmap != null) { // if wallpaper is not blank
                            Palette p = Palette.from(bitmap).generate();
                            int wallColor = p.getDominantColor(color);
                            if (color != wallColor)
                                color = wallColor;
                        }
                    }
                } catch (Exception e) { /* nothing to do, will use fallback */ }
                break;
            case 2: // Accent
                color = Utils.getColorAccentDefaultColor(getContext());
                break;
            case 3: // Custom
                color = customColor;
                break;
            case 4:
                color = getRandomColor();
                break;
            default: // White
                color = 0xFFFFFFFF;
        }
        return color;
    }

    public void animateNotificationWithColor(int color) {
        ContentResolver resolver = mContext.getContentResolver();
        int duration = Settings.System.getIntForUser(resolver,
                Settings.System.NOTIFICATION_PULSE_DURATION, 2,
                UserHandle.USER_CURRENT) * 1000; // seconds to ms
        int repeats = Settings.System.getIntForUser(resolver,
                Settings.System.NOTIFICATION_PULSE_REPEATS, 0,
                UserHandle.USER_CURRENT);
        boolean directionIsRestart = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.AMBIENT_LIGHT_REPEAT_DIRECTION, 0,
                UserHandle.USER_CURRENT) != 1;
        int style = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.AMBIENT_LIGHT_LAYOUT, 0,
                UserHandle.USER_CURRENT);
        int width = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PULSE_AMBIENT_LIGHT_WIDTH, 125,
                UserHandle.USER_CURRENT);
        int layoutStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.PULSE_LIGHT_LAYOUT_STYLE, 0,
                UserHandle.USER_CURRENT);

        ImageView leftViewFaded = (ImageView) findViewById(R.id.notification_animation_left_faded);
        ImageView topViewFaded = (ImageView) findViewById(R.id.notification_animation_top_faded);
        ImageView rightViewFaded = (ImageView) findViewById(R.id.notification_animation_right_faded);
        ImageView bottomViewFaded = (ImageView) findViewById(R.id.notification_animation_bottom_faded);
        ImageView leftViewSolid = (ImageView) findViewById(R.id.notification_animation_left_solid);
        ImageView topViewSolid = (ImageView) findViewById(R.id.notification_animation_top_solid);
        ImageView rightViewSolid = (ImageView) findViewById(R.id.notification_animation_right_solid);
        ImageView bottomViewSolid = (ImageView) findViewById(R.id.notification_animation_bottom_solid);
        leftViewFaded.setColorFilter(color);
        topViewFaded.setColorFilter(color);
        rightViewFaded.setColorFilter(color);
        bottomViewFaded.setColorFilter(color);
        leftViewFaded.setVisibility(style == 0 && layoutStyle != 1 ? View.VISIBLE : View.GONE);
        topViewFaded.setVisibility(style == 0 && layoutStyle != 2 ? View.VISIBLE : View.GONE);
        rightViewFaded.setVisibility(style == 0 && layoutStyle != 1 ? View.VISIBLE : View.GONE);
        bottomViewFaded.setVisibility(style == 0 && layoutStyle != 2 ? View.VISIBLE : View.GONE);
        leftViewSolid.setColorFilter(color);
        topViewSolid.setColorFilter(color);
        rightViewSolid.setColorFilter(color);
        bottomViewSolid.setColorFilter(color);
        leftViewSolid.setVisibility(style == 1 && layoutStyle != 1 ? View.VISIBLE : View.GONE);
        topViewSolid.setVisibility(style == 1 && layoutStyle != 2 ? View.VISIBLE : View.GONE);
        rightViewSolid.setVisibility(style == 1 && layoutStyle != 1 ? View.VISIBLE : View.GONE);
        bottomViewSolid.setVisibility(style == 1 && layoutStyle != 2 ? View.VISIBLE : View.GONE);
        if (leftViewSolid != null && rightViewSolid != null) {
            leftViewSolid.getLayoutParams().width = width;
            rightViewSolid.getLayoutParams().width = width;
        }
        if (leftViewFaded != null && rightViewFaded != null) {
            leftViewFaded.getLayoutParams().width = width;
            rightViewFaded.getLayoutParams().width = width;
        }
        if (topViewSolid != null && bottomViewSolid != null) {
            topViewSolid.getLayoutParams().height = width;
            bottomViewSolid.getLayoutParams().height = width;
        }
        if (topViewFaded != null && bottomViewFaded != null) {
            topViewFaded.getLayoutParams().height = width;
            bottomViewFaded.getLayoutParams().height = width;
        }
        mLightAnimator = ValueAnimator.ofFloat(new float[]{0.0f, 2.0f});
        mLightAnimator.setDuration(duration);
        mLightAnimator.setRepeatCount(repeats == 0 ?
                ValueAnimator.INFINITE : repeats - 1);
        mLightAnimator.setRepeatMode(directionIsRestart ? ValueAnimator.RESTART : ValueAnimator.REVERSE);
        if (repeats != 0) {
            mLightAnimator.addListener(new AnimatorListener() {
                @Override
                public void onAnimationCancel(Animator animation) { /* do nothing */ }
                @Override
                public void onAnimationRepeat(Animator animation) { /* do nothing */ }
                @Override
                public void onAnimationStart(Animator animation) { /* do nothing */ }
                @Override
                public void onAnimationEnd(Animator animation) {
                    Settings.System.putIntForUser(resolver,
                            Settings.System.AOD_NOTIFICATION_PULSE_ACTIVATED, 0,
                            UserHandle.USER_CURRENT);
                    Settings.System.putIntForUser(resolver,
                            Settings.System.AOD_NOTIFICATION_PULSE_TRIGGER, 0,
                            UserHandle.USER_CURRENT);
                }
            });
        }
        mLightAnimator.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                if (DEBUG) Log.d(TAG, "onAnimationUpdate");
                float progress = ((Float) animation.getAnimatedValue()).floatValue();
                leftViewFaded.setScaleY(progress);
                topViewFaded.setScaleX(progress);
                rightViewFaded.setScaleY(progress);
                bottomViewFaded.setScaleX(progress);
                leftViewSolid.setScaleY(progress);
                topViewSolid.setScaleX(progress);
                rightViewSolid.setScaleY(progress);
                bottomViewSolid.setScaleX(progress);
                float alpha = 1.0f;
                if (progress <= 0.3f) {
                    alpha = progress / 0.3f;
                } else if (progress >= 1.0f) {
                    alpha = 2.0f - progress;
                }
                leftViewFaded.setAlpha(alpha);
                topViewFaded.setAlpha(alpha);
                rightViewFaded.setAlpha(alpha);
                bottomViewFaded.setAlpha(alpha);
                leftViewSolid.setAlpha(alpha);
                topViewSolid.setAlpha(alpha);
                rightViewSolid.setAlpha(alpha);
                bottomViewSolid.setAlpha(alpha);
            }
        });
        if (DEBUG) Log.d(TAG, "start");
        mLightAnimator.start();
    }

    public int getRandomColor(){
    Random rnd = new Random();
       return Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256));
    }
}
