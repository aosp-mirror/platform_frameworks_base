/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.testutils.shadow;

import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;

import android.content.ContentResolver;
import android.provider.Settings;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

@Implements(value = Settings.Secure.class)
public class ShadowSecure extends ShadowSettings.ShadowSecure {
    @Implementation(minSdk = JELLY_BEAN_MR1)
    public static boolean putStringForUser(ContentResolver cr, String name, String value,
            int userHandle) {
        return putString(cr, name, value);
    }
}
