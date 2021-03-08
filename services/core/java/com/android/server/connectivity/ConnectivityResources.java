/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.util.Log;

import com.android.server.ConnectivityService;

import java.util.List;

/**
 * Utility to obtain the {@link ConnectivityService} {@link Resources}, in the
 * ServiceConnectivityResources APK.
 */
public class ConnectivityResources {
    private static final String RESOURCES_APK_INTENT =
            "com.android.server.connectivity.intent.action.SERVICE_CONNECTIVITY_RESOURCES_APK";
    private static final String RES_PKG_DIR = "/apex/com.android.tethering/";

    @NonNull
    private final Context mContext;

    @Nullable
    private Resources mResources = null;

    public ConnectivityResources(Context context) {
        mContext = context;
    }

    /**
     * Get the {@link Resources} of the ServiceConnectivityResources APK.
     */
    public synchronized Resources get() {
        if (mResources != null) {
            return mResources;
        }

        final List<ResolveInfo> pkgs = mContext.getPackageManager()
                .queryIntentActivities(new Intent(RESOURCES_APK_INTENT), MATCH_SYSTEM_ONLY);
        pkgs.removeIf(pkg -> !pkg.activityInfo.applicationInfo.sourceDir.startsWith(RES_PKG_DIR));
        if (pkgs.size() > 1) {
            Log.wtf(ConnectivityResources.class.getSimpleName(),
                    "More than one package found: " + pkgs);
        }
        if (pkgs.isEmpty()) {
            throw new IllegalStateException("No connectivity resource package found");
        }

        final Context pkgContext;
        try {
            pkgContext = mContext.createPackageContext(
                    pkgs.get(0).activityInfo.applicationInfo.packageName, 0 /* flags */);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Resolved package not found", e);
        }

        mResources = pkgContext.getResources();
        return mResources;
    }
}
