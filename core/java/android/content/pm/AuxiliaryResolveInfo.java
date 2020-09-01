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
import android.os.Bundle;

import java.util.Collections;
import java.util.List;

/**
 * Auxiliary application resolution response.
 * <p>
 * Used when resolution occurs, but, the target is not actually on the device.
 * This happens resolving instant apps that haven't been installed yet or if
 * the application consists of multiple feature splits and the needed split
 * hasn't been installed.
 * @hide
 */
public final class AuxiliaryResolveInfo {
    /** The activity to launch if there's an installation failure. */
    public final ComponentName installFailureActivity;
    /** Whether or not instant resolution needs the second phase */
    public final boolean needsPhaseTwo;
    /** Opaque token to track the instant application resolution */
    public final String token;
    /** An intent to start upon failure to install */
    public final Intent failureIntent;
    /** The matching filters for this resolve info. */
    public final List<AuxiliaryFilter> filters;
    /** Stored {@link InstantAppRequest#hostDigestPrefixSecure} to prevent re-generation */
    public final int[] hostDigestPrefixSecure;

    /** Create a response for installing an instant application. */
    public AuxiliaryResolveInfo(@NonNull String token,
            boolean needsPhase2,
            @Nullable Intent failureIntent,
            @Nullable List<AuxiliaryFilter> filters,
            @Nullable int[] hostDigestPrefix) {
        this.token = token;
        this.needsPhaseTwo = needsPhase2;
        this.failureIntent = failureIntent;
        this.filters = filters;
        this.installFailureActivity = null;
        this.hostDigestPrefixSecure = hostDigestPrefix;
    }

    /** Create a response for installing a split on demand. */
    public AuxiliaryResolveInfo(@Nullable ComponentName failureActivity,
            @Nullable Intent failureIntent,
            @Nullable List<AuxiliaryFilter> filters) {
        super();
        this.installFailureActivity = failureActivity;
        this.filters = filters;
        this.token = null;
        this.needsPhaseTwo = false;
        this.failureIntent = failureIntent;
        this.hostDigestPrefixSecure = null;
    }

    /** Create a response for installing a split on demand. */
    public AuxiliaryResolveInfo(@Nullable ComponentName failureActivity,
            String packageName, long versionCode, String splitName) {
        this(failureActivity, null, Collections.singletonList(
                new AuxiliaryResolveInfo.AuxiliaryFilter(packageName, versionCode, splitName)));
    }

    /** @hide */
    public static final class AuxiliaryFilter extends IntentFilter {
        /** Resolved information returned from the external instant resolver */
        public final InstantAppResolveInfo resolveInfo;
        /** The resolved package. Copied from {@link #resolveInfo}. */
        public final String packageName;
        /** The version code of the package */
        public final long versionCode;
        /** The resolve split. Copied from the matched filter in {@link #resolveInfo}. */
        public final String splitName;
        /** The extras to pass on to the installer for this filter. */
        public final Bundle extras;

        public AuxiliaryFilter(IntentFilter orig, InstantAppResolveInfo resolveInfo,
                String splitName, Bundle extras) {
            super(orig);
            this.resolveInfo = resolveInfo;
            this.packageName = resolveInfo.getPackageName();
            this.versionCode = resolveInfo.getLongVersionCode();
            this.splitName = splitName;
            this.extras = extras;
        }

        public AuxiliaryFilter(InstantAppResolveInfo resolveInfo,
                String splitName, Bundle extras) {
            this.resolveInfo = resolveInfo;
            this.packageName = resolveInfo.getPackageName();
            this.versionCode = resolveInfo.getLongVersionCode();
            this.splitName = splitName;
            this.extras = extras;
        }

        public AuxiliaryFilter(String packageName, long versionCode, String splitName) {
            this.resolveInfo = null;
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.splitName = splitName;
            this.extras = null;
        }

        @Override
        public String toString() {
            return "AuxiliaryFilter{"
                    + "packageName='" + packageName + '\''
                    + ", versionCode=" + versionCode
                    + ", splitName='" + splitName + '\'' + '}';
        }
    }
}
