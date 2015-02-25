/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.statementservice.retriever;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources.NotFoundException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Class that provides information about an android app from {@link PackageManager}.
 *
 * Visible for testing.
 *
 * @hide
 */
public class AndroidPackageInfoFetcher {

    /**
     * The name of the metadata tag in AndroidManifest.xml that stores the associated asset array
     * ID. The metadata tag should use the android:resource attribute to point to an array resource
     * that contains the associated assets.
     */
    private static final String ASSOCIATED_ASSETS_KEY = "associated_assets";

    private Context mContext;

    public AndroidPackageInfoFetcher(Context context) {
        mContext = context;
    }

    /**
     * Returns the Sha-256 fingerprints of all certificates from the specified package as a list of
     * upper case HEX Strings with bytes separated by colons. Given an app {@link
     * android.content.pm.Signature}, the fingerprint can be computed as {@link
     * Utils#computeNormalizedSha256Fingerprint} {@code(signature.toByteArray())}.
     *
     * <p>Given a signed APK, Java 7's commandline keytool can compute the fingerprint using: {@code
     * keytool -list -printcert -jarfile signed_app.apk}
     *
     * <p>Example: "10:39:38:EE:45:37:E5:9E:8E:E7:92:F6:54:50:4F:B8:34:6F:C6:B3:46:D0:BB:C4:41:5F:C3:39:FC:FC:8E:C1"
     *
     * @throws NameNotFoundException if an app with packageName is not installed on the device.
     */
    public List<String> getCertFingerprints(String packageName) throws NameNotFoundException {
        return Utils.getCertFingerprintsFromPackageManager(packageName, mContext);
    }

    /**
     * Returns all statements that the specified package makes in its AndroidManifest.xml.
     *
     * @throws NameNotFoundException if the app is not installed on the device.
     */
    public List<String> getStatements(String packageName) throws NameNotFoundException {
        PackageInfo packageInfo = mContext.getPackageManager().getPackageInfo(
                packageName, PackageManager.GET_META_DATA);
        ApplicationInfo appInfo = packageInfo.applicationInfo;
        if (appInfo.metaData == null) {
            return Collections.<String>emptyList();
        }
        int tokenResourceId = appInfo.metaData.getInt(ASSOCIATED_ASSETS_KEY);
        if (tokenResourceId == 0) {
            return Collections.<String>emptyList();
        }
        try {
            return Arrays.asList(
                    mContext.getPackageManager().getResourcesForApplication(packageName)
                    .getStringArray(tokenResourceId));
        } catch (NotFoundException e) {
            return Collections.<String>emptyList();
        }
    }
}
