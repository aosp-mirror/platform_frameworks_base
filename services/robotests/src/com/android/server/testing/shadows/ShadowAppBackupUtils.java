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
 * limitations under the License
 */

package com.android.server.testing.shadows;

import android.annotation.Nullable;
import android.content.pm.PackageManager;

import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.utils.AppBackupUtils;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.function.Function;

@Implements(AppBackupUtils.class)
public class ShadowAppBackupUtils {
    public static Function<String, Boolean> sAppIsRunningAndEligibleForBackupWithTransport;
    static {
        reset();
    }

    @Implementation
    public static boolean appIsRunningAndEligibleForBackupWithTransport(
            @Nullable TransportClient transportClient, String packageName, PackageManager pm) {
        return sAppIsRunningAndEligibleForBackupWithTransport.apply(packageName);
    }

    public static void reset() {
        sAppIsRunningAndEligibleForBackupWithTransport = p -> true;
    }
}
