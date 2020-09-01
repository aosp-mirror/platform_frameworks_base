/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.util.time;

import android.app.ActivityManager;
import android.content.Context;
import android.text.format.DateFormat;

import javax.inject.Inject;

/**
 * Instantiable wrapper around {@link DateFormat}.
 */
public class DateFormatUtil {
    private final Context mContext;

    @Inject
    public DateFormatUtil(Context context) {
        mContext = context;
    }

    /** Returns true if the phone is in 24 hour format. */
    public boolean is24HourFormat() {
        return DateFormat.is24HourFormat(mContext, ActivityManager.getCurrentUser());
    }
}
