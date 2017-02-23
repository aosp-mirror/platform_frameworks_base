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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.EphemeralIntentFilter;
import android.content.pm.EphemeralRequest;
import android.content.pm.EphemeralResolveInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.EphemeralResolveInfo.EphemeralDigest;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.server.pm.EphemeralResolverConnection.PhaseTwoCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** @hide */
public abstract class EphemeralResolver {
    public static AuxiliaryResolveInfo doEphemeralResolutionPhaseOne(Context context,
            EphemeralResolverConnection connection, EphemeralRequest requestObj) {
        final Intent intent = requestObj.origIntent;
        final EphemeralDigest digest =
                new EphemeralDigest(intent.getData().getHost(), 5 /*maxDigests*/);
        final int[] shaPrefix = digest.getDigestPrefix();
        final List<EphemeralResolveInfo> ephemeralResolveInfoList =
                connection.getEphemeralResolveInfoList(shaPrefix);
        if (ephemeralResolveInfoList == null || ephemeralResolveInfoList.size() == 0) {
            // No hash prefix match; there are no ephemeral apps for this domain.
            return null;
        }

        final String token = UUID.randomUUID().toString();
        return EphemeralResolver.filterEphemeralIntent(ephemeralResolveInfoList,
                intent, requestObj.resolvedType, requestObj.userId,
                intent.getPackage(), digest, token);
    }

    public static void doEphemeralResolutionPhaseTwo(Context context,
            EphemeralResolverConnection connection, EphemeralRequest requestObj,
            ActivityInfo ephemeralInstaller, Handler callbackHandler) {
        final Intent intent = requestObj.origIntent;
        final String hostName = intent.getData().getHost();
        final EphemeralDigest digest = new EphemeralDigest(hostName, 5 /*maxDigests*/);

        final PhaseTwoCallback callback = new PhaseTwoCallback() {
            @Override
            void onPhaseTwoResolved(EphemeralResolveInfo ephemeralResolveInfo,
                    int sequence) {
                final String packageName;
                final String splitName;
                final int versionCode;
                if (ephemeralResolveInfo != null) {
                    final ArrayList<EphemeralResolveInfo> ephemeralResolveInfoList =
                            new ArrayList<EphemeralResolveInfo>(1);
                    ephemeralResolveInfoList.add(ephemeralResolveInfo);
                    final AuxiliaryResolveInfo ephemeralIntentInfo =
                            EphemeralResolver.filterEphemeralIntent(
                                    ephemeralResolveInfoList, intent, null /*resolvedType*/,
                                    0 /*userId*/, intent.getPackage(), digest,
                                    requestObj.responseObj.token);
                    if (ephemeralIntentInfo != null
                            && ephemeralIntentInfo.resolveInfo != null) {
                        packageName = ephemeralIntentInfo.resolveInfo.getPackageName();
                        splitName = ephemeralIntentInfo.splitName;
                        versionCode = ephemeralIntentInfo.resolveInfo.getVersionCode();
                    } else {
                        packageName = null;
                        splitName = null;
                        versionCode = -1;
                    }
                } else {
                    packageName = null;
                    splitName = null;
                    versionCode = -1;
                }
                final Intent installerIntent = buildEphemeralInstallerIntent(
                        requestObj.origIntent,
                        requestObj.callingPackage,
                        requestObj.resolvedType,
                        requestObj.userId,
                        packageName,
                        splitName,
                        versionCode,
                        requestObj.responseObj.token,
                        false /*needsPhaseTwo*/);
                installerIntent.setComponent(new ComponentName(
                        ephemeralInstaller.packageName, ephemeralInstaller.name));
                context.startActivity(installerIntent);
            }
        };
        connection.getEphemeralIntentFilterList(
                hostName, callback, callbackHandler, 0 /*sequence*/);
    }

