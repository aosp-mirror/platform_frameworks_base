/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;


import java.io.File;
import java.util.HashSet;

/**
 * Settings base class for pending and resolved classes.
 */
class PackageSettingBase extends GrantedPermissions {
    /**
     * Indicates the state of installation. Used by PackageManager to figure out
     * incomplete installations. Say a package is being installed (the state is
     * set to PKG_INSTALL_INCOMPLETE) and remains so till the package
     * installation is successful or unsuccessful in which case the
     * PackageManager will no longer maintain state information associated with
     * the package. If some exception(like device freeze or battery being pulled
     * out) occurs during installation of a package, the PackageManager needs
     * this information to clean up the previously failed installation.
     */
    static final int PKG_INSTALL_COMPLETE = 1;
    static final int PKG_INSTALL_INCOMPLETE = 0;

    final String name;
    final String realName;
    File codePath;
    String codePathString;
    File resourcePath;
    String resourcePathString;
    String nativeLibraryPathString;
    long timeStamp;
    long firstInstallTime;
    long lastUpdateTime;
    int versionCode;

    boolean uidError;

    PackageSignatures signatures = new PackageSignatures();

    boolean permissionsFixed;
    boolean haveGids;

    // Whether this package is currently stopped, thus can not be
    // started until explicitly launched by the user.
    public boolean stopped;

    // Set to true if we have never launched this app.
    public boolean notLaunched;

    /* Explicitly disabled components */
    HashSet<String> disabledComponents = new HashSet<String>(0);
    /* Explicitly enabled components */
    HashSet<String> enabledComponents = new HashSet<String>(0);
    int enabled = COMPONENT_ENABLED_STATE_DEFAULT;
    int installStatus = PKG_INSTALL_COMPLETE;

    PackageSettingBase origPackage;
    
    /* package name of the app that installed this package */
    String installerPackageName;
    PackageSettingBase(String name, String realName, File codePath, File resourcePath,
            String nativeLibraryPathString, int pVersionCode, int pkgFlags) {
        super(pkgFlags);
        this.name = name;
        this.realName = realName;
        init(codePath, resourcePath, nativeLibraryPathString, pVersionCode);
    }

    /**
     * New instance of PackageSetting with one-level-deep cloning.
     */
    @SuppressWarnings("unchecked")
    PackageSettingBase(PackageSettingBase base) {
        super(base);

        name = base.name;
        realName = base.realName;
        codePath = base.codePath;
        codePathString = base.codePathString;
        resourcePath = base.resourcePath;
        resourcePathString = base.resourcePathString;
        nativeLibraryPathString = base.nativeLibraryPathString;
        timeStamp = base.timeStamp;
        firstInstallTime = base.firstInstallTime;
        lastUpdateTime = base.lastUpdateTime;
        versionCode = base.versionCode;

        uidError = base.uidError;

        signatures = new PackageSignatures(base.signatures);

        permissionsFixed = base.permissionsFixed;
        haveGids = base.haveGids;
        stopped = base.stopped;
        notLaunched = base.notLaunched;

        disabledComponents = (HashSet<String>) base.disabledComponents.clone();

        enabledComponents = (HashSet<String>) base.enabledComponents.clone();

        enabled = base.enabled;
        installStatus = base.installStatus;

        origPackage = base.origPackage;

        installerPackageName = base.installerPackageName;
    }

    void init(File codePath, File resourcePath, String nativeLibraryPathString,
            int pVersionCode) {
        this.codePath = codePath;
        this.codePathString = codePath.toString();
        this.resourcePath = resourcePath;
        this.resourcePathString = resourcePath.toString();
        this.nativeLibraryPathString = nativeLibraryPathString;
        this.versionCode = pVersionCode;
    }

    public void setInstallerPackageName(String packageName) {
        installerPackageName = packageName;
    }

    String getInstallerPackageName() {
        return installerPackageName;
    }

    public void setInstallStatus(int newStatus) {
        installStatus = newStatus;
    }

    public int getInstallStatus() {
        return installStatus;
    }

    public void setTimeStamp(long newStamp) {
        timeStamp = newStamp;
    }

    /**
     * Make a shallow copy of this package settings.
     */
    public void copyFrom(PackageSettingBase base) {
        grantedPermissions = base.grantedPermissions;
        gids = base.gids;

        timeStamp = base.timeStamp;
        firstInstallTime = base.firstInstallTime;
        lastUpdateTime = base.lastUpdateTime;
        signatures = base.signatures;
        permissionsFixed = base.permissionsFixed;
        haveGids = base.haveGids;
        stopped = base.stopped;
        notLaunched = base.notLaunched;
        disabledComponents = base.disabledComponents;
        enabledComponents = base.enabledComponents;
        enabled = base.enabled;
        installStatus = base.installStatus;
    }

    boolean enableComponentLPw(String componentClassName) {
        boolean changed = disabledComponents.remove(componentClassName);
        changed |= enabledComponents.add(componentClassName);
        return changed;
    }

    boolean disableComponentLPw(String componentClassName) {
        boolean changed = enabledComponents.remove(componentClassName);
        changed |= disabledComponents.add(componentClassName);
        return changed;
    }

    boolean restoreComponentLPw(String componentClassName) {
        boolean changed = enabledComponents.remove(componentClassName);
        changed |= disabledComponents.remove(componentClassName);
        return changed;
    }

    int getCurrentEnabledStateLPr(String componentName) {
        if (enabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_ENABLED;
        } else if (disabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_DISABLED;
        } else {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }
}