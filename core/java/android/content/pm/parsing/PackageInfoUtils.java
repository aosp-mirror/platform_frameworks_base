/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.pm.parsing;

import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;

import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FallbackCategoryProvider;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.SELinuxUtil;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedInstrumentation;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermission;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermissionGroup;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.internal.util.ArrayUtils;

import java.util.Set;

/** @hide */
public class PackageInfoUtils {

    private static final String TAG = ApkParseUtils.TAG;

    /**
     * Returns true if the package is installed and not hidden, or if the caller
     * explicitly wanted all uninstalled and hidden packages as well.
     */
    private static boolean checkUseInstalledOrHidden(AndroidPackage pkg, PackageUserState state,
            @PackageManager.PackageInfoFlags int flags) {
        // Returns false if the package is hidden system app until installed.
        if ((flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) == 0
                && !state.installed
                && pkg.isHiddenUntilInstalled()) {
            return false;
        }

        // If available for the target user, or trying to match uninstalled packages and it's
        // a system app.
        return state.isAvailable(flags)
                || (pkg.isSystemApp()
                && ((flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0
                || (flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) != 0));
    }

    public static PackageInfo generate(AndroidPackage pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(pkg, state, flags) || !pkg.isMatch(flags)) {
            return null;
        }
        ApplicationInfo applicationInfo = generateApplicationInfo(pkg, flags, state, userId);

        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg.getPackageName();
        pi.splitNames = pkg.getSplitNames();
        pi.versionCode = pkg.getVersionCode();
        pi.versionCodeMajor = pkg.getVersionCodeMajor();
        pi.baseRevisionCode = pkg.getBaseRevisionCode();
        pi.splitRevisionCodes = pkg.getSplitRevisionCodes();
        pi.versionName = pkg.getVersionName();
        pi.sharedUserId = pkg.getSharedUserId();
        pi.sharedUserLabel = pkg.getSharedUserLabel();
        pi.applicationInfo = applicationInfo;
        pi.installLocation = pkg.getInstallLocation();
        pi.isStub = pkg.isStub();
        pi.coreApp = pkg.isCoreApp();
        if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (pi.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            pi.requiredForAllUsers = pkg.isRequiredForAllUsers();
        }
        pi.restrictedAccountType = pkg.getRestrictedAccountType();
        pi.requiredAccountType = pkg.getRequiredAccountType();
        pi.overlayTarget = pkg.getOverlayTarget();
        pi.targetOverlayableName = pkg.getOverlayTargetName();
        pi.overlayCategory = pkg.getOverlayCategory();
        pi.overlayPriority = pkg.getOverlayPriority();
        pi.mOverlayIsStatic = pkg.isOverlayIsStatic();
        pi.compileSdkVersion = pkg.getCompileSdkVersion();
        pi.compileSdkVersionCodename = pkg.getCompileSdkVersionCodeName();
        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if ((flags & PackageManager.GET_GIDS) != 0) {
            pi.gids = gids;
        }
        if ((flags & PackageManager.GET_CONFIGURATIONS) != 0) {
            int size = pkg.getConfigPreferences() != null ? pkg.getConfigPreferences().size() : 0;
            if (size > 0) {
                pi.configPreferences = new ConfigurationInfo[size];
                pkg.getConfigPreferences().toArray(pi.configPreferences);
            }
            size = pkg.getReqFeatures() != null ? pkg.getReqFeatures().size() : 0;
            if (size > 0) {
                pi.reqFeatures = new FeatureInfo[size];
                pkg.getReqFeatures().toArray(pi.reqFeatures);
            }
            size = pkg.getFeatureGroups() != null ? pkg.getFeatureGroups().size() : 0;
            if (size > 0) {
                pi.featureGroups = new FeatureGroupInfo[size];
                pkg.getFeatureGroups().toArray(pi.featureGroups);
            }
        }
        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            if (pkg.getActivities() != null) {
                final int N = pkg.getActivities().size();
                if (N > 0) {
                    int num = 0;
                    final ActivityInfo[] res = new ActivityInfo[N];
                    for (int i = 0; i < N; i++) {
                        final ParsedActivity a = pkg.getActivities().get(i);
                        if (state.isMatch(pkg.isSystem(), pkg.isEnabled(), a, flags)) {
                            if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(
                                    a.className)) {
                                continue;
                            }
                            res[num++] = generateActivityInfo(pkg, a, flags, state, applicationInfo,
                                    userId);
                        }
                    }
                    pi.activities = ArrayUtils.trimToSize(res, num);
                }
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            if (pkg.getReceivers() != null) {
                final int size = pkg.getReceivers().size();
                if (size > 0) {
                    int num = 0;
                    final ActivityInfo[] res = new ActivityInfo[size];
                    for (int i = 0; i < size; i++) {
                        final ParsedActivity a = pkg.getReceivers().get(i);
                        if (state.isMatch(pkg.isSystem(), pkg.isEnabled(), a, flags)) {
                            res[num++] = generateActivityInfo(pkg, a, flags, state, applicationInfo,
                                    userId);
                        }
                    }
                    pi.receivers = ArrayUtils.trimToSize(res, num);
                }
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            if (pkg.getServices() != null) {
                final int size = pkg.getServices().size();
                if (size > 0) {
                    int num = 0;
                    final ServiceInfo[] res = new ServiceInfo[size];
                    for (int i = 0; i < size; i++) {
                        final ComponentParseUtils.ParsedService s = pkg.getServices().get(i);
                        if (state.isMatch(pkg.isSystem(), pkg.isEnabled(), s, flags)) {
                            res[num++] = generateServiceInfo(pkg, s, flags, state, applicationInfo,
                                    userId);
                        }
                    }
                    pi.services = ArrayUtils.trimToSize(res, num);
                }
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            if (pkg.getProviders() != null) {
                final int size = pkg.getProviders().size();
                if (size > 0) {
                    int num = 0;
                    final ProviderInfo[] res = new ProviderInfo[size];
                    for (int i = 0; i < size; i++) {
                        final ComponentParseUtils.ParsedProvider pr = pkg.getProviders()
                                .get(i);
                        if (state.isMatch(pkg.isSystem(), pkg.isEnabled(), pr, flags)) {
                            res[num++] = generateProviderInfo(pkg, pr, flags, state,
                                    applicationInfo, userId);
                        }
                    }
                    pi.providers = ArrayUtils.trimToSize(res, num);
                }
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            if (pkg.getInstrumentations() != null) {
                int N = pkg.getInstrumentations().size();
                if (N > 0) {
                    pi.instrumentation = new InstrumentationInfo[N];
                    for (int i = 0; i < N; i++) {
                        pi.instrumentation[i] = generateInstrumentationInfo(
                                pkg.getInstrumentations().get(i), flags);
                    }
                }
            }
        }
        if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
            if (pkg.getPermissions() != null) {
                int N = ArrayUtils.size(pkg.getPermissions());
                if (N > 0) {
                    pi.permissions = new PermissionInfo[N];
                    for (int i = 0; i < N; i++) {
                        pi.permissions[i] = generatePermissionInfo(
                                pkg.getPermissions().get(i),
                                flags
                        );
                    }
                }
            }
            if (pkg.getRequestedPermissions() != null) {
                int N = pkg.getRequestedPermissions().size();
                if (N > 0) {
                    pi.requestedPermissions = new String[N];
                    pi.requestedPermissionsFlags = new int[N];
                    for (int i = 0; i < N; i++) {
                        final String perm = pkg.getRequestedPermissions().get(i);
                        pi.requestedPermissions[i] = perm;
                        // The notion of required permissions is deprecated but for compatibility.
                        pi.requestedPermissionsFlags[i] |=
                                PackageInfo.REQUESTED_PERMISSION_REQUIRED;
                        if (grantedPermissions != null && grantedPermissions.contains(perm)) {
                            pi.requestedPermissionsFlags[i] |=
                                    PackageInfo.REQUESTED_PERMISSION_GRANTED;
                        }
                    }
                }
            }
        }

        PackageParser.SigningDetails signingDetails = pkg.getSigningDetails();
        // deprecated method of getting signing certificates
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            if (signingDetails.hasPastSigningCertificates()) {
                // Package has included signing certificate rotation information.  Return the oldest
                // cert so that programmatic checks keep working even if unaware of key rotation.
                pi.signatures = new Signature[1];
                pi.signatures[0] = signingDetails.pastSigningCertificates[0];
            } else if (signingDetails.hasSignatures()) {
                // otherwise keep old behavior
                int numberOfSigs = signingDetails.signatures.length;
                pi.signatures = new Signature[numberOfSigs];
                System.arraycopy(signingDetails.signatures, 0, pi.signatures, 0,
                        numberOfSigs);
            }
        }

        // replacement for GET_SIGNATURES
        if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            if (signingDetails != PackageParser.SigningDetails.UNKNOWN) {
                // only return a valid SigningInfo if there is signing information to report
                pi.signingInfo = new SigningInfo(signingDetails);
            } else {
                pi.signingInfo = null;
            }
        }

