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

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_INSTANT_APP_RESOLUTION_PHASE_ONE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_INSTANT_APP_RESOLUTION_PHASE_TWO;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_INSTANT_APP_LAUNCH_TOKEN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_INSTANT_APP_RESOLUTION_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_INSTANT_APP_RESOLUTION_STATUS;

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
import android.content.pm.InstantAppRequest;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.InstantAppIntentFilter;
import android.content.pm.InstantAppResolveInfo;
import android.content.pm.InstantAppResolveInfo.InstantAppDigest;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.server.pm.EphemeralResolverConnection.PhaseTwoCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** @hide */
public abstract class InstantAppResolver {
    private static final boolean DEBUG_EPHEMERAL = Build.IS_DEBUGGABLE;
    private static final String TAG = "PackageManager";

    private static int RESOLUTION_SUCCESS = 0;
    private static int RESOLUTION_FAILURE = 1;

    private static MetricsLogger sMetricsLogger;
    private static MetricsLogger getLogger() {
        if (sMetricsLogger == null) {
            sMetricsLogger = new MetricsLogger();
        }
        return sMetricsLogger;
    }

    public static AuxiliaryResolveInfo doInstantAppResolutionPhaseOne(Context context,
            EphemeralResolverConnection connection, InstantAppRequest requestObj) {
        if (DEBUG_EPHEMERAL) {
            Log.d(TAG, "Resolving phase 1");
        }
        final long startTime = System.currentTimeMillis();
        final String token = UUID.randomUUID().toString();
        final Intent intent = requestObj.origIntent;
        final InstantAppDigest digest =
                new InstantAppDigest(intent.getData().getHost(), 5 /*maxDigests*/);
        final int[] shaPrefix = digest.getDigestPrefix();
        final List<InstantAppResolveInfo> instantAppResolveInfoList =
                connection.getInstantAppResolveInfoList(shaPrefix, token);

        final AuxiliaryResolveInfo resolveInfo;
        if (instantAppResolveInfoList == null || instantAppResolveInfoList.size() == 0) {
            // No hash prefix match; there are no instant apps for this domain.
            if (DEBUG_EPHEMERAL) {
                Log.d(TAG, "No results returned");
            }
            resolveInfo = null;
        } else {
            resolveInfo = InstantAppResolver.filterInstantAppIntent(instantAppResolveInfoList,
                    intent, requestObj.resolvedType, requestObj.userId,
                    intent.getPackage(), digest, token);
        }

        logMetrics(ACTION_INSTANT_APP_RESOLUTION_PHASE_ONE, startTime, token,
                resolveInfo != null ? RESOLUTION_SUCCESS : RESOLUTION_FAILURE);

        return resolveInfo;
    }

    public static void doInstantAppResolutionPhaseTwo(Context context,
            EphemeralResolverConnection connection, InstantAppRequest requestObj,
            ActivityInfo instantAppInstaller, Handler callbackHandler) {
        if (DEBUG_EPHEMERAL) {
            Log.d(TAG, "Resolving phase 2");
        }
        final long startTime = System.currentTimeMillis();
        final Intent intent = requestObj.origIntent;
        final String hostName = intent.getData().getHost();
        final InstantAppDigest digest = new InstantAppDigest(hostName, 5 /*maxDigests*/);
        final int[] shaPrefix = digest.getDigestPrefix();
        final String token = requestObj.responseObj.token;

        final PhaseTwoCallback callback = new PhaseTwoCallback() {
            @Override
            void onPhaseTwoResolved(List<InstantAppResolveInfo> instantAppResolveInfoList,
                    long startTime) {
                final String packageName;
                final String splitName;
                final int versionCode;
                if (instantAppResolveInfoList != null && instantAppResolveInfoList.size() > 0) {
                    final AuxiliaryResolveInfo instantAppIntentInfo =
                            InstantAppResolver.filterInstantAppIntent(
                                    instantAppResolveInfoList, intent, null /*resolvedType*/,
                                    0 /*userId*/, intent.getPackage(), digest, token);
                    if (instantAppIntentInfo != null
                            && instantAppIntentInfo.resolveInfo != null) {
                        packageName = instantAppIntentInfo.resolveInfo.getPackageName();
                        splitName = instantAppIntentInfo.splitName;
                        versionCode = instantAppIntentInfo.resolveInfo.getVersionCode();
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
                        token,
                        false /*needsPhaseTwo*/);
                installerIntent.setComponent(new ComponentName(
                        instantAppInstaller.packageName, instantAppInstaller.name));

                logMetrics(ACTION_INSTANT_APP_RESOLUTION_PHASE_TWO, startTime, token,
                        packageName != null ? RESOLUTION_SUCCESS : RESOLUTION_FAILURE);

                context.startActivity(installerIntent);
            }
        };
        connection.getInstantAppIntentFilterList(
                shaPrefix, token, hostName, callback, callbackHandler, startTime);
    }

    /**
     * Builds and returns an intent to launch the instant installer.
     */
    public static Intent buildEphemeralInstallerIntent(@NonNull Intent origIntent,
            @NonNull String callingPackage,
            @NonNull String resolvedType,
            int userId,
            @NonNull String instantAppPackageName,
            @Nullable String instantAppSplitName,
            int versionCode,
            @Nullable String token,
            boolean needsPhaseTwo) {
        // Construct the intent that launches the instant installer
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

            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, instantAppPackageName);
            intent.putExtra(Intent.EXTRA_SPLIT_NAME, instantAppSplitName);
            intent.putExtra(Intent.EXTRA_VERSION_CODE, versionCode);
        }

