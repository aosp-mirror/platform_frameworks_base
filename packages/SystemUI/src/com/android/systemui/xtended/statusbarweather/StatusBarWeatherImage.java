/*
 * Copyright (C) 2017 AICP
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

package com.android.systemui.xtended.statusbarweather;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.internal.util.xtended.OmniJawsClient;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.util.Arrays;
import java.util.ArrayList;

public class StatusBarWeatherImage extends ImageView implements
        OmniJawsClient.OmniJawsObserver {

    private String TAG = StatusBarWeatherImage.class.getSimpleName();

    private static final boolean DEBUG = false;

    private Context mContext;

    private int mStatusBarWeatherEnabled;
    private Drawable mWeatherImage;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private boolean mEnabled;
    private boolean mWeatherInHeaderView;
    private boolean mAttached;
    private int mTintColor;
    private int mWeatherIconColor;

    Handler mHandler;

    public StatusBarWeatherImage(Context context) {
        this(context, null);
    }

    public StatusBarWeatherImage(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarWeatherImage(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        mContext = context;
        mHandler = new Handler();
        mWeatherClient = new OmniJawsClient(mContext);
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        mTintColor = resources.getColor(android.R.color.white);
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateColor();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        mWeatherClient.addObserver(this);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        queryAndUpdateWeather();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
	            Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION), false, this,
		    UserHandle.USER_ALL);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            updateColor();
        }
    }

    @Override
    public void weatherUpdated() {
        if (DEBUG) Log.d(TAG, "weatherUpdated");
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
    }

    public void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mStatusBarWeatherEnabled = Settings.System.getIntForUser(
                resolver, Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0,
                UserHandle.USER_CURRENT);
        mWeatherInHeaderView = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION, 0,
                UserHandle.USER_CURRENT) == 1;
        if ((mStatusBarWeatherEnabled == 1 || mStatusBarWeatherEnabled == 2
                || mStatusBarWeatherEnabled == 5) && !mWeatherInHeaderView) {
            mWeatherClient.setOmniJawsEnabled(true);
            queryAndUpdateWeather();
        } else {
            setVisibility(View.GONE);
        }
    }

    private void queryAndUpdateWeather() {
        try {
            if (DEBUG) Log.d(TAG, "queryAndUpdateWeather " + mEnabled);
            if (mEnabled) {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                mWeatherImage = mWeatherClient.getWeatherConditionImage(mWeatherData.conditionCode);
                if (mWeatherData != null) {
                    if (mWeatherImage instanceof VectorDrawable) {
                        mWeatherImage.setTint(mTintColor);
                    }
                    if ((mStatusBarWeatherEnabled == 1
                            || mStatusBarWeatherEnabled == 2
                            || mStatusBarWeatherEnabled == 5) && !mWeatherInHeaderView) {
                        setImageDrawable(mWeatherImage);
                        setVisibility(View.VISIBLE);
                    } else {
			setVisibility(View.GONE);
 		    }
                } else {
                    setVisibility(View.GONE);
                }
            } else {
                setVisibility(View.GONE);
            }
        } catch(Exception e) {
            // Do nothing
        }
    }

    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mTintColor = DarkIconDispatcher.getTint(areas, this, tint);
        if (mWeatherImage == null) return;
        if (mWeatherIconColor == 0xFFFFFFFF) {
            mWeatherImage.setColorFilter(mTintColor, PorterDuff.Mode.MULTIPLY);
        } else {
            mWeatherImage.setColorFilter(mWeatherIconColor, PorterDuff.Mode.MULTIPLY);
        }
        queryAndUpdateWeather();
    }

    private void updateColor() {
        mWeatherIconColor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_WEATHER_ICON_COLOR, 0xffffffff);
        if (mWeatherImage == null) return;
        if (mWeatherIconColor == 0xFFFFFFFF) {
            mWeatherImage.setColorFilter(mTintColor, PorterDuff.Mode.MULTIPLY);
        } else {
            mWeatherImage.setColorFilter(mWeatherIconColor, PorterDuff.Mode.MULTIPLY);
        }
    }
}