        return pi;
    }

    // TODO(b/135203078): Remove this in favor of AndroidPackage.toAppInfo()
    private static ApplicationInfo appInfoFromFinalPackage(AndroidPackage pkg) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.name = pkg.getName();
        if (appInfo.name != null) appInfo.name = appInfo.name.trim();
        appInfo.packageName = pkg.getPackageName();
        appInfo.labelRes = pkg.getLabelRes();
        appInfo.nonLocalizedLabel = pkg.getNonLocalizedLabel();
        if (appInfo.nonLocalizedLabel != null) {
            appInfo.nonLocalizedLabel = appInfo.nonLocalizedLabel.toString().trim();
        }
        appInfo.icon = pkg.getIcon();
        appInfo.banner = pkg.getBanner();
        appInfo.logo = pkg.getLogo();
        appInfo.metaData = pkg.getMetaData();

        // TODO(b/135203078): Can this be removed? Looks only used in ActivityInfo.
//        appInfo.showUserIcon = pkg.getShowUserIcon();

        appInfo.taskAffinity = pkg.getTaskAffinity();
        appInfo.permission = pkg.getPermission();
        appInfo.processName = pkg.getProcessName();
        appInfo.className = pkg.getClassName();
        appInfo.theme = pkg.getTheme();
        appInfo.flags = pkg.getFlags();
        appInfo.privateFlags = pkg.getPrivateFlags();
        appInfo.requiresSmallestWidthDp = pkg.getRequiresSmallestWidthDp();
        appInfo.compatibleWidthLimitDp = pkg.getCompatibleWidthLimitDp();
        appInfo.largestWidthLimitDp = pkg.getLargestWidthLimitDp();
        appInfo.volumeUuid = pkg.getVolumeUuid();
        appInfo.storageUuid = pkg.getStorageUuid();
        appInfo.scanSourceDir = pkg.getScanSourceDir();
        appInfo.scanPublicSourceDir = pkg.getScanPublicSourceDir();
        appInfo.sourceDir = pkg.getBaseCodePath();
        appInfo.publicSourceDir = pkg.getPublicSourceDir();
        appInfo.splitNames = pkg.getSplitNames();
        appInfo.splitSourceDirs = pkg.getSplitCodePaths();
        appInfo.splitPublicSourceDirs = pkg.getSplitPublicSourceDirs();
        appInfo.splitDependencies = pkg.getSplitDependencies();
        appInfo.nativeLibraryDir = pkg.getNativeLibraryDir();
        appInfo.secondaryNativeLibraryDir = pkg.getSecondaryNativeLibraryDir();
        appInfo.nativeLibraryRootDir = pkg.getNativeLibraryRootDir();
        appInfo.nativeLibraryRootRequiresIsa = pkg.isNativeLibraryRootRequiresIsa();
        appInfo.primaryCpuAbi = pkg.getPrimaryCpuAbi();
        appInfo.secondaryCpuAbi = pkg.getSecondaryCpuAbi();

        // TODO(b/135203078): Unused?
