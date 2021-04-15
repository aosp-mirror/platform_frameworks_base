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

package android.net;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * Utility to obtain the {@link com.android.server.ConnectivityService} {@link Resources}, in the
 * ServiceConnectivityResources APK.
 * @hide
 */
public class ConnectivityResources {
    private static final String RESOURCES_APK_INTENT =
            "com.android.server.connectivity.intent.action.SERVICE_CONNECTIVITY_RESOURCES_APK";
    private static final String RES_PKG_DIR = "/apex/com.android.tethering/";

    @NonNull
    private final Context mContext;

    @Nullable
    private Context mResourcesContext = null;

    @Nullable
    private static Context sTestResourcesContext = null;

    public ConnectivityResources(Context context) {
        mContext = context;
    }

    /**
     * Convenience method to mock all resources for the duration of a test.
     *
     * Call with a null context to reset after the test.
     */
    @VisibleForTesting
    public static void setResourcesContextForTest(@Nullable Context testContext) {
        sTestResourcesContext = testContext;
    }

    /**
     * Get the {@link Context} of the resources package.
     */
    public synchronized Context getResourcesContext() {
        if (sTestResourcesContext != null) {
            return sTestResourcesContext;
        }

        if (mResourcesContext != null) {
            return mResourcesContext;
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

        mResourcesContext = pkgContext;
        return pkgContext;
    }

    /**
     * Get the {@link Resources} of the ServiceConnectivityResources APK.
     */
    public Resources get() {
        return getResourcesContext().getResources();
    }
}
