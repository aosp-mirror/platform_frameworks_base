/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

public abstract class ZenModeViewAdapter implements ZenModeView.Adapter {
    private static final String TAG = "ZenModeViewAdapter";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler = new Handler();
    private final SettingsObserver mObserver;
    private final List<ExitCondition> mExits = Arrays.asList(
            newExit("Until you turn this off", "Until", "You turn this off"));

    private Callbacks mCallbacks;
    private int mExitIndex;
    private boolean mMode;

    public ZenModeViewAdapter(Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        mObserver = new SettingsObserver(mHandler);
        mObserver.init();
        init();
    }

    @Override
    public boolean getMode() {
        return mMode;
    }

    @Override
    public void setMode(boolean mode) {
        if (mode == mMode) return;
        mMode = mode;
        final int v = mMode ? Settings.Global.ZEN_MODE_ON : Settings.Global.ZEN_MODE_OFF;
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                Settings.Global.putInt(mContext.getContentResolver(),
                        Settings.Global.ZEN_MODE, v);
            }
        });
        dispatchChanged();
    }

    @Override
    public void init() {
        if (mExitIndex != 0) {
            mExitIndex = 0;
            dispatchChanged();
        }
    }

    private void dispatchChanged() {
        mHandler.removeCallbacks(mChanged);
        mHandler.post(mChanged);
    }

    @Override
    public void setCallbacks(final Callbacks callbacks) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mCallbacks = callbacks;
            }
        });
    }

    @Override
    public ExitCondition getExitCondition(int d) {
        final int n = mExits.size();
        final int i = (n + (mExitIndex + (int)Math.signum(d))) % n;
        return mExits.get(i);
    }

    @Override
    public int getExitConditionCount() {
        return mExits.size();
    }

    @Override
    public int getExitConditionIndex() {
        return mExitIndex;
    }

    @Override
    public void select(ExitCondition ec) {
        final int i = mExits.indexOf(ec);
        if (i == -1 || i == mExitIndex) {
            return;
        }
        mExitIndex = i;
        dispatchChanged();
    }

    private static ExitCondition newExit(String summary, String line1, String line2) {
        final ExitCondition rt = new ExitCondition();
        rt.summary = summary;
        rt.line1 = line1;
        rt.line2 = line2;
        return rt;
    }

    private final Runnable mChanged = new Runnable() {
        public void run() {
            if (mCallbacks == null) {
                return;
            }
            try {
                mCallbacks.onChanged();
            } catch (Throwable t) {
                Log.w(TAG, "Error dispatching onChanged to " + mCallbacks, t);
            }
        }
    };

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void init() {
            loadSettings();
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ZEN_MODE),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            loadSettings();
            mChanged.run();  // already on handler
        }

        private void loadSettings() {
            mMode = getModeFromSetting();
        }

        private boolean getModeFromSetting() {
            final int v = Settings.Global.getInt(mResolver,
                    Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
            return v != Settings.Global.ZEN_MODE_OFF;
        }
    }
}
