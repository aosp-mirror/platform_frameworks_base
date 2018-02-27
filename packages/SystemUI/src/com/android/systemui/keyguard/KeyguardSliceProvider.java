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

package com.android.systemui.keyguard;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl;

import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import androidx.app.slice.Slice;
import androidx.app.slice.SliceProvider;
import androidx.app.slice.builders.ListBuilder;
import androidx.app.slice.builders.ListBuilder.RowBuilder;

/**
 * Simple Slice provider that shows the current date.
 */
public class KeyguardSliceProvider extends SliceProvider implements
        NextAlarmController.NextAlarmChangeCallback {

    public static final String KEYGUARD_SLICE_URI = "content://com.android.systemui.keyguard/main";
    public static final String KEYGUARD_DATE_URI = "content://com.android.systemui.keyguard/date";
    public static final String KEYGUARD_NEXT_ALARM_URI =
            "content://com.android.systemui.keyguard/alarm";

    /**
     * Only show alarms that will ring within N hours.
     */
    @VisibleForTesting
    static final int ALARM_VISIBILITY_HOURS = 12;

    private final Date mCurrentTime = new Date();
    protected final Uri mSliceUri;
    protected final Uri mDateUri;
    protected final Uri mAlarmUri;
    private final Handler mHandler;
    private String mDatePattern;
    private DateFormat mDateFormat;
    private String mLastText;
    private boolean mRegistered;
    private boolean mRegisteredEveryMinute;
    private String mNextAlarm;
    private NextAlarmController mNextAlarmController;
    protected AlarmManager mAlarmManager;
    protected ContentResolver mContentResolver;
    private AlarmManager.AlarmClockInfo mNextAlarmInfo;
    private final AlarmManager.OnAlarmListener mUpdateNextAlarm = this::updateNextAlarm;

    /**
     * Receiver responsible for time ticking and updating the date format.
     */
    @VisibleForTesting
    final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_DATE_CHANGED.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                    || Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                if (Intent.ACTION_LOCALE_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                    // need to get a fresh date format
                    mHandler.post(KeyguardSliceProvider.this::cleanDateFormat);
                }
                mHandler.post(KeyguardSliceProvider.this::updateClock);
            }
        }
    };

    public KeyguardSliceProvider() {
        this(new Handler());
    }

    @VisibleForTesting
    KeyguardSliceProvider(Handler handler) {
        mHandler = handler;
        mSliceUri = Uri.parse(KEYGUARD_SLICE_URI);
        mDateUri = Uri.parse(KEYGUARD_DATE_URI);
        mAlarmUri = Uri.parse(KEYGUARD_NEXT_ALARM_URI);
    }

    @Override
    public Slice onBindSlice(Uri sliceUri) {
        ListBuilder builder = new ListBuilder(getContext(), mSliceUri);
        builder.addRow(new RowBuilder(builder, mDateUri).setTitle(mLastText));
        addNextAlarm(builder);
        return builder.build();
    }

    protected void addNextAlarm(ListBuilder builder) {
        if (TextUtils.isEmpty(mNextAlarm)) {
            return;
        }

        Icon alarmIcon = Icon.createWithResource(getContext(), R.drawable.ic_access_alarms_big);
        RowBuilder alarmRowBuilder = new RowBuilder(builder, mAlarmUri)
                .setTitle(mNextAlarm)
                .addEndItem(alarmIcon);
        builder.addRow(alarmRowBuilder);
    }

    @Override
    public boolean onCreateSliceProvider() {
        mAlarmManager = getContext().getSystemService(AlarmManager.class);
        mContentResolver = getContext().getContentResolver();
        mNextAlarmController = new NextAlarmControllerImpl(getContext());
        mNextAlarmController.addCallback(this);
        mDatePattern = getContext().getString(R.string.system_ui_aod_date_pattern);
        registerClockUpdate(false /* everyMinute */);
        updateClock();
        return true;
    }

    private void updateNextAlarm() {
        if (withinNHours(mNextAlarmInfo, ALARM_VISIBILITY_HOURS)) {
            String pattern = android.text.format.DateFormat.is24HourFormat(getContext(),
                    ActivityManager.getCurrentUser()) ? "H:mm" : "h:mm";
            mNextAlarm = android.text.format.DateFormat.format(pattern,
                    mNextAlarmInfo.getTriggerTime()).toString();
        } else {
            mNextAlarm = "";
        }
        mContentResolver.notifyChange(mSliceUri, null /* observer */);
    }

    private boolean withinNHours(AlarmManager.AlarmClockInfo alarmClockInfo, int hours) {
        if (alarmClockInfo == null) {
            return false;
        }

        long limit = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours);
        return mNextAlarmInfo.getTriggerTime() <= limit;
    }

    /**
     * Registers a broadcast receiver for clock updates, include date, time zone and manually
     * changing the date/time via the settings app.
     *
     * @param everyMinute {@code true} if you also want updates every minute.
     */
    protected void registerClockUpdate(boolean everyMinute) {
        if (mRegistered) {
            if (mRegisteredEveryMinute == everyMinute) {
                return;
            } else {
                unregisterClockUpdate();
            }
        }

        IntentFilter filter = new IntentFilter();
        if (everyMinute) {
            filter.addAction(Intent.ACTION_TIME_TICK);
        }
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        getContext().registerReceiver(mIntentReceiver, filter, null /* permission*/,
                null /* scheduler */);
        mRegistered = true;
        mRegisteredEveryMinute = everyMinute;
    }

    protected void unregisterClockUpdate() {
        if (!mRegistered) {
            return;
        }
        getContext().unregisterReceiver(mIntentReceiver);
        mRegistered = false;
    }

    @VisibleForTesting
    boolean isRegistered() {
        return mRegistered;
    }

    protected void updateClock() {
        final String text = getFormattedDate();
        if (!text.equals(mLastText)) {
            mLastText = text;
            mContentResolver.notifyChange(mSliceUri, null /* observer */);
        }
    }

    protected String getFormattedDate() {
        if (mDateFormat == null) {
            final Locale l = Locale.getDefault();
            DateFormat format = DateFormat.getInstanceForSkeleton(mDatePattern, l);
            format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
            mDateFormat = format;
        }
        mCurrentTime.setTime(System.currentTimeMillis());
        return mDateFormat.format(mCurrentTime);
    }

    @VisibleForTesting
    void cleanDateFormat() {
        mDateFormat = null;
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarmInfo = nextAlarm;
        mAlarmManager.cancel(mUpdateNextAlarm);

        long triggerAt = mNextAlarmInfo == null ? -1 : mNextAlarmInfo.getTriggerTime()
                - TimeUnit.HOURS.toMillis(ALARM_VISIBILITY_HOURS);
        if (triggerAt > 0) {
            mAlarmManager.setExact(AlarmManager.RTC, triggerAt, "lock_screen_next_alarm",
                    mUpdateNextAlarm, mHandler);
        }
        updateNextAlarm();
    }
}
