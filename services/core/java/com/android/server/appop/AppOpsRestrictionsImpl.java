/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appop;

import android.annotation.RequiresPermission;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.PackageTagsList;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * Implementation for AppOpsService's app-op restrictions (global and user) storage and retrieval.
 */
public class AppOpsRestrictionsImpl implements AppOpsRestrictions {

    private static final int UID_ANY = -2;

    private Context mContext;
    private Handler mHandler;

    private AppOpsRestrictionRemovedListener mAppOpsRestrictionRemovedListener;

    // Map from (Object token) to (int code) to (boolean restricted)
    private final ArrayMap<Object, SparseBooleanArray> mGlobalRestrictions = new ArrayMap<>();

    // Map from (Object token) to (int userId) to (int code) to (boolean restricted)
    private final ArrayMap<Object, SparseArray<SparseBooleanArray>> mUserRestrictions =
            new ArrayMap<>();

    // Map from (Object token) to (int userId) to (PackageTagsList packageTagsList)
    private final ArrayMap<Object, SparseArray<PackageTagsList>>
            mUserRestrictionExcludedPackageTags = new ArrayMap<>();

    public AppOpsRestrictionsImpl(Context context, Handler handler,
            AppOpsRestrictionRemovedListener appOpsRestrictionRemovedListener) {
        mContext = context;
        mHandler = handler;
        mAppOpsRestrictionRemovedListener = appOpsRestrictionRemovedListener;
    }

    @Override
    public boolean setGlobalRestriction(Object clientToken, int code, boolean restricted) {
        if (restricted) {
            if (!mGlobalRestrictions.containsKey(clientToken)) {
                mGlobalRestrictions.put(clientToken, new SparseBooleanArray());
            }
            SparseBooleanArray restrictedCodes = mGlobalRestrictions.get(clientToken);
            Objects.requireNonNull(restrictedCodes);
            boolean changed = !restrictedCodes.get(code);
            restrictedCodes.put(code, true);
            return changed;
        } else {
            SparseBooleanArray restrictedCodes = mGlobalRestrictions.get(clientToken);
            if (restrictedCodes == null) {
                return false;
            }
            boolean changed = restrictedCodes.get(code);
            restrictedCodes.delete(code);
            if (restrictedCodes.size() == 0) {
                mGlobalRestrictions.remove(clientToken);
            }
            return changed;
        }
    }

    @Override
    public boolean getGlobalRestriction(Object clientToken, int code) {
        SparseBooleanArray restrictedCodes = mGlobalRestrictions.get(clientToken);
        if (restrictedCodes == null) {
            return false;
        }
        return restrictedCodes.get(code);
    }

    @Override
    public boolean hasGlobalRestrictions(Object clientToken) {
        return mGlobalRestrictions.containsKey(clientToken);
    }