        return intent;
    }

    private static AuxiliaryResolveInfo filterInstantAppIntent(
            List<InstantAppResolveInfo> instantAppResolveInfoList,
            Intent intent, String resolvedType, int userId, String packageName,
            InstantAppDigest digest, String token) {
        final int[] shaPrefix = digest.getDigestPrefix();
        final byte[][] digestBytes = digest.getDigestBytes();
        // Go in reverse order so we match the narrowest scope first.
        for (int i = shaPrefix.length - 1; i >= 0 ; --i) {
            for (InstantAppResolveInfo instantAppInfo : instantAppResolveInfoList) {
                if (!Arrays.equals(digestBytes[i], instantAppInfo.getDigestBytes())) {
                    continue;
                }
                if (packageName != null
                        && !packageName.equals(instantAppInfo.getPackageName())) {
                    continue;
                }
                final List<InstantAppIntentFilter> instantAppFilters =
                        instantAppInfo.getIntentFilters();
                // No filters; we need to start phase two
                if (instantAppFilters == null || instantAppFilters.isEmpty()) {
                    if (DEBUG_EPHEMERAL) {
                        Log.d(TAG, "No app filters; go to phase 2");
                    }
                    return new AuxiliaryResolveInfo(instantAppInfo,
                            new IntentFilter(Intent.ACTION_VIEW) /*intentFilter*/,
                            null /*splitName*/, token, true /*needsPhase2*/);
                }
                // We have a domain match; resolve the filters to see if anything matches.
                final PackageManagerService.EphemeralIntentResolver instantAppResolver =
                        new PackageManagerService.EphemeralIntentResolver();
                for (int j = instantAppFilters.size() - 1; j >= 0; --j) {
                    final InstantAppIntentFilter instantAppFilter = instantAppFilters.get(j);
                    final List<IntentFilter> splitFilters = instantAppFilter.getFilters();
                    if (splitFilters == null || splitFilters.isEmpty()) {
                        continue;
                    }
                    for (int k = splitFilters.size() - 1; k >= 0; --k) {
                        final AuxiliaryResolveInfo intentInfo =
                                new AuxiliaryResolveInfo(instantAppInfo,
                                        splitFilters.get(k), instantAppFilter.getSplitName(),
                                        token, false /*needsPhase2*/);
                        instantAppResolver.addFilter(intentInfo);
                    }
                }
                List<AuxiliaryResolveInfo> matchedResolveInfoList = instantAppResolver.queryIntent(
                        intent, resolvedType, false /*defaultOnly*/, userId);
                if (!matchedResolveInfoList.isEmpty()) {
                    if (DEBUG_EPHEMERAL) {
                        final AuxiliaryResolveInfo info = matchedResolveInfoList.get(0);
                        Log.d(TAG, "Found match;"
                                + " package: " + info.packageName
                                + ", split: " + info.splitName
                                + ", versionCode: " + info.versionCode);
                    }
                    return matchedResolveInfoList.get(0);
                }
            }
        }
        // Hash or filter mis-match; no instant apps for this domain.
        return null;
    }

    private static void logMetrics(int action, long startTime, String token, int status) {
        final LogMaker logMaker = new LogMaker(action)
                .setType(MetricsProto.MetricsEvent.TYPE_ACTION)
                .addTaggedData(FIELD_INSTANT_APP_RESOLUTION_DELAY_MS,
                        new Long(System.currentTimeMillis() - startTime))
                .addTaggedData(FIELD_INSTANT_APP_LAUNCH_TOKEN, token)
                .addTaggedData(FIELD_INSTANT_APP_RESOLUTION_STATUS, new Integer(status));
        getLogger().write(logMaker);
    }
}
