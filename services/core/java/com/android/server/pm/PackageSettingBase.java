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

import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageUserState;
import android.os.storage.VolumeInfo;
import android.util.ArraySet;
import android.util.SparseArray;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Settings base class for pending and resolved classes.
 */
abstract class PackageSettingBase extends SettingBase {
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

    String parentPackageName;
    List<String> childPackageNames;

    /**
     * Path where this package was found on disk. For monolithic packages
     * this is path to single base APK file; for cluster packages this is
     * path to the cluster directory.
     */
    File codePath;
    String codePathString;
    File resourcePath;
    String resourcePathString;

    /**
     * The path under which native libraries have been unpacked. This path is
     * always derived at runtime, and is only stored here for cleanup when a
     * package is uninstalled.
     */
    @Deprecated
    String legacyNativeLibraryPathString;

    /**
     * The primary CPU abi for this package. This value is regenerated at every
     * boot scan.
     */
    String primaryCpuAbiString;

    /**
     * The secondary CPU abi for this package. This value is regenerated at every
     * boot scan.
     */
    String secondaryCpuAbiString;

    /**
     * The install time CPU override, if any. This value is written at install time
     * and doesn't change during the life of an install. If non-null,
     * {@code primaryCpuAbiString} will contain the same value.
     */
    String cpuAbiOverrideString;

    long timeStamp;
    long firstInstallTime;
    long lastUpdateTime;
    int versionCode;

    boolean uidError;

    PackageSignatures signatures = new PackageSignatures();

    boolean installPermissionsFixed;

    PackageKeySetData keySetData = new PackageKeySetData();

    private static final PackageUserState DEFAULT_USER_STATE = new PackageUserState();

    // Whether this package is currently stopped, thus can not be
    // started until explicitly launched by the user.
    private final SparseArray<PackageUserState> userState = new SparseArray<PackageUserState>();

    int installStatus = PKG_INSTALL_COMPLETE;

    /**
     * Non-persisted value. During an "upgrade without restart", we need the set
     * of all previous code paths so we can surgically add the new APKs to the
     * active classloader. If at any point an application is upgraded with a
     * restart, this field will be cleared since the classloader would be created
     * using the full set of code paths when the package's process is started.
     */
    Set<String> oldCodePaths;
    PackageSettingBase origPackage;

    /** Package name of the app that installed this package */
    String installerPackageName;
    /** Indicates if the package that installed this app has been uninstalled */
    boolean isOrphaned;
    /** UUID of {@link VolumeInfo} hosting this app */
    String volumeUuid;

    IntentFilterVerificationInfo verificationInfo;

    PackageSettingBase(String name, String realName, File codePath, File resourcePath,
            String legacyNativeLibraryPathString, String primaryCpuAbiString,
            String secondaryCpuAbiString, String cpuAbiOverrideString,
            int pVersionCode, int pkgFlags, int pkgPrivateFlags,
            String parentPackageName, List<String> childPackageNames) {
        super(pkgFlags, pkgPrivateFlags);
        this.name = name;
        this.realName = realName;
        this.parentPackageName = parentPackageName;
        this.childPackageNames = (childPackageNames != null)
                ? new ArrayList<>(childPackageNames) : null;
        init(codePath, resourcePath, legacyNativeLibraryPathString, primaryCpuAbiString,
                secondaryCpuAbiString, cpuAbiOverrideString, pVersionCode);
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
        legacyNativeLibraryPathString = base.legacyNativeLibraryPathString;
        primaryCpuAbiString = base.primaryCpuAbiString;
        secondaryCpuAbiString = base.secondaryCpuAbiString;
        cpuAbiOverrideString = base.cpuAbiOverrideString;
        timeStamp = base.timeStamp;
        firstInstallTime = base.firstInstallTime;
        lastUpdateTime = base.lastUpdateTime;
        versionCode = base.versionCode;

        uidError = base.uidError;

        signatures = new PackageSignatures(base.signatures);

        installPermissionsFixed = base.installPermissionsFixed;
        userState.clear();
        for (int i=0; i<base.userState.size(); i++) {
            userState.put(base.userState.keyAt(i),
                    new PackageUserState(base.userState.valueAt(i)));
        }
        installStatus = base.installStatus;

        origPackage = base.origPackage;

        installerPackageName = base.installerPackageName;
        isOrphaned = base.isOrphaned;
        volumeUuid = base.volumeUuid;

        keySetData = new PackageKeySetData(base.keySetData);

        parentPackageName = base.parentPackageName;
        childPackageNames = (base.childPackageNames != null)
                ? new ArrayList<>(base.childPackageNames) : null;
    }

    void init(File codePath, File resourcePath, String legacyNativeLibraryPathString,
              String primaryCpuAbiString, String secondaryCpuAbiString,
              String cpuAbiOverrideString, int pVersionCode) {
        this.codePath = codePath;
        this.codePathString = codePath.toString();
        this.resourcePath = resourcePath;
        this.resourcePathString = resourcePath.toString();
        this.legacyNativeLibraryPathString = legacyNativeLibraryPathString;
        this.primaryCpuAbiString = primaryCpuAbiString;
        this.secondaryCpuAbiString = secondaryCpuAbiString;
        this.cpuAbiOverrideString = cpuAbiOverrideString;
        this.versionCode = pVersionCode;
    }

