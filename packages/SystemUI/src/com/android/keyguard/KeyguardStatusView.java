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

import static java.util.Collections.emptySet;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.omni.CurrentWeatherView;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.tuner.TunerService;

import java.io.PrintWriter;
import java.util.Set;

/**
 * View consisting of:
 * - keyguard clock
 * - media player (split shade mode only)
 */
public class KeyguardStatusView extends GridLayout implements
     TunerService.Tunable {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private static final String LOCKSCREEN_WEATHER_ENABLED =
            "system:" + Settings.System.OMNI_LOCKSCREEN_WEATHER_ENABLED;
    private static final String LOCKSCREEN_WEATHER_STYLE =
            "system:" + Settings.System.AICP_LOCKSCREEN_WEATHER_STYLE;
    private static final String LOCKSCREEN_WEATHER_SELECTION =
            "system:" + Settings.System.LOCKSCREEN_WEATHER_SELECTION;

    private ViewGroup mStatusViewContainer;
    private KeyguardClockSwitch mClockView;
    private KeyguardSliceView mKeyguardSlice;
    private View mMediaHostContainer;
    private CurrentWeatherView mWeatherView;
    private boolean mShowWeather;
    private boolean mOmniStyle;

    // Weather styles paddings
    private int mWeatherBgSelection;
    private int mWeatherVerPadding;
    private int mWeatherHorPadding;

    private float mDarkAmount = 0;

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        Dependency.get(TunerService.class).addTunable(this,
                LOCKSCREEN_WEATHER_ENABLED,
                LOCKSCREEN_WEATHER_STYLE,
                LOCKSCREEN_WEATHER_SELECTION);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mStatusViewContainer = findViewById(R.id.status_view_container);

        mClockView = findViewById(R.id.keyguard_clock_container);

        mKeyguardSlice = findViewById(R.id.keyguard_slice_view);

        mWeatherView = (CurrentWeatherView) findViewById(R.id.weather_container);


        mMediaHostContainer = findViewById(R.id.status_view_media_container);

        updateDark();
        updateWeatherView();

        //mKeyguardSlice.setContentChangeListener(this::onSliceContentChanged);
        //onSliceContentChanged(); doc: This is removed with A13
    }

    void setDarkAmount(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        CrossFadeHelper.fadeOut(mMediaHostContainer, darkAmount);
        updateDark();
    }

    void updateDark() {
        mKeyguardSlice.setDarkAmount(mDarkAmount);
        if (mWeatherView != null) {
            mWeatherView.blendARGB(mDarkAmount);
        }
    }

    /** Sets a translationY value on every child view except for the media view. */
    public void setChildrenTranslationY(float translationY, boolean excludeMedia) {
        setChildrenTranslationYExcluding(translationY,
                excludeMedia ? Set.of(mMediaHostContainer) : emptySet());
    }

    /** Sets a translationY value on every view except for the views in the provided set. */
    private void setChildrenTranslationYExcluding(float translationY, Set<View> excludedViews) {
        for (int i = 0; i < mStatusViewContainer.getChildCount(); i++) {
            final View child = mStatusViewContainer.getChildAt(i);

            if (!excludedViews.contains(child)) {
                child.setTranslationY(translationY);
            }
        }
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardStatusView:");
        pw.println("  mDarkAmount: " + mDarkAmount);
        if (mClockView != null) {
            mClockView.dump(pw, args);
        }
        if (mKeyguardSlice != null) {
            mKeyguardSlice.dump(pw, args);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Trace.beginSection("KeyguardStatusView#onMeasure");
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Trace.endSection();
    }

    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case LOCKSCREEN_WEATHER_ENABLED:
                mShowWeather =
                        TunerService.parseIntegerSwitch(newValue, false);
                updateWeatherView();
                break;
            case LOCKSCREEN_WEATHER_STYLE:
                mOmniStyle =
                        !TunerService.parseIntegerSwitch(newValue, false);
                updateWeatherView();
                break;
            case LOCKSCREEN_WEATHER_SELECTION:
                mWeatherBgSelection =
                        TunerService.parseInteger(newValue, 0);
                updateWeatherBg();
                break;
            default:
                break;
        }
    }

    public void updateWeatherView() {
        if (mWeatherView != null) {
            if (mShowWeather && mOmniStyle && mKeyguardSlice.getVisibility() == View.VISIBLE) {
                mWeatherView.setVisibility(View.VISIBLE);
                mWeatherView.enableUpdates();
            } else if (!mShowWeather || !mOmniStyle) {
                mWeatherView.setVisibility(View.GONE);
                mWeatherView.disableUpdates();
            }
               updateWeatherBg();
        }
    }

    private void updateWeatherBg() {
        if (mWeatherView != null && mOmniStyle) {
            switch (mWeatherBgSelection) {
                case 0: // default
                    mWeatherView.setViewBackgroundResource(0);
                    mWeatherVerPadding = 0;
                    mWeatherHorPadding = 0;
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                case 1: // semi-transparent box
                    mWeatherView.setViewBackground(getResources().getDrawable(R.drawable.date_box_str_border));
                    mWeatherHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_hor),getResources().getDisplayMetrics()));
                    mWeatherVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_ver),getResources().getDisplayMetrics()));
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                case 2: // semi-transparent box (round)
                    mWeatherView.setViewBackground(getResources().getDrawable(R.drawable.date_str_border));
                    mWeatherHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_hor),getResources().getDisplayMetrics()));
                    mWeatherVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_box_padding_ver),getResources().getDisplayMetrics()));
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                case 3: // Q-Now Playing background
                    mWeatherView.setViewBackground(getResources().getDrawable(R.drawable.ambient_indication_pill_background));
                    mWeatherHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.q_nowplay_pill_padding_hor),getResources().getDisplayMetrics()));
                    mWeatherVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.q_nowplay_pill_padding_ver),getResources().getDisplayMetrics()));
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                case 4: // accent box
                    mWeatherView.setViewBackground(getResources().getDrawable(R.drawable.date_str_accent));
                    mWeatherHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                    mWeatherVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                case 5: // accent box transparent
                    mWeatherView.setViewBackground(getResources().getDrawable(R.drawable.date_str_accent), 160);
                    mWeatherHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                    mWeatherVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                case 6: // gradient box
                    mWeatherView.setViewBackground(getResources().getDrawable(R.drawable.date_str_gradient));
                    mWeatherHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                    mWeatherVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                case 7: // Dark Accent border
                    mWeatherView.setViewBackground(getResources().getDrawable(R.drawable.date_str_borderacc));
                    mWeatherHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                    mWeatherVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                case 8: // Dark Gradient border
                    mWeatherView.setViewBackground(getResources().getDrawable(R.drawable.date_str_bordergrad));
                    mWeatherHorPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_hor),getResources().getDisplayMetrics()));
                    mWeatherVerPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(R.dimen.widget_date_accent_box_padding_ver),getResources().getDisplayMetrics()));
                    mWeatherView.setViewPadding(mWeatherHorPadding,mWeatherVerPadding,mWeatherHorPadding,mWeatherVerPadding);
                    break;
                default:
                    break;
            }
        }
    }
}
