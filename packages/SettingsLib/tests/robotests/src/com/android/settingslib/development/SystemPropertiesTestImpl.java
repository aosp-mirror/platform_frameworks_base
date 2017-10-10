/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.development;

import android.text.TextUtils;
import android.util.ArrayMap;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystemProperties;

import java.util.Map;

@Implements(className = "android.os.SystemProperties")
public class SystemPropertiesTestImpl extends ShadowSystemProperties {

    private static Map<String, String> sProperties = new ArrayMap<>();

    @Implementation
    public static String get(String key) {
        String value = sProperties.get(key);
        if (!TextUtils.isEmpty(value)) {
            return value;
        } else {
            return ShadowSystemProperties.get(key);
        }
    }

    @Implementation
    public static String get(String key, String def) {
        String value = sProperties.get(key);
        if (!TextUtils.isEmpty(value)) {
            return value;
        } else {
            return ShadowSystemProperties.get(key, def);
        }
    }

    @Implementation
    public static void set(String key, String val) {
        sProperties.put(key, val);
    }

    public static synchronized void clear() {
        sProperties.clear();
    }
}
