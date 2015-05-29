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

import android.content.ContentResolver;
import android.content.Context;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;

import com.android.systemui.statusbar.phone.StatusBarIconController;

import java.util.Set;

public class StatusBarSwitch extends SwitchPreference {

    public StatusBarSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        setChecked(!StatusBarIconController.isBlocked(getContext(), getKey()));
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        Set<String> blacklist = StatusBarIconController.getIconBlacklist(getContext());
        if (!value) {
            // If not enabled add to blacklist.
            if (!blacklist.contains(getKey())) {
                blacklist.add(getKey());
                setList(blacklist);
            }
        } else {
            if (blacklist != null && blacklist.remove(getKey())) {
                setList(blacklist);
            }
        }
        return true;
    }

    private void setList(Set<String> blacklist) {
        ContentResolver contentResolver = getContext().getContentResolver();
        Settings.Secure.putString(contentResolver, StatusBarIconController.ICON_BLACKLIST,
                TextUtils.join(",", blacklist));
    }
}
