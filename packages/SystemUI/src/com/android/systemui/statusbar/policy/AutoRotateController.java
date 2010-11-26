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
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import android.view.IWindowManager;
import android.widget.CompoundButton;

/**
 * TODO: Listen for changes to the setting.
 */
public class AutoRotateController implements CompoundButton.OnCheckedChangeListener {
    private static final String TAG = "StatusBar.AutoRotateController";

    private Context mContext;
    private CompoundButton mCheckBox;

    private boolean mLockRotation;

    public AutoRotateController(Context context, CompoundButton checkbox) {
        mContext = context;
        mLockRotation = getLockRotation();
        mCheckBox = checkbox;
        checkbox.setChecked(mLockRotation);
        checkbox.setOnCheckedChangeListener(this);
    }

    public void onCheckedChanged(CompoundButton view, boolean checked) {
        Slog.d(TAG, "onCheckedChanged checked=" + checked + " mLockRotation=" + mLockRotation);
        if (checked != mLockRotation) {
            setLockRotation(checked);
        }
    }

    private boolean getLockRotation() {
        ContentResolver cr = mContext.getContentResolver();
        return 0 == Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
    }

    private void setLockRotation(boolean locked) {
        mLockRotation = locked;
        try {
            IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.getService(
                        Context.WINDOW_SERVICE));
            ContentResolver cr = mContext.getContentResolver();
            if (locked) {
                wm.freezeRotation();
            } else {
                wm.thawRotation();
            }
        } catch (RemoteException exc) {
        }
    }
}
