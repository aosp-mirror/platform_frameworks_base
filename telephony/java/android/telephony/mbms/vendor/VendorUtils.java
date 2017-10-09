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

package android.telephony.mbms.vendor;

import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.telephony.MbmsDownloadSession;
import android.telephony.mbms.MbmsDownloadReceiver;

import java.io.File;
import java.util.List;

/**
 * Contains constants and utility methods for MBMS Download middleware apps to communicate with
 * frontend apps.
 * @hide
 */
//@SystemApi
public class VendorUtils {

    /**
     * The MBMS middleware should send this when a download of single file has completed or
     * failed. The only mandatory extra is
     * {@link MbmsDownloadSession#EXTRA_MBMS_DOWNLOAD_RESULT}
     * and the following are required when the download has completed:
     * {@link MbmsDownloadSession#EXTRA_MBMS_FILE_INFO}
     * {@link MbmsDownloadSession#EXTRA_MBMS_DOWNLOAD_REQUEST}
     * {@link #EXTRA_TEMP_LIST}
     * {@link #EXTRA_FINAL_URI}
     */
    public static final String ACTION_DOWNLOAD_RESULT_INTERNAL =
            "android.telephony.mbms.action.DOWNLOAD_RESULT_INTERNAL";

    /**
     * The MBMS middleware should send this when it wishes to request {@code content://} URIs to
     * serve as temp files for downloads or when it wishes to resume paused downloads. Mandatory
     * extras are
     * {@link #EXTRA_SERVICE_ID}
     *
     * Optional extras are
     * {@link #EXTRA_FD_COUNT} (0 if not present)
     * {@link #EXTRA_PAUSED_LIST} (empty if not present)
     */
    public static final String ACTION_FILE_DESCRIPTOR_REQUEST =
            "android.telephony.mbms.action.FILE_DESCRIPTOR_REQUEST";

    /**
     * The MBMS middleware should send this when it wishes to clean up temp  files in the app's
     * filesystem. Mandatory extras are:
     * {@link #EXTRA_TEMP_FILES_IN_USE}
     */
    public static final String ACTION_CLEANUP =
            "android.telephony.mbms.action.CLEANUP";

    /**
     * Extra containing a {@link List} of {@link Uri}s that were used as temp files for this
     * completed file. These {@link Uri}s should have scheme {@code file://}, and the temp
     * files will be deleted upon receipt of the intent.
     * May be null.
     */
    public static final String EXTRA_TEMP_LIST = "android.telephony.mbms.extra.TEMP_LIST";

    /**
     * Extra containing an integer indicating the number of temp files requested.
     */
    public static final String EXTRA_FD_COUNT = "android.telephony.mbms.extra.FD_COUNT";

    /**
     * Extra containing a list of {@link Uri}s that the middleware is requesting access to via
     * {@link #ACTION_FILE_DESCRIPTOR_REQUEST} in order to resume downloading. These {@link Uri}s
     * should have scheme {@code file://}.
     */
    public static final String EXTRA_PAUSED_LIST = "android.telephony.mbms.extra.PAUSED_LIST";

    /**
     * Extra containing a list of {@link android.telephony.mbms.UriPathPair}s, used in the
     * response to {@link #ACTION_FILE_DESCRIPTOR_REQUEST}. These are temp files that are meant
     * to be used for new file downloads.
     */
    public static final String EXTRA_FREE_URI_LIST = "android.telephony.mbms.extra.FREE_URI_LIST";

    /**
     * Extra containing a list of {@link android.telephony.mbms.UriPathPair}s, used in the
     * response to {@link #ACTION_FILE_DESCRIPTOR_REQUEST}. These
     * {@link android.telephony.mbms.UriPathPair}s contain {@code content://} URIs that provide
     * access to previously paused downloads.
     */
    public static final String EXTRA_PAUSED_URI_LIST =
            "android.telephony.mbms.extra.PAUSED_URI_LIST";

    /**
     * Extra containing a string that points to the middleware's knowledge of where the temp file
     * root for the app is. The path should be a canonical path as returned by
     * {@link File#getCanonicalPath()}
     */
    public static final String EXTRA_TEMP_FILE_ROOT =
            "android.telephony.mbms.extra.TEMP_FILE_ROOT";

    /**
     * Extra containing a list of {@link Uri}s indicating temp files which the middleware is
     * still using.
     */
    public static final String EXTRA_TEMP_FILES_IN_USE =
            "android.telephony.mbms.extra.TEMP_FILES_IN_USE";

    /**
     * Extra containing a single {@link Uri} indicating the path to the temp file in which the
     * decoded downloaded file resides. Must not be null.
     */
    public static final String EXTRA_FINAL_URI = "android.telephony.mbms.extra.FINAL_URI";

    /**
     * Extra containing a String representing a service ID, used by
     * file-descriptor requests and cleanup requests to specify which service they want to
     * request temp files or clean up temp files for, respectively.
     */
    public static final String EXTRA_SERVICE_ID =
            "android.telephony.mbms.extra.SERVICE_ID";

    /**
     * Retrieves the {@link ComponentName} for the {@link android.content.BroadcastReceiver} that
     * the various intents from the middleware should be targeted towards.
     * @param packageName The package name of the app.
     * @return The component name of the receiver that the middleware should send its intents to,
     * or null if the app didn't declare it in the manifest.
     */
    public static ComponentName getAppReceiverFromPackageName(Context context, String packageName) {
        ComponentName candidate = new ComponentName(packageName,
                MbmsDownloadReceiver.class.getCanonicalName());
        Intent queryIntent = new Intent();
        queryIntent.setComponent(candidate);
        List<ResolveInfo> receivers =
                context.getPackageManager().queryBroadcastReceivers(queryIntent, 0);
        if (receivers != null && receivers.size() > 0) {
            return candidate;
        }
        return null;
    }
}
