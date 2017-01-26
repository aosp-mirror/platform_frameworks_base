package com.android.systemui.statusbar;
/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

import java.util.ArrayList;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider.GutsInteractionListener;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider.SnoozeListener;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioGroup.OnCheckedChangeListener;
import android.widget.TextView;
import android.widget.Toast;
import com.android.systemui.R;

public class NotificationSnooze extends LinearLayout
        implements NotificationMenuRowProvider.SnoozeGutsContent, View.OnClickListener {

    private GutsInteractionListener mGutsInteractionListener;
    private SnoozeListener mSnoozeListener;
    private StatusBarNotification mSbn;

    private TextView mSelectedOption;
    private TextView mUndo;
    private ViewGroup mSnoozeOptionView;

    private long mTimeToSnooze;

    // Default is the first option in this list
    private static final int[] SNOOZE_OPTIONS = {
            R.string.snooze_option_15_min, R.string.snooze_option_30_min,
            R.string.snooze_option_1_hour
    };

    public NotificationSnooze(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // Create the different options based on list
        createOptionViews();

        // Snackbar
        mSelectedOption = (TextView) findViewById(R.id.snooze_option_default);
        mSelectedOption.setOnClickListener(this);
        mUndo = (TextView) findViewById(R.id.undo);
        mUndo.setOnClickListener(this);

        // Default to first option in list
        setTimeToSnooze(SNOOZE_OPTIONS[0]);
    }

    private void createOptionViews() {
        mSnoozeOptionView = (ViewGroup) findViewById(R.id.snooze_options);
        mSnoozeOptionView.setVisibility(View.GONE);
        final Resources res = getResources();
        final int textSize = res.getDimensionPixelSize(R.dimen.snooze_option_text_size);
        final int p = res.getDimensionPixelSize(R.dimen.snooze_option_padding);
        for (int i = 0; i < SNOOZE_OPTIONS.length; i++) {
            TextView tv = new TextView(getContext());
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
            tv.setPadding(p, p, p, p);
            mSnoozeOptionView.addView(tv);
            tv.setText(SNOOZE_OPTIONS[i]);
            tv.setTag(SNOOZE_OPTIONS[i]);
            tv.setOnClickListener(this);
        }
    }

    private void showSnoozeOptions(boolean show) {
        mSelectedOption.setVisibility(show ? View.GONE : View.VISIBLE);
        mUndo.setVisibility(show ? View.GONE : View.VISIBLE);
        mSnoozeOptionView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setTimeToSnooze(int optionId) {
        long snoozeUntilMillis = Calendar.getInstance().getTimeInMillis();
        switch (optionId) {
            case R.string.snooze_option_15_min:
                snoozeUntilMillis += TimeUnit.MINUTES.toMillis(15);
                break;
            case R.string.snooze_option_30_min:
                snoozeUntilMillis += TimeUnit.MINUTES.toMillis(30);
                break;
            case R.string.snooze_option_1_hour:
                snoozeUntilMillis += TimeUnit.MINUTES.toMillis(60);
                break;
        }
        mTimeToSnooze = snoozeUntilMillis;
        final Resources res = getResources();
        String selectedString = String.format(
                res.getString(R.string.snoozed_for_time), res.getString(optionId));
        mSelectedOption.setText(selectedString);
        showSnoozeOptions(false);
    }

    @Override
    public void onClick(View v) {
        if (mGutsInteractionListener != null) {
            mGutsInteractionListener.onInteraction(this);
        }
        final int id = v.getId();
        final Integer tag = (Integer) v.getTag();
        if (tag != null) {
            // From the option list
            setTimeToSnooze(tag);
        } else if (id == R.id.snooze_option_default) {
            // Show more snooze options
            showSnoozeOptions(true);
        } else if (id == R.id.undo) {
            mTimeToSnooze = -1;
            mGutsInteractionListener.closeGuts(this);
        }
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public void setStatusBarNotification(StatusBarNotification sbn) {
        mSbn = sbn;
    }

    @Override
    public void setInteractionListener(GutsInteractionListener listener) {
        mGutsInteractionListener = listener;
    }

    @Override
    public void setSnoozeListener(SnoozeListener listener) {
        mSnoozeListener = listener;
    }

    @Override
    public boolean handleCloseControls() {
        // When snooze is closed (i.e. there was interaction outside of the notification)
        // then we commit the snooze action.
        if (mSnoozeListener != null && mTimeToSnooze != -1) {
            mSnoozeListener.snoozeNotification(mSbn, mTimeToSnooze);
            return true;
        }
        return false;
    }
}
