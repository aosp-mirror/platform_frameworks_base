/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import android.Manifest.permission;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Internal class for managing the primary network scorer application.
 *
 * @hide
 */
public final class NetworkScorerAppManager {
    private static final String TAG = "NetworkScorerAppManager";

    private static final Intent SCORE_INTENT =
            new Intent(NetworkScoreManager.ACTION_SCORE_NETWORKS);

    /** This class cannot be instantiated. */
    private NetworkScorerAppManager() {}

    /**
     * Returns the list of available scorer app package names.
     *
     * <p>A network scorer is any application which:
     * <ul>
     * <li>Declares the {@link android.Manifest.permission#SCORE_NETWORKS} permission.
     * <li>Includes a receiver for {@link NetworkScoreManager#ACTION_SCORE_NETWORKS} guarded by the
     *     {@link android.Manifest.permission#BROADCAST_SCORE_NETWORKS} permission.
     * </ul>
     *
     * @return the list of scorers, or the empty list if there are no valid scorers.
     */
    public static Collection<String> getAllValidScorers(Context context) {
        List<String> scorers = new ArrayList<>();

        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> receivers = pm.queryBroadcastReceivers(SCORE_INTENT, 0 /* flags */);
        for (ResolveInfo receiver : receivers) {
            // This field is a misnomer, see android.content.pm.ResolveInfo#activityInfo
            final ActivityInfo receiverInfo = receiver.activityInfo;
            if (receiverInfo == null) {
                // Should never happen with queryBroadcastReceivers, but invalid nonetheless.
                continue;
            }
            if (!permission.BROADCAST_SCORE_NETWORKS.equals(receiverInfo.permission)) {
                // Receiver doesn't require the BROADCAST_SCORE_NETWORKS permission, which means
                // anyone could trigger network scoring and flood the framework with score requests.
                continue;
            }
            if (pm.checkPermission(permission.SCORE_NETWORKS, receiverInfo.packageName) !=
                    PackageManager.PERMISSION_GRANTED) {
                // Application doesn't hold the SCORE_NETWORKS permission, so the user never
                // approved it as a network scorer.
                continue;
            }
            scorers.add(receiverInfo.packageName);
        }

        return scorers;
    }

    /**
     * Get the application package name to use for scoring networks.
     *
     * @return the scorer package or null if scoring is disabled (including if no scorer was ever
     *     selected) or if the previously-set scorer is no longer a valid scorer app (e.g. because
     *     it was disabled or uninstalled).
     */
    public static String getActiveScorer(Context context) {
        String scorerPackage = Settings.Global.getString(context.getContentResolver(),
                Global.NETWORK_SCORER_APP);
        Collection<String> applications = getAllValidScorers(context);
        if (isPackageValidScorer(applications, scorerPackage)) {
            return scorerPackage;
        } else {
            return null;
        }
    }

    /**
     * Set the specified package as the default scorer application.
     *
     * <p>The caller must have permission to write to {@link Settings.Global}.
     *
     * @param context the context of the calling application
     * @param packageName the packageName of the new scorer to use. If null, scoring will be
     *     disabled. Otherwise, the scorer will only be set if it is a valid scorer application.
     * @return true if the scorer was changed, or false if the package is not a valid scorer.
     */
    public static boolean setActiveScorer(Context context, String packageName) {
        String oldPackageName = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.NETWORK_SCORER_APP);
        if (TextUtils.equals(oldPackageName, packageName)) {
            // No change.
            return true;
        }

        Log.i(TAG, "Changing network scorer from " + oldPackageName + " to " + packageName);

        if (packageName == null) {
            Settings.Global.putString(context.getContentResolver(), Global.NETWORK_SCORER_APP,
                    null);
            return true;
        } else {
            // We only make the change if the new package is valid.
            Collection<String> applications = getAllValidScorers(context);
            if (isPackageValidScorer(applications, packageName)) {
                Settings.Global.putString(context.getContentResolver(),
                        Settings.Global.NETWORK_SCORER_APP, packageName);
                return true;
            } else {
                Log.w(TAG, "Requested network scorer is not valid: " + packageName);
                return false;
            }
        }
    }

    /** Determine whether the application with the given UID is the enabled scorer. */
    public static boolean isCallerActiveScorer(Context context, int callingUid) {
        String defaultApp = getActiveScorer(context);
        if (defaultApp == null) {
            return false;
        }
        AppOpsManager appOpsMgr = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        try {
            appOpsMgr.checkPackage(callingUid, defaultApp);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Returns true if the given package is a valid scorer. */
    private static boolean isPackageValidScorer(Collection<String> scorerPackageNames,
            String packageName) {
        return packageName != null && scorerPackageNames.contains(packageName);
    }
}
