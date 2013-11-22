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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageUserState;
import android.content.pm.UserInfo;
import android.util.SparseArray;

import java.io.File;
import java.util.HashSet;
import java.util.List;

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

    PackageKeySetData keySetData = new PackageKeySetData();

    private static final PackageUserState DEFAULT_USER_STATE = new PackageUserState();

    // Whether this package is currently stopped, thus can not be
    // started until explicitly launched by the user.
    private final SparseArray<PackageUserState> userState = new SparseArray<PackageUserState>();

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
        userState.clear();
        for (int i=0; i<base.userState.size(); i++) {
            userState.put(base.userState.keyAt(i),
                    new PackageUserState(base.userState.valueAt(i)));
        }
        installStatus = base.installStatus;

        origPackage = base.origPackage;

        installerPackageName = base.installerPackageName;

        keySetData = new PackageKeySetData(base.keySetData);

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
        userState.clear();
        for (int i=0; i<base.userState.size(); i++) {
            userState.put(base.userState.keyAt(i), base.userState.valueAt(i));
        }
        installStatus = base.installStatus;
        keySetData = base.keySetData;
    }

    private PackageUserState modifyUserState(int userId) {
        PackageUserState state = userState.get(userId);
        if (state == null) {
            state = new PackageUserState();
            userState.put(userId, state);
        }
        return state;
    }

    public PackageUserState readUserState(int userId) {
        PackageUserState state = userState.get(userId);
        if (state != null) {
            return state;
        }
        return DEFAULT_USER_STATE;
    }

    void setEnabled(int state, int userId, String callingPackage) {
        PackageUserState st = modifyUserState(userId);
        st.enabled = state;
        st.lastDisableAppCaller = callingPackage;
    }

    int getEnabled(int userId) {
        return readUserState(userId).enabled;
    }

    String getLastDisabledAppCaller(int userId) {
        return readUserState(userId).lastDisableAppCaller;
    }

    void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).installed = inst;
    }

    boolean getInstalled(int userId) {
        return readUserState(userId).installed;
    }

    boolean isAnyInstalled(int[] users) {
        for (int user: users) {
            if (readUserState(user).installed) {
                return true;
            }
        }
        return false;
    }

    int[] queryInstalledUsers(int[] users, boolean installed) {
        int num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                num++;
            }
        }
        int[] res = new int[num];
        num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                res[num] = user;
                num++;
            }
        }
        return res;
    }

    boolean getStopped(int userId) {
        return readUserState(userId).stopped;
    }

    void setStopped(boolean stop, int userId) {
        modifyUserState(userId).stopped = stop;
    }

    boolean getNotLaunched(int userId) {
        return readUserState(userId).notLaunched;
    }

    void setNotLaunched(boolean stop, int userId) {
        modifyUserState(userId).notLaunched = stop;
    }

    boolean getBlocked(int userId) {
        return readUserState(userId).blocked;
    }

    void setBlocked(boolean blocked, int userId) {
        modifyUserState(userId).blocked = blocked;
    }

    void setUserState(int userId, int enabled, boolean installed, boolean stopped,
            boolean notLaunched, boolean blocked,
            String lastDisableAppCaller, HashSet<String> enabledComponents,
            HashSet<String> disabledComponents) {
        PackageUserState state = modifyUserState(userId);
        state.enabled = enabled;
        state.installed = installed;
        state.stopped = stopped;
        state.notLaunched = notLaunched;
        state.blocked = blocked;
        state.lastDisableAppCaller = lastDisableAppCaller;
        state.enabledComponents = enabledComponents;
        state.disabledComponents = disabledComponents;
    }

    HashSet<String> getEnabledComponents(int userId) {
        return readUserState(userId).enabledComponents;
    }

    HashSet<String> getDisabledComponents(int userId) {
        return readUserState(userId).disabledComponents;
    }

    void setEnabledComponents(HashSet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components;
    }

    void setDisabledComponents(HashSet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components;
    }

    void setEnabledComponentsCopy(HashSet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components != null
                ? new HashSet<String>(components) : null;
    }

    void setDisabledComponentsCopy(HashSet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components != null
                ? new HashSet<String>(components) : null;
    }

    PackageUserState modifyUserStateComponents(int userId, boolean disabled, boolean enabled) {
        PackageUserState state = modifyUserState(userId);
        if (disabled && state.disabledComponents == null) {
            state.disabledComponents = new HashSet<String>(1);
        }
        if (enabled && state.enabledComponents == null) {
            state.enabledComponents = new HashSet<String>(1);
        }
        return state;
    }

    void addDisabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, true, false).disabledComponents.add(componentClassName);
    }

    void addEnabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, false, true).enabledComponents.add(componentClassName);
    }

    boolean enableComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, false, true);
        boolean changed = state.disabledComponents != null
                ? state.disabledComponents.remove(componentClassName) : false;
        changed |= state.enabledComponents.add(componentClassName);
        return changed;
    }

    boolean disableComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, false);
        boolean changed = state.enabledComponents != null
                ? state.enabledComponents.remove(componentClassName) : false;
        changed |= state.disabledComponents.add(componentClassName);
        return changed;
    }

    boolean restoreComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, true);
        boolean changed = state.disabledComponents != null
                ? state.disabledComponents.remove(componentClassName) : false;
        changed |= state.enabledComponents != null
                ? state.enabledComponents.remove(componentClassName) : false;
        return changed;
    }

    int getCurrentEnabledStateLPr(String componentName, int userId) {
        PackageUserState state = readUserState(userId);
        if (state.enabledComponents != null && state.enabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_ENABLED;
        } else if (state.disabledComponents != null
                && state.disabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_DISABLED;
        } else {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    void removeUser(int userId) {
        userState.delete(userId);
    }
}
