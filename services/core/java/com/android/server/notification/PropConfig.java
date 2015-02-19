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

package com.android.server.notification;

import android.content.Context;
import android.os.SystemProperties;

public class PropConfig {
    private static final String UNSET = "UNSET";

    public static int getInt(Context context, String propName, int resId) {
        return SystemProperties.getInt(propName, context.getResources().getInteger(resId));
    }

    public static String[] getStringArray(Context context, String propName, int resId) {
        final String prop = SystemProperties.get(propName, UNSET);
        return !UNSET.equals(prop) ? prop.split(",") : context.getResources().getStringArray(resId);
    }
}
