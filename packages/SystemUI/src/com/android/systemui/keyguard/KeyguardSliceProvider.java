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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.net.Uri;
import android.os.Handler;
import android.app.slice.Slice;
import android.app.slice.SliceProvider;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.util.Date;
import java.util.Locale;

/**
 * Simple Slice provider that shows the current date.
 */
public class KeyguardSliceProvider extends SliceProvider {

    public static final String KEYGUARD_SLICE_URI = "content://com.android.systemui.keyguard/main";

    private final Date mCurrentTime = new Date();
    protected final Uri mSliceUri;
    private final Handler mHandler;
    private String mDatePattern;
    private DateFormat mDateFormat;
    private String mLastText;
    private boolean mRegistered;
    private boolean mRegisteredEveryMinute;

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
    }

    @Override
    public Slice onBindSlice(Uri sliceUri) {
        return new Slice.Builder(sliceUri).addText(mLastText, Slice.HINT_TITLE).build();
    }

    @Override
    public boolean onCreate() {

        mDatePattern = getContext().getString(R.string.system_ui_date_pattern);

        registerClockUpdate(false /* everyMinute */);
        updateClock();
        return true;
    }

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
            getContext().getContentResolver().notifyChange(mSliceUri, null /* observer */);
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
}
