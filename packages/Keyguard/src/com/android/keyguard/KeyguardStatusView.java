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
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import libcore.icu.ICU;

public class KeyguardStatusView extends GridLayout {
    private static final boolean DEBUG = KeyguardViewMediator.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private LockPatternUtils mLockPatternUtils;

    private TextView mAlarmStatusView;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refresh();
        }

        @Override
        void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refresh();
            }
        };
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mLockPatternUtils = new LockPatternUtils(getContext());

        // Required to get Marquee to work.
        final View marqueeViews[] = { mAlarmStatusView };
        for (int i = 0; i < marqueeViews.length; i++) {
            View v = marqueeViews[i];
            if (v == null) {
                throw new RuntimeException("Can't find widget at index " + i);
            }
            v.setSelected(true);
        }
        refresh();
    }

    protected void refresh() {
        refreshAlarmStatus(); // might as well
    }

    void refreshAlarmStatus() {
        // Update Alarm status
        String nextAlarm = mLockPatternUtils.getNextAlarm();
        if (!TextUtils.isEmpty(nextAlarm)) {
            mAlarmStatusView.setText(nextAlarm);
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
    }

    public int getAppWidgetId() {
        return LockPatternUtils.ID_DEFAULT_STATUS_WIDGET;
    }

}
