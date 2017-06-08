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
import android.content.pm.*;
import android.content.pm.ServiceInfo;
import android.telephony.MbmsDownloadManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    public static void waitOnLatchWithTimeout(CountDownLatch l, long timeoutMs) {
        long endTime = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < endTime) {
            try {
                l.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // keep waiting
            }
            if (l.getCount() <= 0) {
                return;
            }
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
}
