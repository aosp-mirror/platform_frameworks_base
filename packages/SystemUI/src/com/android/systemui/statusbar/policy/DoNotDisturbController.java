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

package com.android.systemui.statusbar.policy;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import android.view.IWindowManager;
import android.widget.CompoundButton;

public class DoNotDisturbController implements CompoundButton.OnCheckedChangeListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "StatusBar.DoNotDisturbController";

    SharedPreferences mPrefs;
    private Context mContext;
    private CompoundButton mCheckBox;

    private boolean mDoNotDisturb;

    public DoNotDisturbController(Context context, CompoundButton checkbox) {
        mContext = context;

        mPrefs = Prefs.read(context);
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        mDoNotDisturb = mPrefs.getBoolean(Prefs.DO_NOT_DISTURB_PREF, Prefs.DO_NOT_DISTURB_DEFAULT);

        mCheckBox = checkbox;
        checkbox.setOnCheckedChangeListener(this);

        checkbox.setChecked(!mDoNotDisturb);
    }

    // The checkbox is ON for notifications coming in and OFF for Do not disturb, so we
    // don't have a double negative.
    public void onCheckedChanged(CompoundButton view, boolean checked) {
        //Slog.d(TAG, "onCheckedChanged checked=" + checked + " mDoNotDisturb=" + mDoNotDisturb);
        final boolean value = !checked;
        if (value != mDoNotDisturb) {
            SharedPreferences.Editor editor = Prefs.edit(mContext);
            editor.putBoolean(Prefs.DO_NOT_DISTURB_PREF, value);
            editor.apply();
        }
    }
    
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        final boolean val = prefs.getBoolean(Prefs.DO_NOT_DISTURB_PREF,
                Prefs.DO_NOT_DISTURB_DEFAULT);
        if (val != mDoNotDisturb) {
            mDoNotDisturb = val;
            mCheckBox.setChecked(!val);
        }
    }

    public void release() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
    }
}

