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
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.text.method.SingleLineTransformationMethod;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.Locale;

public class CarrierText extends TextView {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "CarrierText";

    private static CharSequence mSeparator;

    private boolean mShowMissingSim;

    private boolean mShowAirplaneMode;
    private boolean mShouldMarquee;

    private CarrierTextController mCarrierTextController;

    private CarrierTextController.CarrierTextCallback mCarrierTextCallback =
            new CarrierTextController.CarrierTextCallback() {
                @Override
                public void updateCarrierInfo(CarrierTextController.CarrierTextCallbackInfo info) {
                    setText(info.carrierText);
                }

                @Override
                public void startedGoingToSleep() {
                    setSelected(false);
                }

                @Override
                public void finishedWakingUp() {
                    setSelected(true);
                }
            };

    public CarrierText(Context context) {
        this(context, null);
    }

    public CarrierText(Context context, AttributeSet attrs) {
        super(context, attrs);
        boolean useAllCaps;
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.CarrierText, 0, 0);
        try {
            useAllCaps = a.getBoolean(R.styleable.CarrierText_allCaps, false);
            mShowAirplaneMode = a.getBoolean(R.styleable.CarrierText_showAirplaneMode, false);
            mShowMissingSim = a.getBoolean(R.styleable.CarrierText_showMissingSim, false);
        } finally {
            a.recycle();
        }
        setTransformationMethod(new CarrierTextTransformationMethod(mContext, useAllCaps));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSeparator = getResources().getString(
                com.android.internal.R.string.kg_text_message_separator);
        mCarrierTextController = new CarrierTextController(mContext, mSeparator, mShowAirplaneMode,
                mShowMissingSim);
        mShouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setSelected(mShouldMarquee); // Allow marquee to work.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mCarrierTextController.setListening(mCarrierTextCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mCarrierTextController.setListening(null);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Only show marquee when visible
        if (visibility == VISIBLE) {
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
        } else {
            setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    private class CarrierTextTransformationMethod extends SingleLineTransformationMethod {
        private final Locale mLocale;
        private final boolean mAllCaps;

        public CarrierTextTransformationMethod(Context context, boolean allCaps) {
            mLocale = context.getResources().getConfiguration().locale;
            mAllCaps = allCaps;
        }

        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            source = super.getTransformation(source, view);

            if (mAllCaps && source != null) {
                source = source.toString().toUpperCase(mLocale);
            }

            return source;
        }
    }
}