    @Override
    public boolean clearGlobalRestrictions(Object clientToken) {
        return mGlobalRestrictions.remove(clientToken) != null;
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    @Override
    public boolean setUserRestriction(Object clientToken, int userId, int code,
            boolean restricted,
            PackageTagsList excludedPackageTags) {
        int[] userIds = resolveUserId(userId);
        boolean changed = false;
        for (int i = 0; i < userIds.length; i++) {
            changed |= putUserRestriction(clientToken, userIds[i], code, restricted);
            changed |= putUserRestrictionExclusions(clientToken, userIds[i],
                    excludedPackageTags);
        }
        return changed;
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    private int[] resolveUserId(int userId) {
        int[] userIds;
        if (userId == UserHandle.USER_ALL) {
            // TODO(b/162888972): this call is returning all users, not just live ones - we
            // need to either fix the method called, or rename the variable
            List<UserInfo> liveUsers = UserManager.get(mContext).getUsers();

            userIds = new int[liveUsers.size()];
            for (int i = 0; i < liveUsers.size(); i++) {
                userIds[i] = liveUsers.get(i).id;
            }
        } else {
            userIds = new int[]{userId};
        }
        return userIds;
    }

    @Override
    public boolean hasUserRestrictions(Object clientToken) {
        return mUserRestrictions.containsKey(clientToken);
    }

    private boolean getUserRestriction(Object clientToken, int userId, int code) {
        SparseArray<SparseBooleanArray> userIdRestrictedCodes =
                mUserRestrictions.get(clientToken);
        if (userIdRestrictedCodes == null) {
            return false;
        }
        SparseBooleanArray restrictedCodes = userIdRestrictedCodes.get(userId);
        if (restrictedCodes == null) {
            return false;
        }
        return restrictedCodes.get(code);
    }

    @Override
    public boolean getUserRestriction(Object clientToken, int userId, int code, String packageName,
            String attributionTag, boolean isCheckOp) {
        boolean restricted = getUserRestriction(clientToken, userId, code);
        if (!restricted) {
            return false;
        }

        PackageTagsList perUserExclusions = getUserRestrictionExclusions(clientToken, userId);
        if (perUserExclusions == null) {
            return true;
        }

        // TODO (b/240617242) add overload for checkOp to support attribution tags
        if (isCheckOp) {
            return !perUserExclusions.includes(packageName);
        }
        return !perUserExclusions.contains(packageName, attributionTag);
    }

    @Override
    public boolean clearUserRestrictions(Object clientToken) {
        boolean changed = false;
        SparseBooleanArray allUserRestrictedCodes = collectAllUserRestrictedCodes(clientToken);
        changed |= mUserRestrictions.remove(clientToken) != null;
        changed |= mUserRestrictionExcludedPackageTags.remove(clientToken) != null;
        notifyAllUserRestrictions(allUserRestrictedCodes);
        return changed;
    }

    private SparseBooleanArray collectAllUserRestrictedCodes(Object clientToken) {
        SparseBooleanArray allRestrictedCodes = new SparseBooleanArray();
        SparseArray<SparseBooleanArray> userIdRestrictedCodes = mUserRestrictions.get(clientToken);
        if (userIdRestrictedCodes == null) {
            return allRestrictedCodes;
        }
        int userIdRestrictedCodesSize = userIdRestrictedCodes.size();
        for (int i = 0; i < userIdRestrictedCodesSize; i++) {
            SparseBooleanArray restrictedCodes = userIdRestrictedCodes.valueAt(i);
            int restrictedCodesSize = restrictedCodes.size();
            for (int j = 0; j < restrictedCodesSize; j++) {
                int code = restrictedCodes.keyAt(j);
                allRestrictedCodes.put(code, true);
            }
        }
        return allRestrictedCodes;
    }

    private void notifyAllUserRestrictions(SparseBooleanArray allUserRestrictedCodes) {
        int restrictedCodesSize = allUserRestrictedCodes.size();
        for (int j = 0; j < restrictedCodesSize; j++) {
            int code = allUserRestrictedCodes.keyAt(j);
            mHandler.post(() -> mAppOpsRestrictionRemovedListener.onAppOpsRestrictionRemoved(code));
        }
    }

    @Override
    public boolean clearUserRestrictions(Object clientToken, Integer userId) {
        boolean changed = false;

        SparseArray<SparseBooleanArray> userIdRestrictedCodes =
                mUserRestrictions.get(clientToken);
        if (userIdRestrictedCodes != null) {
            changed |= userIdRestrictedCodes.contains(userId);
            userIdRestrictedCodes.remove(userId);
            if (userIdRestrictedCodes.size() == 0) {
                mUserRestrictions.remove(clientToken);
            }
        }

        SparseArray<PackageTagsList> userIdPackageTags =
                mUserRestrictionExcludedPackageTags.get(clientToken);
        if (userIdPackageTags != null) {
            changed |= userIdPackageTags.contains(userId);
            userIdPackageTags.remove(userId);
            if (userIdPackageTags.size() == 0) {
                mUserRestrictionExcludedPackageTags.remove(clientToken);
            }
        }

        return changed;
    }

    private boolean putUserRestriction(Object token, int userId, int code, boolean restricted) {
        boolean changed = false;
        if (restricted) {
            if (!mUserRestrictions.containsKey(token)) {
                mUserRestrictions.put(token, new SparseArray<>());
            }
            SparseArray<SparseBooleanArray> userIdRestrictedCodes = mUserRestrictions.get(token);
            Objects.requireNonNull(userIdRestrictedCodes);

            if (!userIdRestrictedCodes.contains(userId)) {
                userIdRestrictedCodes.put(userId, new SparseBooleanArray());
            }
            SparseBooleanArray restrictedCodes = userIdRestrictedCodes.get(userId);

            changed = !restrictedCodes.get(code);
            restrictedCodes.put(code, restricted);
        } else {
            SparseArray<SparseBooleanArray> userIdRestrictedCodes = mUserRestrictions.get(token);
            if (userIdRestrictedCodes == null) {
                return false;
            }
            SparseBooleanArray restrictedCodes = userIdRestrictedCodes.get(userId);
            if (restrictedCodes == null) {
                return false;
            }
            changed = restrictedCodes.get(code);
            restrictedCodes.delete(code);
            if (restrictedCodes.size() == 0) {
                userIdRestrictedCodes.remove(userId);
            }
            if (userIdRestrictedCodes.size() == 0) {
                mUserRestrictions.remove(token);
            }
        }
        return changed;
    }

    @Override
    public PackageTagsList getUserRestrictionExclusions(Object clientToken, int userId) {
        SparseArray<PackageTagsList> userIdPackageTags =
                mUserRestrictionExcludedPackageTags.get(clientToken);
        if (userIdPackageTags == null) {
            return null;
        }
        return userIdPackageTags.get(userId);
    }

    private boolean putUserRestrictionExclusions(Object token, int userId,
            PackageTagsList excludedPackageTags) {
        boolean addingExclusions = excludedPackageTags != null && !excludedPackageTags.isEmpty();
        if (addingExclusions) {
            if (!mUserRestrictionExcludedPackageTags.containsKey(token)) {
                mUserRestrictionExcludedPackageTags.put(token, new SparseArray<>());
            }
            SparseArray<PackageTagsList> userIdExcludedPackageTags =
                    mUserRestrictionExcludedPackageTags.get(token);
            Objects.requireNonNull(userIdExcludedPackageTags);

            userIdExcludedPackageTags.put(userId, excludedPackageTags);
            return true;
        } else {
            SparseArray<PackageTagsList> userIdExclusions =
                    mUserRestrictionExcludedPackageTags.get(token);
            if (userIdExclusions == null) {
                return false;
            }
            boolean changed = userIdExclusions.get(userId) != null;
            userIdExclusions.remove(userId);
            if (userIdExclusions.size() == 0) {
                mUserRestrictionExcludedPackageTags.remove(token);
            }
            return changed;
        }
    }

    @Override
    public void dumpRestrictions(PrintWriter pw, int code, String dumpPackage,
            boolean showUserRestrictions) {
        final int globalRestrictionCount = mGlobalRestrictions.size();
        for (int i = 0; i < globalRestrictionCount; i++) {
            Object token = mGlobalRestrictions.keyAt(i);
            SparseBooleanArray restrictedOps = mGlobalRestrictions.valueAt(i);

            pw.println("  Global restrictions for token " + token + ":");
            StringBuilder restrictedOpsValue = new StringBuilder();
            restrictedOpsValue.append("[");
            final int restrictedOpCount = restrictedOps.size();
            for (int j = 0; j < restrictedOpCount; j++) {
                if (restrictedOpsValue.length() > 1) {
                    restrictedOpsValue.append(", ");
                }
                restrictedOpsValue.append(AppOpsManager.opToName(restrictedOps.keyAt(j)));
            }
            restrictedOpsValue.append("]");
            pw.println("      Restricted ops: " + restrictedOpsValue);
        }

        if (!showUserRestrictions) {
            return;
        }

        final int userRestrictionCount = mUserRestrictions.size();
        for (int i = 0; i < userRestrictionCount; i++) {
            Object token = mUserRestrictions.keyAt(i);
            SparseArray<SparseBooleanArray> perUserRestrictions = mUserRestrictions.get(token);
            SparseArray<PackageTagsList> perUserExcludedPackageTags =
                    mUserRestrictionExcludedPackageTags.get(token);

            boolean printedTokenHeader = false;

            final int restrictionCount = perUserRestrictions != null
                    ? perUserRestrictions.size() : 0;
            if (restrictionCount > 0 && dumpPackage == null) {
                boolean printedOpsHeader = false;
                for (int j = 0; j < restrictionCount; j++) {
                    int userId = perUserRestrictions.keyAt(j);
                    SparseBooleanArray restrictedOps = perUserRestrictions.valueAt(j);
                    if (restrictedOps == null) {
                        continue;
                    }
                    if (code >= 0 && !restrictedOps.get(code)) {
                        continue;
                    }
                    if (!printedTokenHeader) {
                        pw.println("  User restrictions for token " + token + ":");
                        printedTokenHeader = true;
                    }
                    if (!printedOpsHeader) {
                        pw.println("      Restricted ops:");
                        printedOpsHeader = true;
                    }
                    StringBuilder restrictedOpsValue = new StringBuilder();
                    restrictedOpsValue.append("[");
                    final int restrictedOpCount = restrictedOps.size();
                    for (int k = 0; k < restrictedOpCount; k++) {
                        int restrictedOp = restrictedOps.keyAt(k);
                        if (restrictedOpsValue.length() > 1) {
                            restrictedOpsValue.append(", ");
                        }
                        restrictedOpsValue.append(AppOpsManager.opToName(restrictedOp));
                    }
                    restrictedOpsValue.append("]");
                    pw.print("        ");
                    pw.print("user: ");
                    pw.print(userId);
                    pw.print(" restricted ops: ");
                    pw.println(restrictedOpsValue);
                }
            }

            final int excludedPackageCount = perUserExcludedPackageTags != null
                    ? perUserExcludedPackageTags.size() : 0;
            if (excludedPackageCount > 0 && code < 0) {
                IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
                ipw.increaseIndent();
                boolean printedPackagesHeader = false;
                for (int j = 0; j < excludedPackageCount; j++) {
                    int userId = perUserExcludedPackageTags.keyAt(j);
                    PackageTagsList packageNames =
                            perUserExcludedPackageTags.valueAt(j);
                    if (packageNames == null) {
                        continue;
                    }
                    boolean hasPackage;
                    if (dumpPackage != null) {
                        hasPackage = packageNames.includes(dumpPackage);
                    } else {
                        hasPackage = true;
                    }
                    if (!hasPackage) {
                        continue;
                    }
                    if (!printedTokenHeader) {
                        ipw.println("User restrictions for token " + token + ":");
                        printedTokenHeader = true;
                    }

                    ipw.increaseIndent();
                    if (!printedPackagesHeader) {
                        ipw.println("Excluded packages:");
                        printedPackagesHeader = true;
                    }

                    ipw.increaseIndent();
                    ipw.print("user: ");
                    ipw.print(userId);
                    ipw.println(" packages: ");

                    ipw.increaseIndent();
                    packageNames.dump(ipw);

                    ipw.decreaseIndent();
                    ipw.decreaseIndent();
                    ipw.decreaseIndent();
                }
                ipw.decreaseIndent();
            }
        }
    }
}