//        appInfo.resourceDirs = pkg.getResourceDirs();
        appInfo.seInfo = pkg.getSeInfo();
        appInfo.seInfoUser = pkg.getSeInfoUser();
        appInfo.sharedLibraryFiles = pkg.getUsesLibraryFiles();
        appInfo.sharedLibraryInfos = pkg.getUsesLibraryInfos();
        appInfo.dataDir = pkg.getDataDir();
        appInfo.deviceProtectedDataDir = pkg.getDeviceProtectedDataDir();
        appInfo.credentialProtectedDataDir = pkg.getCredentialProtectedDataDir();
        appInfo.uid = pkg.getUid();
        appInfo.minSdkVersion = pkg.getMinSdkVersion();
        appInfo.targetSdkVersion = pkg.getTargetSdkVersion();
        appInfo.setVersionCode(pkg.getLongVersionCode());
        appInfo.enabled = pkg.isEnabled();

        // TODO(b/135203078): Unused?
//        appInfo.enabledSetting = pkg.getEnabledSetting();
        appInfo.installLocation = pkg.getInstallLocation();
        appInfo.manageSpaceActivityName = pkg.getManageSpaceActivityName();
        appInfo.descriptionRes = pkg.getDescriptionRes();
        appInfo.uiOptions = pkg.getUiOptions();
        appInfo.backupAgentName = pkg.getBackupAgentName();
        appInfo.fullBackupContent = pkg.getFullBackupContent();
        appInfo.networkSecurityConfigRes = pkg.getNetworkSecurityConfigRes();
        appInfo.category = pkg.getCategory();
        appInfo.targetSandboxVersion = pkg.getTargetSandboxVersion();
        appInfo.classLoaderName = pkg.getClassLoaderName();
        appInfo.splitClassLoaderNames = pkg.getSplitClassLoaderNames();
        appInfo.appComponentFactory = pkg.getAppComponentFactory();
        appInfo.iconRes = pkg.getIconRes();
        appInfo.roundIconRes = pkg.getRoundIconRes();
        appInfo.compileSdkVersion = pkg.getCompileSdkVersion();
        appInfo.compileSdkVersionCodename = pkg.getCompileSdkVersionCodeName();

        // TODO(b/135203078): See PackageImpl#getHiddenApiEnforcementPolicy
