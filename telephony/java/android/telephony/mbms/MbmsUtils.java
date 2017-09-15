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
 * limitations under the License
 */

package android.telephony.mbms;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.*;
import android.content.pm.ServiceInfo;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * @hide
 */
public class MbmsUtils {
    private static final String LOG_TAG = "MbmsUtils";

    public static boolean isContainedIn(File parent, File child) {
        try {
            String parentPath = parent.getCanonicalPath();
            String childPath = child.getCanonicalPath();
            return childPath.startsWith(parentPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve canonical paths: " + e);
        }
    }

    public static ComponentName toComponentName(ComponentInfo ci) {
        return new ComponentName(ci.packageName, ci.name);
    }

    public static ServiceInfo getMiddlewareServiceInfo(Context context, String serviceAction) {
        // Query for the proper service
        PackageManager packageManager = context.getPackageManager();
        Intent queryIntent = new Intent();
        queryIntent.setAction(serviceAction);
        List<ResolveInfo> downloadServices = packageManager.queryIntentServices(queryIntent,
                PackageManager.MATCH_SYSTEM_ONLY);

        if (downloadServices == null || downloadServices.size() == 0) {
            Log.w(LOG_TAG, "No download services found, cannot get service info");
            return null;
        }

        if (downloadServices.size() > 1) {
            Log.w(LOG_TAG, "More than one download service found, cannot get unique service");
            return null;
        }
        return downloadServices.get(0).serviceInfo;
    }

    public static int startBinding(Context context, String serviceAction,
            ServiceConnection serviceConnection) {
        Intent bindIntent = new Intent();
        ServiceInfo mbmsServiceInfo =
                MbmsUtils.getMiddlewareServiceInfo(context, serviceAction);

        if (mbmsServiceInfo == null) {
            return MbmsErrors.ERROR_NO_UNIQUE_MIDDLEWARE;
        }

        bindIntent.setComponent(MbmsUtils.toComponentName(mbmsServiceInfo));

        context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        return MbmsErrors.SUCCESS;
    }

    /**
     * Returns a File linked to the directory used to store temp files for this file service
     */
    public static File getEmbmsTempFileDirForService(Context context, String serviceId) {
        File embmsTempFileDir = MbmsTempFileProvider.getEmbmsTempFileDir(context);

        return new File(embmsTempFileDir, serviceId);
    }
}
