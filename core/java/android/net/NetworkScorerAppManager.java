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

import android.Manifest;
import android.Manifest.permission;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Internal class for managing the primary network scorer application.
 *
 * TODO: Rename this to something more generic.
 *
 * @hide
 */
public final class NetworkScorerAppManager {
    private static final String TAG = "NetworkScorerAppManager";

    private static final Intent SCORE_INTENT =
            new Intent(NetworkScoreManager.ACTION_SCORE_NETWORKS);

    /** This class cannot be instantiated. */
    private NetworkScorerAppManager() {}

    public static class NetworkScorerAppData {
        /** Package name of this scorer app. */
        public final String mPackageName;

        /** UID of the scorer app. */
        public final int mPackageUid;

        /** Name of this scorer app for display. */
        public final CharSequence mScorerName;

        /**
         * Optional class name of a configuration activity. Null if none is set.
         *
         * @see NetworkScoreManager#ACTION_CUSTOM_ENABLE
         */
        public final String mConfigurationActivityClassName;

        /**
         * Optional class name of the scoring service we can bind to. Null if none is set.
         */
        public final String mScoringServiceClassName;

        public NetworkScorerAppData(String packageName, int packageUid, CharSequence scorerName,
                @Nullable String configurationActivityClassName,
                @Nullable String scoringServiceClassName) {
            mScorerName = scorerName;
            mPackageName = packageName;
            mPackageUid = packageUid;
            mConfigurationActivityClassName = configurationActivityClassName;
            mScoringServiceClassName = scoringServiceClassName;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("NetworkScorerAppData{");
            sb.append("mPackageName='").append(mPackageName).append('\'');
            sb.append(", mPackageUid=").append(mPackageUid);
            sb.append(", mScorerName=").append(mScorerName);
            sb.append(", mConfigurationActivityClassName='").append(mConfigurationActivityClassName)
                    .append('\'');
            sb.append(", mScoringServiceClassName='").append(mScoringServiceClassName).append('\'');
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * Returns the list of available scorer apps.
     *
     * <p>A network scorer is any application which:
     * <ul>
     * <li>Declares the {@link android.Manifest.permission#SCORE_NETWORKS} permission.
     * <li>Includes a receiver for {@link NetworkScoreManager#ACTION_SCORE_NETWORKS} guarded by the
     *     {@link android.Manifest.permission#BROADCAST_NETWORK_PRIVILEGED} permission.
     * </ul>
     *
     * @return the list of scorers, or the empty list if there are no valid scorers.
     */
    public static Collection<NetworkScorerAppData> getAllValidScorers(Context context) {
        // Network scorer apps can only run as the primary user so exit early if we're not the
        // primary user.
        if (UserHandle.getCallingUserId() != UserHandle.USER_SYSTEM) {
            return Collections.emptyList();
        }

        List<NetworkScorerAppData> scorers = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        // Only apps installed under the primary user of the device can be scorers.
        // TODO: http://b/23422763
        List<ResolveInfo> receivers =
                pm.queryBroadcastReceiversAsUser(SCORE_INTENT, 0 /* flags */, UserHandle.USER_SYSTEM);
        for (ResolveInfo receiver : receivers) {
            // This field is a misnomer, see android.content.pm.ResolveInfo#activityInfo
            final ActivityInfo receiverInfo = receiver.activityInfo;
            if (receiverInfo == null) {
                // Should never happen with queryBroadcastReceivers, but invalid nonetheless.
                continue;
            }
            if (!permission.BROADCAST_NETWORK_PRIVILEGED.equals(receiverInfo.permission)) {
                // Receiver doesn't require the BROADCAST_NETWORK_PRIVILEGED permission, which
                // means anyone could trigger network scoring and flood the framework with score
                // requests.
                continue;
            }
            if (pm.checkPermission(permission.SCORE_NETWORKS, receiverInfo.packageName) !=
                    PackageManager.PERMISSION_GRANTED) {
                // Application doesn't hold the SCORE_NETWORKS permission, so the user never
                // approved it as a network scorer.
                continue;
            }

            // Optionally, this package may specify a configuration activity.
            String configurationActivityClassName = null;
            Intent intent = new Intent(NetworkScoreManager.ACTION_CUSTOM_ENABLE);
            intent.setPackage(receiverInfo.packageName);
            List<ResolveInfo> configActivities = pm.queryIntentActivities(intent, 0 /* flags */);
            if (configActivities != null && !configActivities.isEmpty()) {
                ActivityInfo activityInfo = configActivities.get(0).activityInfo;
                if (activityInfo != null) {
                    configurationActivityClassName = activityInfo.name;
                }
            }

            // Find the scoring service class we can bind to, if any.
            String scoringServiceClassName = null;
            Intent serviceIntent = new Intent(NetworkScoreManager.ACTION_SCORE_NETWORKS);
            serviceIntent.setPackage(receiverInfo.packageName);
            ResolveInfo resolveServiceInfo = pm.resolveService(serviceIntent, 0 /* flags */);
            if (resolveServiceInfo != null && resolveServiceInfo.serviceInfo != null) {
                scoringServiceClassName = resolveServiceInfo.serviceInfo.name;
            }

            // NOTE: loadLabel will attempt to load the receiver's label and fall back to the
            // app label if none is present.
            scorers.add(new NetworkScorerAppData(receiverInfo.packageName,
                    receiverInfo.applicationInfo.uid, receiverInfo.loadLabel(pm),
                    configurationActivityClassName, scoringServiceClassName));
        }

        return scorers;
    }

    /**
     * Get the application to use for scoring networks.
     *
     * @return the scorer app info or null if scoring is disabled (including if no scorer was ever
     *     selected) or if the previously-set scorer is no longer a valid scorer app (e.g. because
     *     it was disabled or uninstalled).
     */
    public static NetworkScorerAppData getActiveScorer(Context context) {
        String scorerPackage = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.NETWORK_SCORER_APP);
        return getScorer(context, scorerPackage);
    }

    /**
     * Set the specified package as the default scorer application.
     *
     * <p>The caller must have permission to write to {@link android.provider.Settings.Global}.
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
            Settings.Global.putString(context.getContentResolver(),
                    Settings.Global.NETWORK_SCORER_APP, null);
            return true;
        } else {
            // We only make the change if the new package is valid.
            if (getScorer(context, packageName) != null) {
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
        NetworkScorerAppData defaultApp = getActiveScorer(context);
        if (defaultApp == null) {
            return false;
        }
        if (callingUid != defaultApp.mPackageUid) {
            return false;
        }
        // To be extra safe, ensure the caller holds the SCORE_NETWORKS permission. It always
        // should, since it couldn't become the active scorer otherwise, but this can't hurt.
        return context.checkCallingPermission(Manifest.permission.SCORE_NETWORKS) ==
                PackageManager.PERMISSION_GRANTED;
    }

    /** Returns the {@link NetworkScorerAppData} for the given app, or null if it's not a scorer. */
    public static NetworkScorerAppData getScorer(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        Collection<NetworkScorerAppData> applications = getAllValidScorers(context);
        for (NetworkScorerAppData app : applications) {
            if (packageName.equals(app.mPackageName)) {
                return app;
            }
        }
        return null;
    }
}
