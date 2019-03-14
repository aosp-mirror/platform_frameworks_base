/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.widget.settingsspinner;

import android.content.Context;
import android.widget.ArrayAdapter;

import com.android.settingslib.widget.R;

/**
 * An ArrayAdapter which was used by {@link SettingsSpinner} with settings style.
 */
public class SettingsSpinnerAdapter<T> extends ArrayAdapter<T> {

    /**
     * Constructs a new SettingsSpinnerAdapter with the given context.
     * And it customizes title bar with a settings style.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     */
    public SettingsSpinnerAdapter(Context context) {
        super(context, R.layout.settings_spinner_view);
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }
}
