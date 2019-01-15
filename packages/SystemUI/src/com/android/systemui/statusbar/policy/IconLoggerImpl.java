/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_NUM_STATUS_ICONS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_STATUS_ICONS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.STATUS_BAR_ICONS_CHANGED;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_ACTION;

import android.content.Context;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.VisibleForTesting;
import android.util.ArraySet;

import com.android.internal.logging.MetricsLogger;

import java.util.Arrays;
import java.util.List;

public class IconLoggerImpl implements IconLogger {

    // Minimum ms between log statements.
    // NonFinalForTesting
    @VisibleForTesting
    protected static long MIN_LOG_INTERVAL = 1000;

    private final Context mContext;
    private final Handler mHandler;
    private final MetricsLogger mLogger;
    private final ArraySet<String> mIcons = new ArraySet<>();
    private final List<String> mIconIndex;
    private long mLastLog = System.currentTimeMillis();

    public IconLoggerImpl(Context context, Looper bgLooper, MetricsLogger logger) {
        mContext = context;
        mHandler = new Handler(bgLooper);
        mLogger = logger;
        String[] icons = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_statusBarIcons);
        mIconIndex = Arrays.asList(icons);
        doLog();
    }

    @Override
    public void onIconShown(String tag) {
        synchronized (mIcons) {
            if (mIcons.contains(tag)) return;
            mIcons.add(tag);
        }
        if (!mHandler.hasCallbacks(mLog)) {
            mHandler.postDelayed(mLog, MIN_LOG_INTERVAL);
        }
    }

    @Override
    public void onIconHidden(String tag) {
        synchronized (mIcons) {
            if (!mIcons.contains(tag)) return;
            mIcons.remove(tag);
        }
        if (!mHandler.hasCallbacks(mLog)) {
            mHandler.postDelayed(mLog, MIN_LOG_INTERVAL);
        }
    }

    private void doLog() {
        long time = System.currentTimeMillis();
        long timeSinceLastLog = time - mLastLog;
        mLastLog = time;

        ArraySet<String> icons;
        synchronized (mIcons) {
            icons = new ArraySet<>(mIcons);
        }
        mLogger.write(new LogMaker(STATUS_BAR_ICONS_CHANGED)
                .setType(TYPE_ACTION)
                .setLatency(timeSinceLastLog)
                .addTaggedData(FIELD_NUM_STATUS_ICONS, icons.size())
                .addTaggedData(FIELD_STATUS_ICONS, getBitField(icons)));
    }

    private int getBitField(ArraySet<String> icons) {
        int iconsVisible = 0;
        for (String icon : icons) {
            int index = mIconIndex.indexOf(icon);
            if (index >= 0) {
                iconsVisible |= (1 << index);
            }
        }
        return iconsVisible;
    }

    private final Runnable mLog = this::doLog;
}
