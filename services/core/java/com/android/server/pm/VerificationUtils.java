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

package com.android.server.pm;

import static com.android.server.pm.PackageManagerService.PACKAGE_MIME_TYPE;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;

final class VerificationUtils {
    /**
     * The default maximum time to wait for the verification agent to return in
     * milliseconds.
     */
    private static final long DEFAULT_VERIFICATION_TIMEOUT = 10 * 1000;

    /**
     * The default maximum time to wait for the verification agent to return in
     * milliseconds.
     */
    private static final long DEFAULT_STREAMING_VERIFICATION_TIMEOUT = 3 * 1000;

    public static long getVerificationTimeout(Context context, boolean streaming) {
        if (streaming) {
            return getDefaultStreamingVerificationTimeout(context);
        }
        return getDefaultVerificationTimeout(context);
    }

    /**
     * Get the default verification agent timeout. Used for both the APK verifier and the
     * intent filter verifier.
     *
     * @return verification timeout in milliseconds
     */
    public static long getDefaultVerificationTimeout(Context context) {
        long timeout = Settings.Global.getLong(context.getContentResolver(),
                Settings.Global.PACKAGE_VERIFIER_TIMEOUT, DEFAULT_VERIFICATION_TIMEOUT);
        // The setting can be used to increase the timeout but not decrease it, since that is
        // equivalent to disabling the verifier.
        return Math.max(timeout, DEFAULT_VERIFICATION_TIMEOUT);
    }

    /**
     * Get the default verification agent timeout for streaming installations.
     *
     * @return verification timeout in milliseconds
     */
    public static long getDefaultStreamingVerificationTimeout(Context context) {
        long timeout = Settings.Global.getLong(context.getContentResolver(),
                Settings.Global.PACKAGE_STREAMING_VERIFIER_TIMEOUT,
                DEFAULT_STREAMING_VERIFICATION_TIMEOUT);
        // The setting can be used to increase the timeout but not decrease it, since that is
        // equivalent to disabling the verifier.
        return Math.max(timeout, DEFAULT_STREAMING_VERIFICATION_TIMEOUT);
    }

    public static void broadcastPackageVerified(int verificationId, Uri packageUri,
            int verificationCode, @Nullable String rootHashString, int dataLoaderType,
            UserHandle user, Context context) {
        final Intent intent = new Intent(Intent.ACTION_PACKAGE_VERIFIED);
        intent.setDataAndType(packageUri, PACKAGE_MIME_TYPE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(PackageManager.EXTRA_VERIFICATION_ID, verificationId);
        intent.putExtra(PackageManager.EXTRA_VERIFICATION_RESULT, verificationCode);
        if (rootHashString != null) {
            intent.putExtra(PackageManager.EXTRA_VERIFICATION_ROOT_HASH, rootHashString);
        }
        intent.putExtra(PackageInstaller.EXTRA_DATA_LOADER_TYPE, dataLoaderType);

        context.sendBroadcastAsUser(intent, user,
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT);
    }
}
