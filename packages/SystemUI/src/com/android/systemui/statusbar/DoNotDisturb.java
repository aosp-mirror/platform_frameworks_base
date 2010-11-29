/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.systemui.statusbar.policy.Prefs;

public class DoNotDisturb implements SharedPreferences.OnSharedPreferenceChangeListener {
    private Context mContext;
    private StatusBarManager mStatusBar;
    SharedPreferences mPrefs;
    private boolean mDoNotDisturb;

    public DoNotDisturb(Context context) {
        mContext = context;
        mStatusBar = (StatusBarManager)context.getSystemService(Context.STATUS_BAR_SERVICE);
        mPrefs = Prefs.read(context);
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        mDoNotDisturb = mPrefs.getBoolean(Prefs.DO_NOT_DISTURB_PREF, Prefs.DO_NOT_DISTURB_DEFAULT);
        updateDisableRecord();
    }

    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        final boolean val = prefs.getBoolean(Prefs.DO_NOT_DISTURB_PREF,
                Prefs.DO_NOT_DISTURB_DEFAULT);
        if (val != mDoNotDisturb) {
            mDoNotDisturb = val;
            updateDisableRecord();
        }
    }

    private void updateDisableRecord() {
        final int disabled = StatusBarManager.DISABLE_NOTIFICATION_ICONS
                | StatusBarManager.DISABLE_NOTIFICATION_ALERTS
                | StatusBarManager.DISABLE_NOTIFICATION_TICKER;
        mStatusBar.disable(mDoNotDisturb ? disabled : 0);
    }
}

