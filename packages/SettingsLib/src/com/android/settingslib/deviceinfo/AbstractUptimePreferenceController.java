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
 * limitations under the License.
 */

package com.android.settingslib.deviceinfo;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.VisibleForTesting;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;
import android.text.format.DateUtils;

import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

import java.lang.ref.WeakReference;

/**
 * Preference controller for uptime
 */
public abstract class AbstractUptimePreferenceController extends AbstractPreferenceController
        implements LifecycleObserver, OnStart, OnStop {

    @VisibleForTesting
    static final String KEY_UPTIME = "up_time";
    private static final int EVENT_UPDATE_STATS = 500;

    private Preference mUptime;
    private Handler mHandler;

    public AbstractUptimePreferenceController(Context context, Lifecycle lifecycle) {
        super(context);
        if (lifecycle != null) {
            lifecycle.addObserver(this);
        }
    }

    @Override
    public void onStart() {
        getHandler().sendEmptyMessage(EVENT_UPDATE_STATS);
    }

    @Override
    public void onStop() {
        getHandler().removeMessages(EVENT_UPDATE_STATS);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getPreferenceKey() {
        return KEY_UPTIME;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mUptime = screen.findPreference(KEY_UPTIME);
        updateTimes();
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new MyHandler(this);
        }
        return mHandler;
    }

    private void updateTimes() {
        mUptime.setSummary(DateUtils.formatDuration(SystemClock.elapsedRealtime()));
    }

    private static class MyHandler extends Handler {
        private WeakReference<AbstractUptimePreferenceController> mStatus;

        public MyHandler(AbstractUptimePreferenceController activity) {
            mStatus = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            AbstractUptimePreferenceController status = mStatus.get();
            if (status == null) {
                return;
            }

            switch (msg.what) {
                case EVENT_UPDATE_STATS:
                    status.updateTimes();
                    sendEmptyMessageDelayed(EVENT_UPDATE_STATS, 1000);
                    break;

                default:
                    throw new IllegalStateException("Unknown message " + msg.what);
            }
        }
    }
}