//        appInfo.mHiddenApiPolicy = pkg.getHiddenApiPolicy();
        appInfo.hiddenUntilInstalled = pkg.isHiddenUntilInstalled();
        appInfo.zygotePreloadName = pkg.getZygotePreloadName();
        return appInfo;
    }

    public static ApplicationInfo generateApplicationInfo(AndroidPackage pkg,
            @PackageManager.ApplicationInfoFlags int flags, PackageUserState state, int userId) {

        if (pkg == null) return null;
        if (!checkUseInstalledOrHidden(pkg, state, flags) || !pkg.isMatch(flags)) {
            return null;
        }
        if (!copyNeeded(flags, pkg, state, null, userId)
                && ((flags & PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS) == 0
                || state.enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
            // TODO(b/135203078): This isn't applicable anymore, as AppInfo isn't cached and
            //   always built new in toAppInfo(). Remove entire copyNeeded flow? Or find a way to
            //   transiently cache AppInfo, since multiple calls in quick secession probably need
            //   the same AppInfo.
            // In this case it is safe to directly modify the internal ApplicationInfo state:
            // - CompatibilityMode is global state, so will be the same for every call.
            // - We only come in to here if the app should reported as installed; this is the
            // default state, and we will do a copy otherwise.
            // - The enable state will always be reported the same for the application across
            // calls; the only exception is for the UNTIL_USED mode, and in that case we will
            // be doing a copy.
            ApplicationInfo applicationInfo = pkg.toAppInfo();
            updateApplicationInfo(applicationInfo, flags, state);
            return applicationInfo;
        }

        // Make shallow copy so we can store the metadata/libraries safely
        ApplicationInfo ai = appInfoFromFinalPackage(pkg);
        ai.initForUser(userId);
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = pkg.getAppMetaData();
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0) {
            ai.sharedLibraryFiles = pkg.getUsesLibraryFiles();
            ai.sharedLibraryInfos = pkg.getUsesLibraryInfos();
        }
        if (state.stopped) {
            ai.flags |= ApplicationInfo.FLAG_STOPPED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_STOPPED;
        }
        updateApplicationInfo(ai, flags, state);

        return ai;
    }

    private static ActivityInfo generateActivityInfo(AndroidPackage pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state,
            @Nullable ApplicationInfo applicationInfo, int userId) {
        if (a == null) return null;
        if (!checkUseInstalledOrHidden(pkg, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        if (!copyNeeded(flags, pkg, state, a.metaData, userId)) {
            updateApplicationInfo(applicationInfo, flags, state);
        }
        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo();
        assignSharedFieldsForComponentInfo(ai, a);
        ai.targetActivity = a.targetActivity;
        ai.processName = a.getProcessName();
        ai.exported = a.exported;
        ai.theme = a.theme;
        ai.uiOptions = a.uiOptions;
        ai.parentActivityName = a.parentActivityName;
        ai.permission = a.getPermission();
        ai.taskAffinity = a.taskAffinity;
        ai.flags = a.flags;
        ai.privateFlags = a.privateFlags;
        ai.launchMode = a.launchMode;
        ai.documentLaunchMode = a.documentLaunchMode;
        ai.maxRecents = a.maxRecents;
        ai.configChanges = a.configChanges;
        ai.softInputMode = a.softInputMode;
        ai.persistableMode = a.persistableMode;
        ai.lockTaskLaunchMode = a.lockTaskLaunchMode;
        ai.screenOrientation = a.screenOrientation;
        ai.resizeMode = a.resizeMode;
        ai.maxAspectRatio = a.maxAspectRatio;
        ai.minAspectRatio = a.minAspectRatio;
        ai.requestedVrComponent = a.requestedVrComponent;
        ai.rotationAnimation = a.rotationAnimation;
        ai.colorMode = a.colorMode;
        ai.windowLayout = a.windowLayout;
        ai.metaData = a.metaData;
        ai.applicationInfo = applicationInfo;
        return ai;
    }

    public static ActivityInfo generateActivityInfo(AndroidPackage pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId) {
        return generateActivityInfo(pkg, a, flags, state, null, userId);
    }

    private static ServiceInfo generateServiceInfo(AndroidPackage pkg,
            ComponentParseUtils.ParsedService s, @PackageManager.ComponentInfoFlags int flags,
            PackageUserState state, @Nullable ApplicationInfo applicationInfo, int userId) {
        if (s == null) return null;
        if (!checkUseInstalledOrHidden(pkg, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        if (!copyNeeded(flags, pkg, state, s.metaData, userId)) {
            updateApplicationInfo(applicationInfo, flags, state);
        }
        // Make shallow copies so we can store the metadata safely
        ServiceInfo si = new ServiceInfo();
        assignSharedFieldsForComponentInfo(si, s);
        si.exported = s.exported;
        si.flags = s.flags;
        si.metaData = s.metaData;
        si.permission = s.getPermission();
        si.processName = s.getProcessName();
        si.mForegroundServiceType = s.foregroundServiceType;
        si.metaData = s.metaData;
        si.applicationInfo = applicationInfo;
        return si;
    }

    public static ServiceInfo generateServiceInfo(AndroidPackage pkg,
            ComponentParseUtils.ParsedService s, @PackageManager.ComponentInfoFlags int flags,
            PackageUserState state, int userId) {
        return generateServiceInfo(pkg, s, flags, state, null, userId);
    }

    private static ProviderInfo generateProviderInfo(AndroidPackage pkg,
            ComponentParseUtils.ParsedProvider p, @PackageManager.ComponentInfoFlags int flags,
            PackageUserState state, @Nullable ApplicationInfo applicationInfo, int userId) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(pkg, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        if (!copyNeeded(flags, pkg, state, p.metaData, userId)
                && ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) != 0
                || p.uriPermissionPatterns == null)) {
            updateApplicationInfo(applicationInfo, flags, state);
        }
        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo();
        assignSharedFieldsForComponentInfo(pi, p);
        pi.exported = p.exported;
        pi.flags = p.flags;
        pi.processName = p.getProcessName();
        pi.authority = p.getAuthority();
        pi.isSyncable = p.isSyncable;
        pi.readPermission = p.getReadPermission();
        pi.writePermission = p.getWritePermission();
        pi.grantUriPermissions = p.grantUriPermissions;
        pi.forceUriPermissions = p.forceUriPermissions;
        pi.multiprocess = p.multiProcess;
        pi.initOrder = p.initOrder;
        pi.uriPermissionPatterns = p.uriPermissionPatterns;
        pi.pathPermissions = p.pathPermissions;
        pi.metaData = p.metaData;
        if ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = applicationInfo;
        return pi;
    }

    public static ProviderInfo generateProviderInfo(AndroidPackage pkg,
            ComponentParseUtils.ParsedProvider p, @PackageManager.ComponentInfoFlags int flags,
            PackageUserState state, int userId) {
        return generateProviderInfo(pkg, p, flags, state, null, userId);
    }

    public static InstrumentationInfo generateInstrumentationInfo(ParsedInstrumentation i,
            @PackageManager.ComponentInfoFlags int flags) {
        if (i == null) return null;

        InstrumentationInfo ii = new InstrumentationInfo();
        assignSharedFieldsForPackageItemInfo(ii, i);
        ii.targetPackage = i.getTargetPackage();
        ii.targetProcesses = i.getTargetProcesses();
        ii.handleProfiling = i.handleProfiling;
        ii.functionalTest = i.functionalTest;

        ii.sourceDir = i.sourceDir;
        ii.publicSourceDir = i.publicSourceDir;
        ii.splitNames = i.splitNames;
        ii.splitSourceDirs = i.splitSourceDirs;
        ii.splitPublicSourceDirs = i.splitPublicSourceDirs;
        ii.splitDependencies = i.splitDependencies;
        ii.dataDir = i.dataDir;
        ii.deviceProtectedDataDir = i.deviceProtectedDataDir;
        ii.credentialProtectedDataDir = i.credentialProtectedDataDir;
        ii.primaryCpuAbi = i.primaryCpuAbi;
        ii.secondaryCpuAbi = i.secondaryCpuAbi;
        ii.nativeLibraryDir = i.nativeLibraryDir;
        ii.secondaryNativeLibraryDir = i.secondaryNativeLibraryDir;

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return ii;
        }
        ii.metaData = i.metaData;
        return ii;
    }

    public static PermissionInfo generatePermissionInfo(ParsedPermission p,
            @PackageManager.ComponentInfoFlags int flags) {
        if (p == null) return null;

        PermissionInfo pi = new PermissionInfo(p.backgroundPermission);
        assignSharedFieldsForPackageItemInfo(pi, p);
        pi.group = p.getGroup();
        pi.requestRes = p.requestRes;
        pi.protectionLevel = p.protectionLevel;
        pi.descriptionRes = p.descriptionRes;
        pi.flags = p.flags;

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return pi;
        }
        pi.metaData = p.metaData;
        return pi;
    }

    public static PermissionGroupInfo generatePermissionGroupInfo(ParsedPermissionGroup pg,
            @PackageManager.ComponentInfoFlags int flags) {
        if (pg == null) return null;

        PermissionGroupInfo pgi = new PermissionGroupInfo(
                pg.requestDetailResourceId,
                pg.backgroundRequestResourceId,
                pg.backgroundRequestDetailResourceId
        );
        assignSharedFieldsForPackageItemInfo(pgi, pg);
        pgi.priority = pg.priority;
        pgi.requestRes = pg.requestRes;
        pgi.flags = pg.flags;

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return pgi;
        }
        pgi.metaData = pg.metaData;
        return pgi;
    }

    private static boolean copyNeeded(@PackageManager.ComponentInfoFlags int flags,
            AndroidPackage pkg, PackageUserState state, Bundle metaData, int userId) {
        if (userId != UserHandle.USER_SYSTEM) {
            // We always need to copy for other users, since we need
            // to fix up the uid.
            return true;
        }
        if (state.enabled != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            boolean enabled = state.enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            if (pkg.isEnabled() != enabled) {
                return true;
            }
        }
        boolean suspended = (pkg.getFlags() & FLAG_SUSPENDED) != 0;
        if (state.suspended != suspended) {
            return true;
        }
        if (!state.installed || state.hidden) {
            return true;
        }
        if (state.stopped) {
            return true;
        }
        if (state.instantApp != pkg.isInstantApp()) {
            return true;
        }
        if ((flags & PackageManager.GET_META_DATA) != 0
                && (metaData != null || pkg.getAppMetaData() != null)) {
            return true;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0
                && pkg.getUsesLibraryFiles() != null) {
            return true;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0
                && pkg.getUsesLibraryInfos() != null) {
            return true;
        }
        return pkg.getStaticSharedLibName() != null;
    }

    private static void updateApplicationInfo(ApplicationInfo ai,
            @PackageManager.ApplicationInfoFlags int flags,
            PackageUserState state) {
        // CompatibilityMode is global state.
        if (!PackageParser.sCompatibilityModeEnabled) {
            ai.disableCompatibilityMode();
        }
        if (state.installed) {
            ai.flags |= ApplicationInfo.FLAG_INSTALLED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        }
        if (state.suspended) {
            ai.flags |= ApplicationInfo.FLAG_SUSPENDED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_SUSPENDED;
        }
        if (state.instantApp) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_INSTANT;
        } else {
            ai.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_INSTANT;
        }
        if (state.virtualPreload) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_VIRTUAL_PRELOAD;
        } else {
            ai.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_VIRTUAL_PRELOAD;
        }
        if (state.hidden) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        } else {
            ai.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        }
        if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            ai.enabled = true;
        } else if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
            ai.enabled = (flags & PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS) != 0;
        } else if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            ai.enabled = false;
        }
        ai.enabledSetting = state.enabled;
        if (ai.category == ApplicationInfo.CATEGORY_UNDEFINED) {
            ai.category = state.categoryHint;
        }
        if (ai.category == ApplicationInfo.CATEGORY_UNDEFINED) {
            ai.category = FallbackCategoryProvider.getFallbackCategory(ai.packageName);
        }
        ai.seInfoUser = SELinuxUtil.assignSeinfoUser(state);
        ai.resourceDirs = state.overlayPaths;
        ai.icon = (PackageParser.sUseRoundIcon && ai.roundIconRes != 0)
                ? ai.roundIconRes : ai.iconRes;
    }

    private static void assignSharedFieldsForPackageItemInfo(PackageItemInfo packageItemInfo,
            ComponentParseUtils.ParsedComponent parsedComponent) {
        packageItemInfo.banner = parsedComponent.banner;
        packageItemInfo.icon = parsedComponent.icon;
        packageItemInfo.labelRes = parsedComponent.labelRes;
        packageItemInfo.logo = parsedComponent.logo;
        packageItemInfo.name = parsedComponent.className;
        packageItemInfo.nonLocalizedLabel = parsedComponent.nonLocalizedLabel;
        packageItemInfo.packageName = parsedComponent.getPackageName();
    }

    private static void assignSharedFieldsForComponentInfo(ComponentInfo componentInfo,
            ComponentParseUtils.ParsedComponent parsedComponent) {
        assignSharedFieldsForPackageItemInfo(componentInfo, parsedComponent);
        componentInfo.descriptionRes = parsedComponent.descriptionRes;
        componentInfo.directBootAware = parsedComponent.directBootAware;
        componentInfo.enabled = parsedComponent.enabled;
        componentInfo.splitName = parsedComponent.getSplitName();
    }

}
