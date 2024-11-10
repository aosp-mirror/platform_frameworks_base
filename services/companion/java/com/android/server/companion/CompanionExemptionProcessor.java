/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.companion;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;

import static com.android.server.companion.utils.PackageUtils.getPackageInfo;

import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.companion.AssociationInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.net.NetworkPolicyManager;
import android.os.Binder;
import android.os.Environment;
import android.os.PowerExemptionManager;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.companion.association.AssociationStore;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressLint("LongLogTag")
public class CompanionExemptionProcessor {

    private static final String TAG = "CDM_CompanionExemptionProcessor";

    private static final String PREF_FILE_NAME = "companion_device_preferences.xml";
    private static final String PREF_KEY_AUTO_REVOKE_GRANTS_DONE = "auto_revoke_grants_done";

    private final Context mContext;
    private final PowerExemptionManager mPowerExemptionManager;
    private final AppOpsManager mAppOpsManager;
    private final PackageManagerInternal mPackageManager;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final ActivityManagerInternal mAmInternal;
    private final AssociationStore mAssociationStore;

    public CompanionExemptionProcessor(Context context, PowerExemptionManager powerExemptionManager,
            AppOpsManager appOpsManager, PackageManagerInternal packageManager,
            ActivityTaskManagerInternal atmInternal, ActivityManagerInternal amInternal,
            AssociationStore associationStore) {
        mContext = context;
        mPowerExemptionManager = powerExemptionManager;
        mAppOpsManager = appOpsManager;
        mPackageManager = packageManager;
        mAtmInternal = atmInternal;
        mAmInternal = amInternal;
        mAssociationStore = associationStore;

        mAssociationStore.registerLocalListener(new AssociationStore.OnChangeListener() {
            @Override
            public void onAssociationChanged(int changeType, AssociationInfo association) {
                final int userId = association.getUserId();
                final List<AssociationInfo> updatedAssociations =
                        mAssociationStore.getActiveAssociationsByUser(userId);

                updateAtm(userId, updatedAssociations);
            }
        });
    }

    /**
     * Update ActivityManager and ActivityTaskManager exemptions
     */
    public void updateAtm(int userId, List<AssociationInfo> associations) {
        final Set<Integer> companionAppUids = new ArraySet<>();
        for (AssociationInfo association : associations) {
            int uid = mPackageManager.getPackageUid(association.getPackageName(), 0, userId);
            if (uid >= 0) {
                companionAppUids.add(uid);
            }
        }
        if (mAtmInternal != null) {
            mAtmInternal.setCompanionAppUids(userId, companionAppUids);
        }
        if (mAmInternal != null) {
            // Make a copy of the set and send it to ActivityManager.
            mAmInternal.setCompanionAppUids(userId, new ArraySet<>(companionAppUids));
        }
    }

    /**
     * Update special access for the association's package
     */
    public void exemptPackage(int userId, String packageName, boolean hasPresentDevices) {
        Slog.i(TAG, "Exempting package [" + packageName + "]...");

        final PackageInfo packageInfo = getPackageInfo(mContext, userId, packageName);

        Binder.withCleanCallingIdentity(
                () -> exemptPackageAsSystem(userId, packageInfo, hasPresentDevices));
    }

    @SuppressLint("MissingPermission")
    private void exemptPackageAsSystem(int userId, PackageInfo packageInfo,
            boolean hasPresentDevices) {
        if (packageInfo == null) {
            return;
        }

        // If the app has run-in-bg permission and present devices, add it to power saver allowlist.
        if (containsEither(packageInfo.requestedPermissions,
                android.Manifest.permission.RUN_IN_BACKGROUND,
                android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND)
                && hasPresentDevices) {
            mPowerExemptionManager.addToPermanentAllowList(packageInfo.packageName);
        } else {
            try {
                mPowerExemptionManager.removeFromPermanentAllowList(packageInfo.packageName);
            } catch (UnsupportedOperationException e) {
                Slog.w(TAG, packageInfo.packageName + " can't be removed from power save"
                        + " allowlist. It might be due to the package being allowlisted by the"
                        + " system.");
            }
        }

        // If the app has run-in-bg permission and present device, allow metered network use.
        NetworkPolicyManager networkPolicyManager = NetworkPolicyManager.from(mContext);
        try {
            if (containsEither(packageInfo.requestedPermissions,
                    android.Manifest.permission.USE_DATA_IN_BACKGROUND,
                    android.Manifest.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND)
                    && hasPresentDevices) {
                networkPolicyManager.addUidPolicy(
                        packageInfo.applicationInfo.uid,
                        NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND);
            } else {
                networkPolicyManager.removeUidPolicy(
                        packageInfo.applicationInfo.uid,
                        NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND);
            }
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, e.getMessage());
        }

        updateAutoRevokeExemption(packageInfo.packageName, packageInfo.applicationInfo.uid,
                !mAssociationStore.getActiveAssociationsByPackage(userId,
                        packageInfo.packageName).isEmpty());
    }

    /**
     * Update auto revoke exemptions.
     * If the app has any association, exempt it from permission auto revoke.
     */
    public void updateAutoRevokeExemptions() {
        Slog.d(TAG, "maybeGrantAutoRevokeExemptions()");

        PackageManager pm = mContext.getPackageManager();
        for (int userId : LocalServices.getService(UserManagerInternal.class).getUserIds()) {
            SharedPreferences pref = mContext.getSharedPreferences(
                    new File(Environment.getUserSystemDirectory(userId), PREF_FILE_NAME),
                    Context.MODE_PRIVATE);
            if (pref.getBoolean(PREF_KEY_AUTO_REVOKE_GRANTS_DONE, false)) {
                continue;
            }

            try {
                final List<AssociationInfo> associations =
                        mAssociationStore.getActiveAssociationsByUser(userId);
                Set<Pair<String, Integer>> exemptedPackages = new HashSet<>();
                for (AssociationInfo a : associations) {
                    try {
                        int uid = pm.getPackageUidAsUser(a.getPackageName(), userId);
                        exemptedPackages.add(new Pair<>(a.getPackageName(), uid));
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.w(TAG, "Unknown companion package: " + a.getPackageName(), e);
                    }
                }
                for (Pair<String, Integer> exemptedPackage : exemptedPackages) {
                    updateAutoRevokeExemption(exemptedPackage.first, exemptedPackage.second, true);
                }
            } finally {
                pref.edit().putBoolean(PREF_KEY_AUTO_REVOKE_GRANTS_DONE, true).apply();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void updateAutoRevokeExemption(String packageName, int uid, boolean hasAssociations) {
        try {
            mAppOpsManager.setMode(
                    AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                    uid,
                    packageName,
                    hasAssociations ? MODE_IGNORED : MODE_ALLOWED);
        } catch (Exception e) {
            Slog.e(TAG, "Error while granting auto revoke exemption for " + packageName, e);
        }
    }

    private <T> boolean containsEither(T[] array, T a, T b) {
        return ArrayUtils.contains(array, a) || ArrayUtils.contains(array, b);
    }

}