    /**
     * Builds and returns an intent to launch the ephemeral installer.
     */
    public static Intent buildEphemeralInstallerIntent(@NonNull Intent origIntent,
            @NonNull String callingPackage,
            @NonNull String resolvedType,
            int userId,
            @NonNull String ephemeralPackageName,
            @Nullable String ephemeralSplitName,
            int versionCode,
            @Nullable String token,
            boolean needsPhaseTwo) {
        // Construct the intent that launches the ephemeral installer
        int flags = origIntent.getFlags();
        final Intent intent = new Intent();
        intent.setFlags(flags
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if (token != null) {
            intent.putExtra(Intent.EXTRA_EPHEMERAL_TOKEN, token);
        }
        if (origIntent.getData() != null) {
            intent.putExtra(Intent.EXTRA_EPHEMERAL_HOSTNAME, origIntent.getData().getHost());
        }

        // We have all of the data we need; just start the installer without a second phase
        if (!needsPhaseTwo) {
            // Intent that is launched if the package couldn't be installed for any reason.
            final Intent failureIntent = new Intent(origIntent);
            failureIntent.setFlags(failureIntent.getFlags() | Intent.FLAG_IGNORE_EPHEMERAL);
            try {
                final IIntentSender failureIntentTarget = ActivityManager.getService()
                        .getIntentSender(
                                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                                null /*token*/, null /*resultWho*/, 1 /*requestCode*/,
                                new Intent[] { failureIntent },
                                new String[] { resolvedType },
                                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                                        | PendingIntent.FLAG_IMMUTABLE,
                                null /*bOptions*/, userId);
                intent.putExtra(Intent.EXTRA_EPHEMERAL_FAILURE,
                        new IntentSender(failureIntentTarget));
            } catch (RemoteException ignore) { /* ignore; same process */ }

            // Intent that is launched if the package was installed successfully.
            final Intent successIntent = new Intent(origIntent);
            try {
                final IIntentSender successIntentTarget = ActivityManager.getService()
                        .getIntentSender(
                                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                                null /*token*/, null /*resultWho*/, 0 /*requestCode*/,
                                new Intent[] { successIntent },
                                new String[] { resolvedType },
                                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                                        | PendingIntent.FLAG_IMMUTABLE,
                                null /*bOptions*/, userId);
                intent.putExtra(Intent.EXTRA_EPHEMERAL_SUCCESS,
                        new IntentSender(successIntentTarget));
            } catch (RemoteException ignore) { /* ignore; same process */ }

            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, ephemeralPackageName);
            intent.putExtra(Intent.EXTRA_SPLIT_NAME, ephemeralSplitName);
            intent.putExtra(Intent.EXTRA_VERSION_CODE, versionCode);
        }

        return intent;
    }

    private static AuxiliaryResolveInfo filterEphemeralIntent(
            List<EphemeralResolveInfo> ephemeralResolveInfoList,
            Intent intent, String resolvedType, int userId, String packageName,
            EphemeralDigest digest, String token) {
        final int[] shaPrefix = digest.getDigestPrefix();
        final byte[][] digestBytes = digest.getDigestBytes();
        // Go in reverse order so we match the narrowest scope first.
        for (int i = shaPrefix.length - 1; i >= 0 ; --i) {
            for (EphemeralResolveInfo ephemeralInfo : ephemeralResolveInfoList) {
                if (!Arrays.equals(digestBytes[i], ephemeralInfo.getDigestBytes())) {
                    continue;
                }
                if (packageName != null
                        && !packageName.equals(ephemeralInfo.getPackageName())) {
                    continue;
                }
                final List<EphemeralIntentFilter> ephemeralFilters =
                        ephemeralInfo.getIntentFilters();
                // No filters; we need to start phase two
                if (ephemeralFilters == null || ephemeralFilters.isEmpty()) {
                    return new AuxiliaryResolveInfo(ephemeralInfo,
                            new IntentFilter(Intent.ACTION_VIEW) /*intentFilter*/,
                            null /*splitName*/, token, true /*needsPhase2*/);
                }
                // We have a domain match; resolve the filters to see if anything matches.
                final PackageManagerService.EphemeralIntentResolver ephemeralResolver =
                        new PackageManagerService.EphemeralIntentResolver();
                for (int j = ephemeralFilters.size() - 1; j >= 0; --j) {
                    final EphemeralIntentFilter ephemeralFilter = ephemeralFilters.get(j);
                    final List<IntentFilter> splitFilters = ephemeralFilter.getFilters();
                    if (splitFilters == null || splitFilters.isEmpty()) {
                        continue;
                    }
                    for (int k = splitFilters.size() - 1; k >= 0; --k) {
                        final AuxiliaryResolveInfo intentInfo =
                                new AuxiliaryResolveInfo(ephemeralInfo,
                                        splitFilters.get(k), ephemeralFilter.getSplitName(),
                                        token, false /*needsPhase2*/);
                        ephemeralResolver.addFilter(intentInfo);
                    }
                }
                List<AuxiliaryResolveInfo> matchedResolveInfoList = ephemeralResolver.queryIntent(
                        intent, resolvedType, false /*defaultOnly*/, userId);
                if (!matchedResolveInfoList.isEmpty()) {
                    return matchedResolveInfoList.get(0);
                }
            }
        }
        // Hash or filter mis-match; no ephemeral apps for this domain.
        return null;
    }
}
