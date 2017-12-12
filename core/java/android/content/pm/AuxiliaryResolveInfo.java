/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Auxiliary application resolution response.
 * <p>
 * Used when resolution occurs, but, the target is not actually on the device.
 * This happens resolving instant apps that haven't been installed yet or if
 * the application consists of multiple feature splits and the needed split
 * hasn't been installed.
 * @hide
 */
public final class AuxiliaryResolveInfo extends IntentFilter {
    /** Resolved information returned from the external instant resolver */
    public final InstantAppResolveInfo resolveInfo;
    /** The resolved package. Copied from {@link #resolveInfo}. */
    public final String packageName;
    /** The activity to launch if there's an installation failure. */
    public final ComponentName installFailureActivity;
    /** The resolve split. Copied from the matched filter in {@link #resolveInfo}. */
    public final String splitName;
    /** Whether or not instant resolution needs the second phase */
    public final boolean needsPhaseTwo;
    /** Opaque token to track the instant application resolution */
    public final String token;
    /** The version code of the package */
    public final int versionCode;
    /** An intent to start upon failure to install */
    public final Intent failureIntent;

    /** Create a response for installing an instant application. */
    public AuxiliaryResolveInfo(@NonNull InstantAppResolveInfo resolveInfo,
            @NonNull IntentFilter orig,
            @Nullable String splitName,
            @NonNull String token,
            boolean needsPhase2,
            @Nullable Intent failureIntent) {
        super(orig);
        this.resolveInfo = resolveInfo;
        this.packageName = resolveInfo.getPackageName();
        this.splitName = splitName;
        this.token = token;
        this.needsPhaseTwo = needsPhase2;
        this.versionCode = resolveInfo.getVersionCode();
        this.failureIntent = failureIntent;
        this.installFailureActivity = null;
    }

    /** Create a response for installing a split on demand. */
    public AuxiliaryResolveInfo(@NonNull String packageName,
            @Nullable String splitName,
            @Nullable ComponentName failureActivity,
            int versionCode,
            @Nullable Intent failureIntent) {
        super();
        this.packageName = packageName;
        this.installFailureActivity = failureActivity;
        this.splitName = splitName;
        this.versionCode = versionCode;
        this.resolveInfo = null;
        this.token = null;
        this.needsPhaseTwo = false;
        this.failureIntent = failureIntent;
    }
}