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
package com.android.server.pm.parsing;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.compat.IPlatformCompat;
import com.android.internal.pm.parsing.PackageParser2;
import com.android.internal.pm.pkg.parsing.ParsingUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.PackageManagerService;

import java.util.Set;

public class PackageParserUtils {
    /**
     * For parsing inside the system server but outside of {@link PackageManagerService}.
     * Generally used for parsing information in an APK that hasn't been installed yet.
     *
     * This must be called inside the system process as it relies on {@link ServiceManager}.
     */
    @NonNull
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public static PackageParser2 forParsingFileWithDefaults() {
        IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        return new PackageParser2(null /* separateProcesses */, null /* displayMetrics */,
                null /* cacheDir */, new PackageParser2.Callback() {
            @Override
            @SuppressLint("AndroidFrameworkRequiresPermission")
            public boolean isChangeEnabled(long changeId, @NonNull ApplicationInfo appInfo) {
                try {
                    return platformCompat.isChangeEnabled(changeId, appInfo);
                } catch (Exception e) {
                    // This shouldn't happen, but assume enforcement if it does
                    Slog.wtf(ParsingUtils.TAG, "IPlatformCompat query failed", e);
                    return true;
                }
            }

            @Override
            public boolean hasFeature(String feature) {
                // Assume the device doesn't support anything. This will affect permission parsing
                // and will force <uses-permission/> declarations to include all requiredNotFeature
                // permissions and exclude all requiredFeature permissions. This mirrors the old
                // behavior.
                return false;
            }

            @Override
            public Set<String> getHiddenApiWhitelistedApps() {
                return SystemConfig.getInstance().getHiddenApiWhitelistedApps();
            }

            @Override
            public Set<String> getInstallConstraintsAllowlist() {
                return SystemConfig.getInstance().getInstallConstraintsAllowlist();
            }
        });
    }
}
