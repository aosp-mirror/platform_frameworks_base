/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.parsing;

import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.Attribution;
import android.content.pm.ComponentInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FallbackCategoryProvider;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PathPermission;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.overlay.OverlayPaths;
import android.os.Environment;
import android.os.PatternMatcher;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageStateUnserialized;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.PackageUserStateUtils;
import com.android.server.pm.pkg.SELinuxUtil;
import com.android.server.pm.pkg.component.ComponentParseUtils;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedAttribution;
import com.android.server.pm.pkg.component.ParsedComponent;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.pkg.component.ParsedPermission;
import com.android.server.pm.pkg.component.ParsedPermissionGroup;
import com.android.server.pm.pkg.component.ParsedProcess;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedService;
import com.android.server.pm.pkg.component.ParsedUsesPermission;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.pm.pkg.parsing.ParsingUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Methods that use a {@link PackageStateInternal} use it to override information provided from the
 * raw package, or to provide information that would otherwise be missing. Null can be passed if
 * none of the state values should be applied.
 *
 * @hide
 **/
public class PackageInfoUtils {
    private static final String TAG = ParsingUtils.TAG;

    private static final String SYSTEM_DATA_PATH =
            Environment.getDataDirectoryPath() + File.separator + "system";

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static PackageInfo generate(AndroidPackage pkg, int[] gids,
            @PackageManager.PackageInfoFlagsBits long flags, long firstInstallTime,
            long lastUpdateTime, Set<String> grantedPermissions, PackageUserStateInternal state,
            @UserIdInt int userId, @NonNull PackageStateInternal pkgSetting) {
        return generateWithComponents(pkg, gids, flags, firstInstallTime, lastUpdateTime,
                grantedPermissions, state, userId, pkgSetting);
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    private static PackageInfo generateWithComponents(AndroidPackage pkg, int[] gids,
            @PackageManager.PackageInfoFlagsBits long flags, long firstInstallTime,
            long lastUpdateTime, Set<String> grantedPermissions, PackageUserStateInternal state,
            @UserIdInt int userId, @NonNull PackageStateInternal pkgSetting) {
        ApplicationInfo applicationInfo = generateApplicationInfo(pkg, flags, state, userId,
                pkgSetting);
        if (applicationInfo == null) {
            return null;
        }

        PackageInfo info = new PackageInfo();
        info.packageName = pkg.getPackageName();
        info.splitNames = pkg.getSplitNames();
        AndroidPackageUtils.fillVersionCodes(pkg, info);
        info.baseRevisionCode = pkg.getBaseRevisionCode();
        info.splitRevisionCodes = pkg.getSplitRevisionCodes();
        info.versionName = pkg.getVersionName();
        info.sharedUserId = pkg.getSharedUserId();
        info.sharedUserLabel = pkg.getSharedUserLabelRes();
        info.applicationInfo = applicationInfo;
        info.installLocation = pkg.getInstallLocation();
        if ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (info.applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            info.requiredForAllUsers = pkg.isRequiredForAllUsers();
        }
        info.restrictedAccountType = pkg.getRestrictedAccountType();
        info.requiredAccountType = pkg.getRequiredAccountType();
        info.overlayTarget = pkg.getOverlayTarget();
        info.targetOverlayableName = pkg.getOverlayTargetOverlayableName();
        info.overlayCategory = pkg.getOverlayCategory();
        info.overlayPriority = pkg.getOverlayPriority();
        info.mOverlayIsStatic = pkg.isOverlayIsStatic();
        info.compileSdkVersion = pkg.getCompileSdkVersion();
        info.compileSdkVersionCodename = pkg.getCompileSdkVersionCodeName();
        info.firstInstallTime = firstInstallTime;
        info.lastUpdateTime = lastUpdateTime;
        if ((flags & PackageManager.GET_GIDS) != 0) {
            info.gids = gids;
        }
        if ((flags & PackageManager.GET_CONFIGURATIONS) != 0) {
            int size = pkg.getConfigPreferences().size();
            if (size > 0) {
                info.configPreferences = new ConfigurationInfo[size];
                pkg.getConfigPreferences().toArray(info.configPreferences);
            }
            size = pkg.getRequestedFeatures().size();
            if (size > 0) {
                info.reqFeatures = new FeatureInfo[size];
                pkg.getRequestedFeatures().toArray(info.reqFeatures);
            }
            size = pkg.getFeatureGroups().size();
            if (size > 0) {
                info.featureGroups = new FeatureGroupInfo[size];
                pkg.getFeatureGroups().toArray(info.featureGroups);
            }
        }
        if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
            int size = ArrayUtils.size(pkg.getPermissions());
            if (size > 0) {
                info.permissions = new PermissionInfo[size];
                for (int i = 0; i < size; i++) {
                    info.permissions[i] = generatePermissionInfo(pkg.getPermissions().get(i),
                            flags);
                }
            }
            final List<ParsedUsesPermission> usesPermissions = pkg.getUsesPermissions();
            size = usesPermissions.size();
            if (size > 0) {
                info.requestedPermissions = new String[size];
                info.requestedPermissionsFlags = new int[size];
                for (int i = 0; i < size; i++) {
                    final ParsedUsesPermission usesPermission = usesPermissions.get(i);
                    info.requestedPermissions[i] = usesPermission.getName();
                    // The notion of required permissions is deprecated but for compatibility.
                    info.requestedPermissionsFlags[i] |=
                            PackageInfo.REQUESTED_PERMISSION_REQUIRED;
                    if (grantedPermissions != null
                            && grantedPermissions.contains(usesPermission.getName())) {
                        info.requestedPermissionsFlags[i] |=
                                PackageInfo.REQUESTED_PERMISSION_GRANTED;
                    }
                    if ((usesPermission.getUsesPermissionFlags()
                            & ParsedUsesPermission.FLAG_NEVER_FOR_LOCATION) != 0) {
                        info.requestedPermissionsFlags[i] |=
                                PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION;
                    }
                    if (pkg.getImplicitPermissions().contains(info.requestedPermissions[i])) {
                        info.requestedPermissionsFlags[i] |=
                                PackageInfo.REQUESTED_PERMISSION_IMPLICIT;
                    }
                }
            }
        }
        if ((flags & PackageManager.GET_ATTRIBUTIONS) != 0) {
            int size = ArrayUtils.size(pkg.getAttributions());
            if (size > 0) {
                info.attributions = new Attribution[size];
                for (int i = 0; i < size; i++) {
                    ParsedAttribution parsedAttribution = pkg.getAttributions().get(i);
                    if (parsedAttribution != null) {
                        info.attributions[i] = new Attribution(parsedAttribution.getTag(),
                                parsedAttribution.getLabel());
                    }
                }
            }
            if (pkg.isAttributionsUserVisible()) {
                info.applicationInfo.privateFlagsExt
                        |= ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE;
            } else {
                info.applicationInfo.privateFlagsExt
                        &= ~ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE;
            }
        } else {
            info.applicationInfo.privateFlagsExt
                    &= ~ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE;
        }

        final SigningDetails signingDetails = pkg.getSigningDetails();
        // deprecated method of getting signing certificates
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            if (signingDetails.hasPastSigningCertificates()) {
                // Package has included signing certificate rotation information.  Return the oldest
                // cert so that programmatic checks keep working even if unaware of key rotation.
                info.signatures = new Signature[1];
                info.signatures[0] = signingDetails.getPastSigningCertificates()[0];
            } else if (signingDetails.hasSignatures()) {
                // otherwise keep old behavior
                int numberOfSigs = signingDetails.getSignatures().length;
                info.signatures = new Signature[numberOfSigs];
                System.arraycopy(signingDetails.getSignatures(), 0, info.signatures, 0,
                        numberOfSigs);
            }
        }

