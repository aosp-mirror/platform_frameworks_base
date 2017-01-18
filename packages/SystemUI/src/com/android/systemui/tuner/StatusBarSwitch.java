/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.support.v14.preference.SwitchPreference;
import android.text.TextUtils;
import android.util.AttributeSet;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.Set;

public class StatusBarSwitch extends SwitchPreference implements Tunable {

    private Set<String> mBlacklist;

    public StatusBarSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        TunerService.get(getContext()).addTunable(this, StatusBarIconController.ICON_BLACKLIST);
    }

    @Override
    public void onDetached() {
        TunerService.get(getContext()).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!StatusBarIconController.ICON_BLACKLIST.equals(key)) {
            return;
        }
        mBlacklist = StatusBarIconController.getIconBlacklist(newValue);
        setChecked(!mBlacklist.contains(getKey()));
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        if (!value) {
            // If not enabled add to blacklist.
            if (!mBlacklist.contains(getKey())) {
                MetricsLogger.action(getContext(), MetricsEvent.TUNER_STATUS_BAR_DISABLE,
                        getKey());
                mBlacklist.add(getKey());
                setList(mBlacklist);
            }
        } else {
            if (mBlacklist.remove(getKey())) {
                MetricsLogger.action(getContext(), MetricsEvent.TUNER_STATUS_BAR_ENABLE, getKey());
                setList(mBlacklist);
            }
        }
        return true;
    }

    private void setList(Set<String> blacklist) {
        ContentResolver contentResolver = getContext().getContentResolver();
        Settings.Secure.putStringForUser(contentResolver, StatusBarIconController.ICON_BLACKLIST,
                TextUtils.join(",", blacklist), ActivityManager.getCurrentUser());
    }
}
