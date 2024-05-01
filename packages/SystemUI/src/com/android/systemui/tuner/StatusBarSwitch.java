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
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.preference.SwitchPreference;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.Set;

public class StatusBarSwitch extends SwitchPreference implements Tunable {

    private Set<String> mHideList;

    public StatusBarSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_HIDE_LIST);
    }

    @Override
    public void onDetached() {
        Dependency.get(TunerService.class).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            return;
        }
        mHideList = StatusBarIconController.getIconHideList(getContext(), newValue);
        setChecked(!mHideList.contains(getKey()));
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        if (!value) {
            // If not enabled add to hideList.
            if (!mHideList.contains(getKey())) {
                MetricsLogger.action(getContext(), MetricsEvent.TUNER_STATUS_BAR_DISABLE,
                        getKey());
                mHideList.add(getKey());
                setList(mHideList);
            }
        } else {
            if (mHideList.remove(getKey())) {
                MetricsLogger.action(getContext(), MetricsEvent.TUNER_STATUS_BAR_ENABLE, getKey());
                setList(mHideList);
            }
        }
        return true;
    }

    private void setList(Set<String> hideList) {
        ContentResolver contentResolver = getContext().getContentResolver();
        Settings.Secure.putStringForUser(contentResolver, StatusBarIconController.ICON_HIDE_LIST,
                TextUtils.join(",", hideList), ActivityManager.getCurrentUser());
    }
}