        // replacement for GET_SIGNATURES
        if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            if (signingDetails != SigningDetails.UNKNOWN) {
                // only return a valid SigningInfo if there is signing information to report
                info.signingInfo = new SigningInfo(signingDetails);
            } else {
                info.signingInfo = null;
            }
        }

        info.isStub = pkg.isStub();
        info.coreApp = pkg.isCoreApp();
        info.isApex = pkg.isApex();

        if (!pkgSetting.hasSharedUser()) {
            // It is possible that this shared UID app has left
            info.sharedUserId = null;
            info.sharedUserLabel = 0;
        }

        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            final int N = pkg.getActivities().size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final ParsedActivity a = pkg.getActivities().get(i);
                    if (ComponentParseUtils.isMatch(state, pkgSetting.isSystem(), pkg.isEnabled(), a,
                            flags)) {
                        if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(
                                a.getName())) {
                            continue;
                        }
                        res[num++] = generateActivityInfo(pkg, a, flags, state,
                                applicationInfo, userId, pkgSetting);
                    }
                }
                info.activities = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            final int size = pkg.getReceivers().size();
            if (size > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[size];
                for (int i = 0; i < size; i++) {
                    final ParsedActivity a = pkg.getReceivers().get(i);
                    if (ComponentParseUtils.isMatch(state, pkgSetting.isSystem(), pkg.isEnabled(), a,
                            flags)) {
                        res[num++] = generateActivityInfo(pkg, a, flags, state, applicationInfo,
                                userId, pkgSetting);
                    }
                }
                info.receivers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            final int size = pkg.getServices().size();
            if (size > 0) {
                int num = 0;
                final ServiceInfo[] res = new ServiceInfo[size];
                for (int i = 0; i < size; i++) {
                    final ParsedService s = pkg.getServices().get(i);
                    if (ComponentParseUtils.isMatch(state, pkgSetting.isSystem(), pkg.isEnabled(), s,
                            flags)) {
                        res[num++] = generateServiceInfo(pkg, s, flags, state, applicationInfo,
                                userId, pkgSetting);
                    }
                }
                info.services = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            final int size = pkg.getProviders().size();
            if (size > 0) {
                int num = 0;
                final ProviderInfo[] res = new ProviderInfo[size];
                for (int i = 0; i < size; i++) {
                    final ParsedProvider pr = pkg.getProviders()
                            .get(i);
                    if (ComponentParseUtils.isMatch(state, pkgSetting.isSystem(), pkg.isEnabled(), pr,
                            flags)) {
                        res[num++] = generateProviderInfo(pkg, pr, flags, state, applicationInfo,
                                userId, pkgSetting);
                    }
                }
                info.providers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            int N = pkg.getInstrumentations().size();
            if (N > 0) {
                info.instrumentation = new InstrumentationInfo[N];
                for (int i = 0; i < N; i++) {
                    info.instrumentation[i] = generateInstrumentationInfo(
                            pkg.getInstrumentations().get(i), pkg, flags, state,
                            userId, pkgSetting);
                }
            }
        }

        return info;
    }

    private static void updateApplicationInfo(ApplicationInfo ai, long flags,
            PackageUserState state) {
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            ai.metaData = null;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) == 0) {
            ai.sharedLibraryFiles = null;
            ai.sharedLibraryInfos = null;
        }

        // CompatibilityMode is global state.
        if (!ParsingPackageUtils.sCompatibilityModeEnabled) {
            ai.disableCompatibilityMode();
        }

        ai.flags |= flag(state.isStopped(), ApplicationInfo.FLAG_STOPPED)
                | flag(state.isInstalled(), ApplicationInfo.FLAG_INSTALLED)
                | flag(state.isSuspended(), ApplicationInfo.FLAG_SUSPENDED);
        ai.privateFlags |= flag(state.isInstantApp(), ApplicationInfo.PRIVATE_FLAG_INSTANT)
                | flag(state.isVirtualPreload(), ApplicationInfo.PRIVATE_FLAG_VIRTUAL_PRELOAD)
                | flag(state.isHidden(), ApplicationInfo.PRIVATE_FLAG_HIDDEN);

        if (state.getEnabledState() == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            ai.enabled = true;
        } else if (state.getEnabledState()
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
            ai.enabled = (flags & PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS) != 0;
        } else if (state.getEnabledState() == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state.getEnabledState()
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            ai.enabled = false;
        }
        ai.enabledSetting = state.getEnabledState();
        if (ai.category == ApplicationInfo.CATEGORY_UNDEFINED) {
            ai.category = FallbackCategoryProvider.getFallbackCategory(ai.packageName);
        }
        ai.seInfoUser = SELinuxUtil.getSeinfoUser(state);
        final OverlayPaths overlayPaths = state.getAllOverlayPaths();
        if (overlayPaths != null) {
            ai.resourceDirs = overlayPaths.getResourceDirs().toArray(new String[0]);
            ai.overlayPaths = overlayPaths.getOverlayPaths().toArray(new String[0]);
        }
    }

    @Nullable
    public static ApplicationInfo generateDelegateApplicationInfo(@Nullable ApplicationInfo ai,
            @PackageManager.ApplicationInfoFlagsBits long flags,
            @NonNull PackageUserState state, int userId) {
        if (ai == null || !checkUseInstalledOrHidden(flags, state, ai)) {
            return null;
        }

        ai = new ApplicationInfo(ai);
        ai.initForUser(userId);
        ai.icon = (ParsingPackageUtils.sUseRoundIcon && ai.roundIconRes != 0) ? ai.roundIconRes
                : ai.iconRes;
        updateApplicationInfo(ai, flags, state);
        return ai;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @VisibleForTesting
    @Nullable
    public static ApplicationInfo generateApplicationInfo(AndroidPackage pkg,
            @PackageManager.ApplicationInfoFlagsBits long flags,
            @NonNull PackageUserStateInternal state, @UserIdInt int userId,
            @NonNull PackageStateInternal pkgSetting) {
        if (pkg == null) {
            return null;
        }

        if (!checkUseInstalledOrHidden(pkg, pkgSetting, state, flags)
                || !AndroidPackageUtils.isMatchForSystemOnly(pkgSetting, flags)) {
            return null;
        }

        // Make shallow copy so we can store the metadata/libraries safely
        ApplicationInfo info = AndroidPackageUtils.generateAppInfoWithoutState(pkg);

        updateApplicationInfo(info, flags, state);

        initForUser(info, pkg, userId);

        // TODO(b/135203078): Remove PackageParser1/toAppInfoWithoutState and clean all this up
        PackageStateUnserialized pkgState = pkgSetting.getTransientState();
        info.hiddenUntilInstalled = pkgState.isHiddenUntilInstalled();
        List<String> usesLibraryFiles = pkgState.getUsesLibraryFiles();
        var usesLibraries = pkgState.getUsesLibraryInfos();
        var usesLibraryInfos = new ArrayList<SharedLibraryInfo>();
        for (int index = 0; index < usesLibraries.size(); index++) {
            usesLibraryInfos.add(usesLibraries.get(index).getInfo());
        }
        info.sharedLibraryFiles = usesLibraryFiles.isEmpty()
                ? null : usesLibraryFiles.toArray(new String[0]);
        info.sharedLibraryInfos = usesLibraryInfos.isEmpty() ? null : usesLibraryInfos;
        if (info.category == ApplicationInfo.CATEGORY_UNDEFINED) {
            info.category = pkgSetting.getCategoryOverride();
        }

        info.seInfo = pkgSetting.getSeInfo();
        info.primaryCpuAbi = pkgSetting.getPrimaryCpuAbi();
        info.secondaryCpuAbi = pkgSetting.getSecondaryCpuAbi();

        info.flags |= appInfoFlags(info.flags, pkgSetting);
        info.privateFlags |= appInfoPrivateFlags(info.privateFlags, pkgSetting);
        info.privateFlagsExt |= appInfoPrivateFlagsExt(info.privateFlagsExt, pkgSetting);

        return info;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @VisibleForTesting
    @Nullable
    public static ActivityInfo generateActivityInfo(AndroidPackage pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlagsBits long flags,
            @NonNull PackageUserStateInternal state, @UserIdInt int userId,
            @NonNull PackageStateInternal pkgSetting) {
        return generateActivityInfo(pkg, a, flags, state, null, userId, pkgSetting);
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @VisibleForTesting
    @Nullable
    public static ActivityInfo generateActivityInfo(AndroidPackage pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlagsBits long flags,
            @NonNull PackageUserStateInternal state, @Nullable ApplicationInfo applicationInfo,
            @UserIdInt int userId, @NonNull PackageStateInternal pkgSetting) {
        if (a == null) return null;
        if (!checkUseInstalledOrHidden(pkg, pkgSetting, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId, pkgSetting);
        }

        if (applicationInfo == null) {
            return null;
        }

        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo();
        ai.targetActivity = a.getTargetActivity();
        ai.processName = a.getProcessName();
        ai.exported = a.isExported();
        ai.theme = a.getTheme();
        ai.uiOptions = a.getUiOptions();
        ai.parentActivityName = a.getParentActivityName();
        ai.permission = a.getPermission();
        ai.taskAffinity = a.getTaskAffinity();
        ai.flags = a.getFlags();
        ai.privateFlags = a.getPrivateFlags();
        ai.launchMode = a.getLaunchMode();
        ai.documentLaunchMode = a.getDocumentLaunchMode();
        ai.maxRecents = a.getMaxRecents();
        ai.configChanges = a.getConfigChanges();
        ai.softInputMode = a.getSoftInputMode();
        ai.persistableMode = a.getPersistableMode();
        ai.lockTaskLaunchMode = a.getLockTaskLaunchMode();
        ai.screenOrientation = a.getScreenOrientation();
        ai.resizeMode = a.getResizeMode();
        ai.setMaxAspectRatio(a.getMaxAspectRatio());
        ai.setMinAspectRatio(a.getMinAspectRatio());
        ai.supportsSizeChanges = a.isSupportsSizeChanges();
        ai.requestedVrComponent = a.getRequestedVrComponent();
        ai.rotationAnimation = a.getRotationAnimation();
        ai.colorMode = a.getColorMode();
        ai.windowLayout = a.getWindowLayout();
        ai.attributionTags = a.getAttributionTags();
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            var metaData = a.getMetaData();
            // Backwards compatibility, coerce to null if empty
            ai.metaData = metaData.isEmpty() ? null : metaData;
        } else {
            ai.metaData = null;
        }
        ai.applicationInfo = applicationInfo;
        ai.requiredDisplayCategory = a.getRequiredDisplayCategory();
        ai.setKnownActivityEmbeddingCerts(a.getKnownActivityEmbeddingCerts());
        assignFieldsComponentInfoParsedMainComponent(ai, a, pkgSetting, userId);
        return ai;
    }

    @Nullable
    public static ActivityInfo generateDelegateActivityInfo(@Nullable ActivityInfo a,
            @PackageManager.ComponentInfoFlagsBits long flags,
            @NonNull PackageUserState state, int userId) {
        if (a == null || !checkUseInstalledOrHidden(flags, state, a.applicationInfo)) {
            return null;
        }
        // This is used to return the ResolverActivity or instantAppInstallerActivity;
        // we will just always make a copy.
        final ActivityInfo ai = new ActivityInfo(a);
        ai.applicationInfo =
                generateDelegateApplicationInfo(ai.applicationInfo, flags, state, userId);
        return ai;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static ServiceInfo generateServiceInfo(AndroidPackage pkg, ParsedService s,
            @PackageManager.ComponentInfoFlagsBits long flags, PackageUserStateInternal state,
            @UserIdInt int userId, @NonNull PackageStateInternal pkgSetting) {
        return generateServiceInfo(pkg, s, flags, state, null, userId, pkgSetting);
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @VisibleForTesting
    @Nullable
    public static ServiceInfo generateServiceInfo(AndroidPackage pkg, ParsedService s,
            @PackageManager.ComponentInfoFlagsBits long flags, PackageUserStateInternal state,
            @Nullable ApplicationInfo applicationInfo, int userId,
            @NonNull PackageStateInternal pkgSetting) {
        if (s == null) return null;
        if (!checkUseInstalledOrHidden(pkg, pkgSetting, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId, pkgSetting);
        }
        if (applicationInfo == null) {
            return null;
        }


        // Make shallow copies so we can store the metadata safely
        ServiceInfo si = new ServiceInfo();
        si.exported = s.isExported();
        si.flags = s.getFlags();
        si.permission = s.getPermission();
        si.processName = s.getProcessName();
        si.mForegroundServiceType = s.getForegroundServiceType();
        si.applicationInfo = applicationInfo;
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            var metaData = s.getMetaData();
            // Backwards compatibility, coerce to null if empty
            si.metaData = metaData.isEmpty() ? null : metaData;
        }
        assignFieldsComponentInfoParsedMainComponent(si, s, pkgSetting, userId);
        return si;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @VisibleForTesting
    @Nullable
    public static ProviderInfo generateProviderInfo(AndroidPackage pkg, ParsedProvider p,
            @PackageManager.ComponentInfoFlagsBits long flags, PackageUserStateInternal state,
            @NonNull ApplicationInfo applicationInfo, int userId,
            @NonNull PackageStateInternal pkgSetting) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(pkg, pkgSetting, state, flags)) {
            return null;
        }
        if (applicationInfo == null || !pkg.getPackageName().equals(applicationInfo.packageName)) {
            Slog.wtf(TAG, "AppInfo's package name is different. Expected=" + pkg.getPackageName()
                    + " actual=" + (applicationInfo == null ? "(null AppInfo)"
                    : applicationInfo.packageName));
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId, pkgSetting);
        }
        if (applicationInfo == null) {
            return null;
        }

        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo();
        pi.exported = p.isExported();
        pi.flags = p.getFlags();
        pi.processName = p.getProcessName();
        pi.authority = p.getAuthority();
        pi.isSyncable = p.isSyncable();
        pi.readPermission = p.getReadPermission();
        pi.writePermission = p.getWritePermission();
        pi.grantUriPermissions = p.isGrantUriPermissions();
        pi.forceUriPermissions = p.isForceUriPermissions();
        pi.multiprocess = p.isMultiProcess();
        pi.initOrder = p.getInitOrder();
        pi.uriPermissionPatterns = p.getUriPermissionPatterns().toArray(new PatternMatcher[0]);
        pi.pathPermissions = p.getPathPermissions().toArray(new PathPermission[0]);
        if ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            var metaData = p.getMetaData();
            // Backwards compatibility, coerce to null if empty
            pi.metaData = metaData.isEmpty() ? null : metaData;
        }
        pi.applicationInfo = applicationInfo;
        assignFieldsComponentInfoParsedMainComponent(pi, p, pkgSetting, userId);
        return pi;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static InstrumentationInfo generateInstrumentationInfo(ParsedInstrumentation i,
            AndroidPackage pkg, @PackageManager.ComponentInfoFlagsBits long flags,
            PackageUserStateInternal state, int userId, @NonNull PackageStateInternal pkgSetting) {
        if (i == null) return null;
        if (!checkUseInstalledOrHidden(pkg, pkgSetting, state, flags)) {
            return null;
        }

        InstrumentationInfo info = new InstrumentationInfo();
        info.targetPackage = i.getTargetPackage();
        info.targetProcesses = i.getTargetProcesses();
        info.handleProfiling = i.isHandleProfiling();
        info.functionalTest = i.isFunctionalTest();

        info.sourceDir = pkg.getBaseApkPath();
        info.publicSourceDir = pkg.getBaseApkPath();
        info.splitNames = pkg.getSplitNames();
        info.splitSourceDirs = pkg.getSplitCodePaths().length == 0 ? null : pkg.getSplitCodePaths();
        info.splitPublicSourceDirs = pkg.getSplitCodePaths().length == 0
                ? null : pkg.getSplitCodePaths();
        info.splitDependencies = pkg.getSplitDependencies().size() == 0
                ? null : pkg.getSplitDependencies();

        initForUser(info, pkg, userId);

        info.primaryCpuAbi = pkgSetting.getPrimaryCpuAbi();
        info.secondaryCpuAbi = pkgSetting.getSecondaryCpuAbi();
        info.nativeLibraryDir = pkg.getNativeLibraryDir();
        info.secondaryNativeLibraryDir = pkg.getSecondaryNativeLibraryDir();

        assignFieldsPackageItemInfoParsedComponent(info, i, pkgSetting, userId);

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            info.metaData = null;
        } else {
            var metaData = i.getMetaData();
            // Backwards compatibility, coerce to null if empty
            info.metaData = metaData.isEmpty() ? null : metaData;
        }

        return info;
    }

    // TODO(b/135203078): Determine if permission methods need to pass in a non-null
    //  PackageStateInternal os that checkUseInstalledOrHidden filter can apply
    @Nullable
    public static PermissionInfo generatePermissionInfo(ParsedPermission p,
            @PackageManager.ComponentInfoFlagsBits long flags) {
        // TODO(b/135203078): Remove null checks and make all usages @NonNull
        if (p == null) return null;

        PermissionInfo pi = new PermissionInfo(p.getBackgroundPermission());

        assignFieldsPackageItemInfoParsedComponent(pi, p);

        pi.group = p.getGroup();
        pi.requestRes = p.getRequestRes();
        pi.protectionLevel = p.getProtectionLevel();
        pi.descriptionRes = p.getDescriptionRes();
        pi.flags = p.getFlags();
        pi.knownCerts = p.getKnownCerts();

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            pi.metaData = null;
        } else {
            var metaData = p.getMetaData();
            // Backwards compatibility, coerce to null if empty
            pi.metaData = metaData.isEmpty() ? null : metaData;
        }
        return pi;
    }

    @Nullable
    public static PermissionGroupInfo generatePermissionGroupInfo(ParsedPermissionGroup pg,
            @PackageManager.ComponentInfoFlagsBits long flags) {
        if (pg == null) return null;

        PermissionGroupInfo pgi = new PermissionGroupInfo(
                pg.getRequestDetailRes(),
                pg.getBackgroundRequestRes(),
                pg.getBackgroundRequestDetailRes()
        );

        assignFieldsPackageItemInfoParsedComponent(pgi, pg);
        pgi.descriptionRes = pg.getDescriptionRes();
        pgi.priority = pg.getPriority();
        pgi.requestRes = pg.getRequestRes();
        pgi.flags = pg.getFlags();

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            pgi.metaData = null;
        } else {
            var metaData = pg.getMetaData();
            // Backwards compatibility, coerce to null if empty
            pgi.metaData = metaData.isEmpty() ? null : metaData;
        }

        return pgi;
    }

    @Nullable
    public static ArrayMap<String, ProcessInfo> generateProcessInfo(
            Map<String, ParsedProcess> procs, @PackageManager.ComponentInfoFlagsBits long flags) {
        if (procs == null) {
            return null;
        }

        final int numProcs = procs.size();
        ArrayMap<String, ProcessInfo> retProcs = new ArrayMap<>(numProcs);
        for (String key : procs.keySet()) {
            ParsedProcess proc = procs.get(key);
            retProcs.put(proc.getName(),
                    new ProcessInfo(proc.getName(), new ArraySet<>(proc.getDeniedPermissions()),
                            proc.getGwpAsanMode(), proc.getMemtagMode(),
                            proc.getNativeHeapZeroInitialized()));
        }
        return retProcs;
    }

    /**
     * Returns true if the package is installed and not hidden, or if the caller explicitly wanted
     * all uninstalled and hidden packages as well.
     */
    public static boolean checkUseInstalledOrHidden(AndroidPackage pkg,
            @NonNull PackageStateInternal pkgSetting, PackageUserStateInternal state,
            @PackageManager.PackageInfoFlagsBits long flags) {
        // Returns false if the package is hidden system app until installed.
        if ((flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) == 0
                && !state.isInstalled()
                && pkgSetting.getTransientState().isHiddenUntilInstalled()) {
            return false;
        }

        // If available for the target user, or trying to match uninstalled packages and it's
        // a system app.
        return PackageUserStateUtils.isAvailable(state, flags)
                || (pkgSetting.isSystem()
                && ((flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0
                || (flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) != 0));
    }

    private static boolean checkUseInstalledOrHidden(long flags,
            @NonNull PackageUserState state, @Nullable ApplicationInfo appInfo) {
        // Returns false if the package is hidden system app until installed.
        if ((flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) == 0
                && !state.isInstalled()
                && appInfo != null && appInfo.hiddenUntilInstalled) {
            return false;
        }

        // If available for the target user, or trying to match uninstalled packages and it's
        // a system app.
        return PackageUserStateUtils.isAvailable(state, flags)
                || (appInfo != null && appInfo.isSystemApp()
                && ((flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0
                || (flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) != 0));
    }

    private static void assignFieldsComponentInfoParsedMainComponent(
            @NonNull ComponentInfo info, @NonNull ParsedMainComponent component) {
        assignFieldsPackageItemInfoParsedComponent(info, component);
        info.descriptionRes = component.getDescriptionRes();
        info.directBootAware = component.isDirectBootAware();
        info.enabled = component.isEnabled();
        info.splitName = component.getSplitName();
        info.attributionTags = component.getAttributionTags();
    }

    private static void assignFieldsPackageItemInfoParsedComponent(
            @NonNull PackageItemInfo packageItemInfo, @NonNull ParsedComponent component) {
        packageItemInfo.nonLocalizedLabel = ComponentParseUtils.getNonLocalizedLabel(component);
        packageItemInfo.icon = ComponentParseUtils.getIcon(component);
        packageItemInfo.banner = component.getBanner();
        packageItemInfo.labelRes = component.getLabelRes();
        packageItemInfo.logo = component.getLogo();
        packageItemInfo.name = component.getName();
        packageItemInfo.packageName = component.getPackageName();
    }

    private static void assignFieldsComponentInfoParsedMainComponent(
            @NonNull ComponentInfo info, @NonNull ParsedMainComponent component,
            @NonNull PackageStateInternal pkgSetting, @UserIdInt int userId) {
        assignFieldsComponentInfoParsedMainComponent(info, component);
        Pair<CharSequence, Integer> labelAndIcon =
                ParsedComponentStateUtils.getNonLocalizedLabelAndIcon(component, pkgSetting,
                        userId);
        info.nonLocalizedLabel = labelAndIcon.first;
        info.icon = labelAndIcon.second;
    }

    private static void assignFieldsPackageItemInfoParsedComponent(
            @NonNull PackageItemInfo info, @NonNull ParsedComponent component,
            @NonNull PackageStateInternal pkgSetting, @UserIdInt int userId) {
        assignFieldsPackageItemInfoParsedComponent(info, component);
        Pair<CharSequence, Integer> labelAndIcon =
                ParsedComponentStateUtils.getNonLocalizedLabelAndIcon(component, pkgSetting,
                        userId);
        info.nonLocalizedLabel = labelAndIcon.first;
        info.icon = labelAndIcon.second;
    }

    @CheckResult
    private static int flag(boolean hasFlag, int flag) {
        return hasFlag ? flag : 0;
    }

    /**
     * @see ApplicationInfo#flags
     */
    public static int appInfoFlags(AndroidPackage pkg, @Nullable PackageStateInternal pkgSetting) {
        // @formatter:off
        int pkgWithoutStateFlags = flag(pkg.isExternalStorage(), ApplicationInfo.FLAG_EXTERNAL_STORAGE)
                | flag(pkg.isHardwareAccelerated(), ApplicationInfo.FLAG_HARDWARE_ACCELERATED)
                | flag(pkg.isAllowBackup(), ApplicationInfo.FLAG_ALLOW_BACKUP)
                | flag(pkg.isKillAfterRestore(), ApplicationInfo.FLAG_KILL_AFTER_RESTORE)
                | flag(pkg.isRestoreAnyVersion(), ApplicationInfo.FLAG_RESTORE_ANY_VERSION)
                | flag(pkg.isFullBackupOnly(), ApplicationInfo.FLAG_FULL_BACKUP_ONLY)
                | flag(pkg.isPersistent(), ApplicationInfo.FLAG_PERSISTENT)
                | flag(pkg.isDebuggable(), ApplicationInfo.FLAG_DEBUGGABLE)
                | flag(pkg.isVmSafeMode(), ApplicationInfo.FLAG_VM_SAFE_MODE)
                | flag(pkg.isHasCode(), ApplicationInfo.FLAG_HAS_CODE)
                | flag(pkg.isAllowTaskReparenting(), ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING)
                | flag(pkg.isAllowClearUserData(), ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA)
                | flag(pkg.isLargeHeap(), ApplicationInfo.FLAG_LARGE_HEAP)
                | flag(pkg.isUsesCleartextTraffic(), ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
                | flag(pkg.isSupportsRtl(), ApplicationInfo.FLAG_SUPPORTS_RTL)
                | flag(pkg.isTestOnly(), ApplicationInfo.FLAG_TEST_ONLY)
                | flag(pkg.isMultiArch(), ApplicationInfo.FLAG_MULTIARCH)
                | flag(pkg.isExtractNativeLibs(), ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS)
                | flag(pkg.isGame(), ApplicationInfo.FLAG_IS_GAME)
                | flag(pkg.isSupportsSmallScreens(), ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS)
                | flag(pkg.isSupportsNormalScreens(), ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS)
                | flag(pkg.isSupportsLargeScreens(), ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS)
                | flag(pkg.isSupportsExtraLargeScreens(), ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS)
                | flag(pkg.isResizeable(), ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS)
                | flag(pkg.isAnyDensity(), ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES)
                | flag(AndroidPackageUtils.isSystem(pkg), ApplicationInfo.FLAG_SYSTEM)
                | flag(pkg.isFactoryTest(), ApplicationInfo.FLAG_FACTORY_TEST);

        return appInfoFlags(pkgWithoutStateFlags, pkgSetting);
        // @formatter:on
    }

    /** @see ApplicationInfo#flags */
    public static int appInfoFlags(int pkgWithoutStateFlags,
            @NonNull PackageStateInternal pkgSetting) {
        // @formatter:off
        int flags = pkgWithoutStateFlags;
        if (pkgSetting != null) {
            flags |= flag(pkgSetting.isUpdatedSystemApp(), ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
        }
        return flags;
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlags */
    public static int appInfoPrivateFlags(AndroidPackage pkg,
            @Nullable PackageStateInternal pkgSetting) {
        // @formatter:off
        int pkgWithoutStateFlags = flag(pkg.isStaticSharedLibrary(), ApplicationInfo.PRIVATE_FLAG_STATIC_SHARED_LIBRARY)
                | flag(pkg.isResourceOverlay(), ApplicationInfo.PRIVATE_FLAG_IS_RESOURCE_OVERLAY)
                | flag(pkg.isIsolatedSplitLoading(), ApplicationInfo.PRIVATE_FLAG_ISOLATED_SPLIT_LOADING)
                | flag(pkg.isHasDomainUrls(), ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS)
                | flag(pkg.isProfileableByShell(), ApplicationInfo.PRIVATE_FLAG_PROFILEABLE_BY_SHELL)
                | flag(pkg.isBackupInForeground(), ApplicationInfo.PRIVATE_FLAG_BACKUP_IN_FOREGROUND)
                | flag(pkg.isUseEmbeddedDex(), ApplicationInfo.PRIVATE_FLAG_USE_EMBEDDED_DEX)
                | flag(pkg.isDefaultToDeviceProtectedStorage(), ApplicationInfo.PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE)
                | flag(pkg.isDirectBootAware(), ApplicationInfo.PRIVATE_FLAG_DIRECT_BOOT_AWARE)
                | flag(pkg.isPartiallyDirectBootAware(), ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE)
                | flag(pkg.isAllowClearUserDataOnFailedRestore(), ApplicationInfo.PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE)
                | flag(pkg.isAllowAudioPlaybackCapture(), ApplicationInfo.PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE)
                | flag(pkg.isRequestLegacyExternalStorage(), ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE)
                | flag(pkg.isUsesNonSdkApi(), ApplicationInfo.PRIVATE_FLAG_USES_NON_SDK_API)
                | flag(pkg.isHasFragileUserData(), ApplicationInfo.PRIVATE_FLAG_HAS_FRAGILE_USER_DATA)
                | flag(pkg.isCantSaveState(), ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE)
                | flag(pkg.isResizeableActivityViaSdkVersion(), ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION)
                | flag(pkg.isAllowNativeHeapPointerTagging(), ApplicationInfo.PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING)
                | flag(AndroidPackageUtils.isSystemExt(pkg), ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT)
                | flag(AndroidPackageUtils.isPrivileged(pkg), ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                | flag(AndroidPackageUtils.isOem(pkg), ApplicationInfo.PRIVATE_FLAG_OEM)
                | flag(AndroidPackageUtils.isVendor(pkg), ApplicationInfo.PRIVATE_FLAG_VENDOR)
                | flag(AndroidPackageUtils.isProduct(pkg), ApplicationInfo.PRIVATE_FLAG_PRODUCT)
                | flag(AndroidPackageUtils.isOdm(pkg), ApplicationInfo.PRIVATE_FLAG_ODM)
                | flag(pkg.isSignedWithPlatformKey(), ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY);

        Boolean resizeableActivity = pkg.getResizeableActivity();
        if (resizeableActivity != null) {
            if (resizeableActivity) {
                pkgWithoutStateFlags |= ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE;
            } else {
                pkgWithoutStateFlags |= ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE;
            }
        }

        return appInfoPrivateFlags(pkgWithoutStateFlags, pkgSetting);
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlags */
    public static int appInfoPrivateFlags(int pkgWithoutStateFlags,
            @Nullable PackageStateInternal pkgSetting) {
        // @formatter:off
        // TODO: Add state specific flags
        return pkgWithoutStateFlags;
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlagsExt */
    public static int appInfoPrivateFlagsExt(AndroidPackage pkg,
                                             @Nullable PackageStateInternal pkgSetting) {
        var isAllowlistedForHiddenApis = SystemConfig.getInstance().getHiddenApiWhitelistedApps()
                .contains(pkg.getPackageName());
        // @formatter:off
        int pkgWithoutStateFlags = flag(pkg.isProfileable(), ApplicationInfo.PRIVATE_FLAG_EXT_PROFILEABLE)
                | flag(pkg.hasRequestForegroundServiceExemption(), ApplicationInfo.PRIVATE_FLAG_EXT_REQUEST_FOREGROUND_SERVICE_EXEMPTION)
                | flag(pkg.isAttributionsUserVisible(), ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE)
                | flag(pkg.isOnBackInvokedCallbackEnabled(), ApplicationInfo.PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK)
                | flag(isAllowlistedForHiddenApis, ApplicationInfo.PRIVATE_FLAG_EXT_ALLOWLISTED_FOR_HIDDEN_APIS);
        return appInfoPrivateFlagsExt(pkgWithoutStateFlags, pkgSetting);
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlagsExt */
    public static int appInfoPrivateFlagsExt(int pkgWithoutStateFlags,
                                             @Nullable PackageStateInternal pkgSetting) {
        // @formatter:off
        // TODO: Add state specific flags
        return pkgWithoutStateFlags;
        // @formatter:on
    }

    private static void initForUser(ApplicationInfo output, AndroidPackage input,
            @UserIdInt int userId) {
        PackageImpl pkg = ((PackageImpl) input);
        String packageName = input.getPackageName();
        output.uid = UserHandle.getUid(userId, UserHandle.getAppId(input.getUid()));

        if ("android".equals(packageName)) {
            output.dataDir = SYSTEM_DATA_PATH;
            return;
        }

        // For performance reasons, all these paths are built as strings
        if (userId == UserHandle.USER_SYSTEM) {
            output.credentialProtectedDataDir =
                    pkg.getBaseAppDataCredentialProtectedDirForSystemUser() + packageName;
            output.deviceProtectedDataDir =
                    pkg.getBaseAppDataDeviceProtectedDirForSystemUser() + packageName;
        } else {
            // Convert /data/user/0/ -> /data/user/1/com.example.app
            String userIdString = String.valueOf(userId);
            int credentialLength = pkg.getBaseAppDataCredentialProtectedDirForSystemUser().length();
            output.credentialProtectedDataDir =
                    new StringBuilder(pkg.getBaseAppDataCredentialProtectedDirForSystemUser())
                            .replace(credentialLength - 2, credentialLength - 1, userIdString)
                            .append(packageName)
                            .toString();
            int deviceLength = pkg.getBaseAppDataDeviceProtectedDirForSystemUser().length();
            output.deviceProtectedDataDir =
                    new StringBuilder(pkg.getBaseAppDataDeviceProtectedDirForSystemUser())
                            .replace(deviceLength - 2, deviceLength - 1, userIdString)
                            .append(packageName)
                            .toString();
        }

        if (input.isDefaultToDeviceProtectedStorage()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            output.dataDir = output.deviceProtectedDataDir;
        } else {
            output.dataDir = output.credentialProtectedDataDir;
        }
    }

    // This duplicates the ApplicationInfo variant because it uses field assignment and the classes
    // don't inherit from each other, unfortunately. Consolidating logic would introduce overhead.
    private static void initForUser(InstrumentationInfo output, AndroidPackage input,
            @UserIdInt int userId) {
        PackageImpl pkg = ((PackageImpl) input);
        String packageName = input.getPackageName();
        if ("android".equals(packageName)) {
            output.dataDir = SYSTEM_DATA_PATH;
            return;
        }

        // For performance reasons, all these paths are built as strings
        if (userId == UserHandle.USER_SYSTEM) {
            output.credentialProtectedDataDir =
                    pkg.getBaseAppDataCredentialProtectedDirForSystemUser() + packageName;
            output.deviceProtectedDataDir =
                    pkg.getBaseAppDataDeviceProtectedDirForSystemUser() + packageName;
        } else {
            // Convert /data/user/0/ -> /data/user/1/com.example.app
            String userIdString = String.valueOf(userId);
            int credentialLength = pkg.getBaseAppDataCredentialProtectedDirForSystemUser().length();
            output.credentialProtectedDataDir =
                    new StringBuilder(pkg.getBaseAppDataCredentialProtectedDirForSystemUser())
                            .replace(credentialLength - 2, credentialLength - 1, userIdString)
                            .append(packageName)
                            .toString();
            int deviceLength = pkg.getBaseAppDataDeviceProtectedDirForSystemUser().length();
            output.deviceProtectedDataDir =
                    new StringBuilder(pkg.getBaseAppDataDeviceProtectedDirForSystemUser())
                            .replace(deviceLength - 2, deviceLength - 1, userIdString)
                            .append(packageName)
                            .toString();
        }

        if (input.isDefaultToDeviceProtectedStorage()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            output.dataDir = output.deviceProtectedDataDir;
        } else {
            output.dataDir = output.credentialProtectedDataDir;
        }
    }

    @NonNull
    public static File getDataDir(AndroidPackage pkg, int userId) {
        if ("android".equals(pkg.getPackageName())) {
            return Environment.getDataSystemDirectory();
        }

        if (pkg.isDefaultToDeviceProtectedStorage()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            return Environment.getDataUserDePackageDirectory(pkg.getVolumeUuid(), userId,
                    pkg.getPackageName());
        } else {
            return Environment.getDataUserCePackageDirectory(pkg.getVolumeUuid(), userId,
                    pkg.getPackageName());
        }
    }

    /**
     * Wraps {@link PackageInfoUtils#generateApplicationInfo} with a cache.
     */
    public static class CachedApplicationInfoGenerator {
        // Map from a package name to the corresponding app info.
        private ArrayMap<String, ApplicationInfo> mCache = new ArrayMap<>();

        /**
         * {@link PackageInfoUtils#generateApplicationInfo} with a cache.
         */
        @Nullable
        public ApplicationInfo generate(AndroidPackage pkg,
                @PackageManager.ApplicationInfoFlagsBits long flags, PackageUserStateInternal state,
                int userId, @NonNull PackageStateInternal pkgSetting) {
            ApplicationInfo appInfo = mCache.get(pkg.getPackageName());
            if (appInfo != null) {
                return appInfo;
            }
            appInfo = PackageInfoUtils.generateApplicationInfo(
                    pkg, flags, state, userId, pkgSetting);
            mCache.put(pkg.getPackageName(), appInfo);
            return appInfo;
        }
    }
}
