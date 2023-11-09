/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.content.pm.PackageManager.PROPERTY_LEGACY_UPDATE_OWNERSHIP_DENYLIST;

import static com.android.server.pm.PackageManagerService.TAG;

import android.Manifest;
import android.app.ResourcesManager;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.component.ParsedUsesPermission;

import org.xmlpull.v1.XmlPullParser;

import java.util.List;

/** Helper class for managing update ownership and optouts for the feature. */
public class UpdateOwnershipHelper {

    // Called out in PackageManager.PROPERTY_LEGACY_UPDATE_OWNERSHIP_DENYLIST docs
    private static final int MAX_DENYLIST_SIZE = 500;
    private static final String TAG_OWNERSHIP_OPT_OUT = "deny-ownership";
    private final ArrayMap<String, ArraySet<String>> mUpdateOwnerOptOutsToOwners =
            new ArrayMap<>(200);

    private final Object mLock = new Object();

    static boolean hasValidOwnershipDenyList(PackageSetting pkgSetting) {
        AndroidPackage pkg = pkgSetting.getPkg();
        // we're checking for uses-permission for these priv permissions instead of grant as we're
        // only considering system apps to begin with, so presumed to be granted.
        return pkg != null
                && (pkgSetting.isSystem() || pkgSetting.isUpdatedSystemApp())
                && pkg.getProperties().containsKey(PROPERTY_LEGACY_UPDATE_OWNERSHIP_DENYLIST)
                && usesAnyPermission(pkg,
                        Manifest.permission.INSTALL_PACKAGES,
                        Manifest.permission.INSTALL_PACKAGE_UPDATES);
    }


    /** Returns true if a package setting declares that it uses a permission */
    private static boolean usesAnyPermission(AndroidPackage pkgSetting, String... permissions) {
        List<ParsedUsesPermission> usesPermissions = pkgSetting.getUsesPermissions();
        for (int i = 0; i < usesPermissions.size(); i++) {
            for (int j = 0; j < permissions.length; j++) {
                if (permissions[j].equals(usesPermissions.get(i).getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reads the update owner deny list from a {@link PackageSetting} and returns the set of
     * packages it contains or {@code null} if it cannot be read.
     */
    public ArraySet<String> readUpdateOwnerDenyList(PackageSetting pkgSetting) {
        if (!hasValidOwnershipDenyList(pkgSetting)) {
            return null;
        }
        AndroidPackage pkg = pkgSetting.getPkg();
        if (pkg == null) {
            return null;
        }
        ArraySet<String> ownershipDenyList = new ArraySet<>(MAX_DENYLIST_SIZE);
        try {
            int resId = pkg.getProperties().get(PROPERTY_LEGACY_UPDATE_OWNERSHIP_DENYLIST)
                    .getResourceId();
            ApplicationInfo appInfo = AndroidPackageUtils.generateAppInfoWithoutState(pkg);
            Resources resources = ResourcesManager.getInstance().getResources(
                    null, appInfo.sourceDir, appInfo.splitSourceDirs, appInfo.resourceDirs,
                    appInfo.overlayPaths, appInfo.sharedLibraryFiles, null, Configuration.EMPTY,
                    null, null, null);
            try (XmlResourceParser parser = resources.getXml(resId)) {
                while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                    if (parser.next() == XmlResourceParser.START_TAG) {
                        if (TAG_OWNERSHIP_OPT_OUT.equals(parser.getName())) {
                            parser.next();
                            String packageName = parser.getText();
                            if (packageName != null && !packageName.isBlank()) {
                                ownershipDenyList.add(packageName);
                                if (ownershipDenyList.size() > MAX_DENYLIST_SIZE) {
                                    Slog.w(TAG, "Deny list defined by " + pkg.getPackageName()
                                            + " was trucated to maximum size of "
                                            + MAX_DENYLIST_SIZE);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to parse update owner list for " + pkgSetting.getPackageName(), e);
            return null;
        }
        return ownershipDenyList;
    }

    /**
     * Begins tracking the contents of a deny list and the owner of that deny list for use in calls
     * to {@link #isUpdateOwnershipDenylisted(String)} and
     * {@link #isUpdateOwnershipDenyListProvider(String)}.
     *
     * @param listOwner the packageName of the package that owns the deny list.
     * @param listContents the list of packageNames that are on the deny list.
     */
    public void addToUpdateOwnerDenyList(String listOwner, ArraySet<String> listContents) {
        synchronized (mLock) {
            for (int i = 0; i < listContents.size(); i++) {
                String packageName = listContents.valueAt(i);
                ArraySet<String> priorDenyListOwners = mUpdateOwnerOptOutsToOwners.putIfAbsent(
                        packageName, new ArraySet<>(new String[]{listOwner}));
                if (priorDenyListOwners != null) {
                    priorDenyListOwners.add(listOwner);
                }
            }
        }
    }

    /**
     * Stop tracking the contents of a deny list owned by the provided owner of the deny list.
     * @param listOwner the packageName of the package that owns the deny list.
     */
    public void removeUpdateOwnerDenyList(String listOwner) {
        synchronized (mLock) {
            for (int i = mUpdateOwnerOptOutsToOwners.size() - 1; i >= 0; i--) {
                ArraySet<String> packageDenyListContributors =
                        mUpdateOwnerOptOutsToOwners.get(mUpdateOwnerOptOutsToOwners.keyAt(i));
                if (packageDenyListContributors.remove(listOwner)
                        && packageDenyListContributors.isEmpty()) {
                    mUpdateOwnerOptOutsToOwners.removeAt(i);
                }
            }
        }
    }

    /**
     * Returns {@code true} if the provided package name is on a valid update ownership deny list.
     */
    public boolean isUpdateOwnershipDenylisted(String packageName) {
        return mUpdateOwnerOptOutsToOwners.containsKey(packageName);
    }

    /**
     * Returns {@code true} if the provided package name defines a valid update ownership deny list.
     */
    public boolean isUpdateOwnershipDenyListProvider(String packageName) {
        if (packageName == null) {
            return false;
        }
        synchronized (mLock) {
            for (int i = mUpdateOwnerOptOutsToOwners.size() - 1; i >= 0; i--) {
                if (mUpdateOwnerOptOutsToOwners.valueAt(i).contains(packageName)) {
                    return true;
                }
            }
            return false;
        }
    }
}
