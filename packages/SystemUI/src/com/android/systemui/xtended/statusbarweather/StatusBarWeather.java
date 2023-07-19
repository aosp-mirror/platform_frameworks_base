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
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.internal.util.xtended.OmniJawsClient;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

import java.util.Arrays;
import java.util.ArrayList;

public class StatusBarWeather extends TextView implements
        OmniJawsClient.OmniJawsObserver, DarkReceiver {

    private static final String TAG = StatusBarWeather.class.getSimpleName();

    private static final boolean DEBUG = false;

    private Context mContext;

    private int mStatusBarWeatherEnabled;
    private TextView mStatusBarWeatherInfo;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;
    private boolean mEnabled;
    private boolean mWeatherInHeaderView;

    private int mWeatherColor = 0xffffffff;
    private int mTintColor = Color.WHITE;

    private int mWeatherFontStyle = FONT_NORMAL;
    public static final int FONT_NORMAL = 0;
    public static final int FONT_ITALIC = 1;
    public static final int FONT_BOLD = 2;
    public static final int FONT_BOLD_ITALIC = 3;
    public static final int FONT_LIGHT = 4;
    public static final int FONT_LIGHT_ITALIC = 5;
    public static final int FONT_THIN = 6;
    public static final int FONT_THIN_ITALIC = 7;
    public static final int FONT_CONDENSED = 8;
    public static final int FONT_CONDENSED_ITALIC = 9;
    public static final int FONT_CONDENSED_LIGHT = 10;
    public static final int FONT_CONDENSED_LIGHT_ITALIC = 11;
    public static final int FONT_CONDENSED_BOLD = 12;
    public static final int FONT_CONDENSED_BOLD_ITALIC = 13;
    public static final int FONT_MEDIUM = 14;
    public static final int FONT_MEDIUM_ITALIC = 15;
    public static final int FONT_BLACK = 16;
    public static final int FONT_BLACK_ITALIC = 17;
    public static final int FONT_DANCINGSCRIPT = 18;
    public static final int FONT_DANCINGSCRIPT_BOLD = 19;
    public static final int FONT_COMINGSOON = 20;
    public static final int FONT_NOTOSERIF = 21;
    public static final int FONT_NOTOSERIF_ITALIC = 22;
    public static final int FONT_NOTOSERIF_BOLD = 23;
    public static final int FONT_NOTOSERIF_BOLD_ITALIC = 24;
    public static final int GOBOLD_LIGHT = 25;
    public static final int ROADRAGE = 26;
    public static final int SNOWSTORM = 27;
    public static final int GOOGLESANS = 28;
    public static final int NEONEON = 29;
    public static final int THEMEABLE = 30;
    public static final int SAMSUNG = 31;
    public static final int MEXCELLENT = 32;
    public static final int BURNSTOWN = 33;
    public static final int DUMBLEDOR = 34;
    public static final int PHANTOMBOLD = 35;

    Handler mHandler;

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
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_WEATHER_FONT_STYLE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_WEATHER_FONT_COLOR), false, this,
                    UserHandle.USER_ALL);
            updateSettings(false);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings(true);
        }
    }

    public StatusBarWeather(Context context) {
        this(context, null);

    }

    public StatusBarWeather(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatusBarWeather(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        mContext = context;
        mHandler = new Handler();
        mWeatherClient = new OmniJawsClient(mContext);
        mEnabled = mWeatherClient.isOmniJawsEnabled();
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        updateColor();
        updateStyle();
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

    @Override
    public void weatherUpdated() {
        if (DEBUG) Log.d(TAG, "weatherUpdated");
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
    }

    public void updateSettings(boolean onChange) {
        ContentResolver resolver = mContext.getContentResolver();
        mStatusBarWeatherEnabled = Settings.System.getIntForUser(
                resolver, Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0,
                UserHandle.USER_CURRENT);
        mWeatherInHeaderView = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_SHOW_WEATHER_LOCATION, 0,
                UserHandle.USER_CURRENT) == 1;
        if ((mStatusBarWeatherEnabled != 0 && mStatusBarWeatherEnabled != 5)
                                           && !mWeatherInHeaderView) {
            mWeatherClient.setOmniJawsEnabled(true);
            queryAndUpdateWeather();
        } else {
            setVisibility(View.GONE);
        }
        updateColor();
        updateStyle();
    }

    private void queryAndUpdateWeather() {
        try {
            if (DEBUG) Log.d(TAG, "queryAndUpdateWeather " + mEnabled);
            if (mEnabled) {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                if (mWeatherData != null) {
                    if ((mStatusBarWeatherEnabled != 0
                            || mStatusBarWeatherEnabled != 5) && !mWeatherInHeaderView) {
                        if (mStatusBarWeatherEnabled == 2 || mStatusBarWeatherEnabled == 4) {
                            setText(mWeatherData.temp);
                        } else {
                            setText(mWeatherData.temp + mWeatherData.tempUnits);
                        }
                        if (mStatusBarWeatherEnabled != 0 && mStatusBarWeatherEnabled != 5 && !mWeatherInHeaderView) {
                            setVisibility(View.VISIBLE);
                        }
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
        if (mWeatherColor == 0xFFFFFFFF) {
            setTextColor(mTintColor);
        } else {
            setTextColor(mWeatherColor);
        }
        queryAndUpdateWeather();
    }

    public void getFontStyle(int font) {
        switch (font) {
            case FONT_NORMAL:
            default:
                setTypeface(Typeface.create("sans-serif",
                    Typeface.NORMAL));
                break;
            case FONT_ITALIC:
                setTypeface(Typeface.create("sans-serif",
                    Typeface.ITALIC));
                break;
            case FONT_BOLD:
                setTypeface(Typeface.create("sans-serif",
                    Typeface.BOLD));
                break;
            case FONT_BOLD_ITALIC:
                setTypeface(Typeface.create("sans-serif",
                    Typeface.BOLD_ITALIC));
                break;
            case FONT_LIGHT:
                setTypeface(Typeface.create("sans-serif-light",
                    Typeface.NORMAL));
                break;
            case FONT_LIGHT_ITALIC:
                setTypeface(Typeface.create("sans-serif-light",
                    Typeface.ITALIC));
                break;
            case FONT_THIN:
                setTypeface(Typeface.create("sans-serif-thin",
                    Typeface.NORMAL));
                break;
            case FONT_THIN_ITALIC:
                setTypeface(Typeface.create("sans-serif-thin",
                    Typeface.ITALIC));
                break;
            case FONT_CONDENSED:
                setTypeface(Typeface.create("sans-serif-condensed",
                    Typeface.NORMAL));
                break;
            case FONT_CONDENSED_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed",
                    Typeface.ITALIC));
                break;
            case FONT_CONDENSED_LIGHT:
                setTypeface(Typeface.create("sans-serif-condensed-light",
                    Typeface.NORMAL));
                break;
            case FONT_CONDENSED_LIGHT_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed-light",
                    Typeface.ITALIC));
                break;
            case FONT_CONDENSED_BOLD:
                setTypeface(Typeface.create("sans-serif-condensed",
                    Typeface.BOLD));
                break;
            case FONT_CONDENSED_BOLD_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed",
                    Typeface.BOLD_ITALIC));
                break;
            case FONT_MEDIUM:
                setTypeface(Typeface.create("sans-serif-medium",
                    Typeface.NORMAL));
                break;
            case FONT_MEDIUM_ITALIC:
                setTypeface(Typeface.create("sans-serif-medium",
                    Typeface.ITALIC));
                break;
            case FONT_BLACK:
                setTypeface(Typeface.create("sans-serif-black",
                    Typeface.NORMAL));
                break;
            case FONT_BLACK_ITALIC:
                setTypeface(Typeface.create("sans-serif-black",
                    Typeface.ITALIC));
                break;
            case FONT_DANCINGSCRIPT:
                setTypeface(Typeface.create("cursive",
                    Typeface.NORMAL));
                break;
            case FONT_DANCINGSCRIPT_BOLD:
                setTypeface(Typeface.create("cursive",
                    Typeface.BOLD));
                break;
            case FONT_COMINGSOON:
                setTypeface(Typeface.create("casual",
                    Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF:
                setTypeface(Typeface.create("serif",
                    Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF_ITALIC:
                setTypeface(Typeface.create("serif",
                    Typeface.ITALIC));
                break;
            case FONT_NOTOSERIF_BOLD:
                setTypeface(Typeface.create("serif",
                    Typeface.BOLD));
                break;
            case FONT_NOTOSERIF_BOLD_ITALIC:
                setTypeface(Typeface.create("serif",
                    Typeface.BOLD_ITALIC));
                break;
            case GOBOLD_LIGHT:
                setTypeface(Typeface.create("gobold-light-sys",
                    Typeface.NORMAL));
                break;
            case ROADRAGE:
                setTypeface(Typeface.create("roadrage-sys",
		   Typeface.NORMAL));
                break;
            case SNOWSTORM:
                setTypeface(Typeface.create("snowstorm-sys",
                    Typeface.NORMAL));
                break;
            case GOOGLESANS:
                setTypeface(Typeface.create("googlesans-sys",
                    Typeface.NORMAL));
                break;
            case NEONEON:
                setTypeface(Typeface.create("neoneon-sys",
                    Typeface.NORMAL));
                break;
            case THEMEABLE:
                setTypeface(Typeface.create("themeable-sys",
                    Typeface.NORMAL));
                break;
            case SAMSUNG:
                setTypeface(Typeface.create("samsung-sys",
                    Typeface.NORMAL));
                break;
            case MEXCELLENT:
                setTypeface(Typeface.create("mexcellent-sys",
                    Typeface.NORMAL));
                break;
            case BURNSTOWN:
                setTypeface(Typeface.create("burnstown-sys",
                    Typeface.NORMAL));
                break;
            case DUMBLEDOR:
                setTypeface(Typeface.create("dumbledor-sys",
                    Typeface.NORMAL));
                break;
	    case PHANTOMBOLD:
                setTypeface(Typeface.create("phantombold-sys",
                    Typeface.NORMAL));
                break;
        }
    }

    private void updateColor() {
        mWeatherColor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_WEATHER_FONT_COLOR, 0xffffffff);
        if (mWeatherColor == 0xFFFFFFFF) {
            setTextColor(mTintColor);
        } else {
            setTextColor(mWeatherColor);
        }
    }

    private void updateStyle() {
        mWeatherFontStyle = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_WEATHER_FONT_STYLE, FONT_NORMAL);
        getFontStyle(mWeatherFontStyle);
    }
}

