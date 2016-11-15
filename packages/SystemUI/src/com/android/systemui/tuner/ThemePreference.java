/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.tuner;

import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Context;
import android.os.SystemProperties;
import android.support.v7.preference.ListPreference;
import android.text.TextUtils;
import android.util.AttributeSet;

import com.android.systemui.R;

import libcore.util.Objects;

import com.google.android.collect.Lists;

import java.io.File;
import java.util.ArrayList;

public class ThemePreference extends ListPreference {

    public ThemePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        String def = SystemProperties.get("ro.boot.vendor.overlay.theme");
        if (TextUtils.isEmpty(def)) {
            def = getContext().getString(R.string.default_theme);
        }
        String[] fileList = new File("/vendor/overlay").list();
        ArrayList<String> options = fileList != null
                ? Lists.newArrayList(fileList) : new ArrayList<>();
        if (!options.contains(def)) {
            options.add(0, def);
        }
        String[] list = options.toArray(new String[options.size()]);
        setVisible(options.size() > 1);
        setEntries(list);
        setEntryValues(list);
        updateValue();
    }

    private void updateValue() {
        setValue(getContext().getSystemService(UiModeManager.class).getTheme());
    }

    @Override
    protected void notifyChanged() {
        super.notifyChanged();
        if (!Objects.equal(getValue(),
                getContext().getSystemService(UiModeManager.class).getTheme())) {
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.change_theme_reboot)
                    .setPositiveButton(com.android.internal.R.string.global_action_restart, (d, i)
                            -> getContext().getSystemService(UiModeManager.class)
                            .setTheme(getValue()))
                    .setNegativeButton(android.R.string.cancel, (d, i) -> updateValue())
                    .show();
        }
    }
}