    public void setInstallerPackageName(String packageName) {
        installerPackageName = packageName;
    }

    public String getInstallerPackageName() {
        return installerPackageName;
    }

    public void setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
    }

    public String getVolumeUuid() {
        return volumeUuid;
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
        mPermissionsState.copyFrom(base.mPermissionsState);
        primaryCpuAbiString = base.primaryCpuAbiString;
        secondaryCpuAbiString = base.secondaryCpuAbiString;
        cpuAbiOverrideString = base.cpuAbiOverrideString;
        timeStamp = base.timeStamp;
        firstInstallTime = base.firstInstallTime;
        lastUpdateTime = base.lastUpdateTime;
        signatures = base.signatures;
        installPermissionsFixed = base.installPermissionsFixed;
        userState.clear();
        for (int i=0; i<base.userState.size(); i++) {
            userState.put(base.userState.keyAt(i), base.userState.valueAt(i));
        }
        installStatus = base.installStatus;
        keySetData = base.keySetData;
        verificationInfo = base.verificationInfo;
        installerPackageName = base.installerPackageName;
        volumeUuid = base.volumeUuid;
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

    long getCeDataInode(int userId) {
        return readUserState(userId).ceDataInode;
    }

    void setCeDataInode(long ceDataInode, int userId) {
        modifyUserState(userId).ceDataInode = ceDataInode;
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

    boolean getHidden(int userId) {
        return readUserState(userId).hidden;
    }

    void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).hidden = hidden;
    }

    boolean getSuspended(int userId) {
        return readUserState(userId).suspended;
    }

    void setSuspended(boolean suspended, int userId) {
        modifyUserState(userId).suspended = suspended;
    }

    boolean getBlockUninstall(int userId) {
        return readUserState(userId).blockUninstall;
    }

    void setBlockUninstall(boolean blockUninstall, int userId) {
        modifyUserState(userId).blockUninstall = blockUninstall;
    }

    void setUserState(int userId, long ceDataInode, int enabled, boolean installed, boolean stopped,
            boolean notLaunched, boolean hidden, boolean suspended,
            String lastDisableAppCaller, ArraySet<String> enabledComponents,
            ArraySet<String> disabledComponents, boolean blockUninstall, int domainVerifState,
            int linkGeneration) {
        PackageUserState state = modifyUserState(userId);
        state.ceDataInode = ceDataInode;
        state.enabled = enabled;
        state.installed = installed;
        state.stopped = stopped;
        state.notLaunched = notLaunched;
        state.hidden = hidden;
        state.suspended = suspended;
        state.lastDisableAppCaller = lastDisableAppCaller;
        state.enabledComponents = enabledComponents;
        state.disabledComponents = disabledComponents;
        state.blockUninstall = blockUninstall;
        state.domainVerificationStatus = domainVerifState;
        state.appLinkGeneration = linkGeneration;
    }

    ArraySet<String> getEnabledComponents(int userId) {
        return readUserState(userId).enabledComponents;
    }

    ArraySet<String> getDisabledComponents(int userId) {
        return readUserState(userId).disabledComponents;
    }

    void setEnabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components;
    }

    void setDisabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components;
    }

    void setEnabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components != null
                ? new ArraySet<String>(components) : null;
    }

    void setDisabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components != null
                ? new ArraySet<String>(components) : null;
    }

    PackageUserState modifyUserStateComponents(int userId, boolean disabled, boolean enabled) {
        PackageUserState state = modifyUserState(userId);
        if (disabled && state.disabledComponents == null) {
            state.disabledComponents = new ArraySet<String>(1);
        }
        if (enabled && state.enabledComponents == null) {
            state.enabledComponents = new ArraySet<String>(1);
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

    IntentFilterVerificationInfo getIntentFilterVerificationInfo() {
        return verificationInfo;
    }

    void setIntentFilterVerificationInfo(IntentFilterVerificationInfo info) {
        verificationInfo = info;
    }

    // Returns a packed value as a long:
    //
    // high 'int'-sized word: link status: undefined/ask/never/always.
    // low 'int'-sized word: relative priority among 'always' results.
    long getDomainVerificationStatusForUser(int userId) {
        PackageUserState state = readUserState(userId);
        long result = (long) state.appLinkGeneration;
        result |= ((long) state.domainVerificationStatus) << 32;
        return result;
    }

    void setDomainVerificationStatusForUser(final int status, int generation, int userId) {
        PackageUserState state = modifyUserState(userId);
        state.domainVerificationStatus = status;
        if (status == PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
            state.appLinkGeneration = generation;
        }
    }

    void clearDomainVerificationStatusForUser(int userId) {
        modifyUserState(userId).domainVerificationStatus =
                PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
    }
}
