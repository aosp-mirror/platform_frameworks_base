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

package android.content.pm.parsing;

import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
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
import android.content.pm.PackageUserState;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.SELinuxUtil;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.overlay.OverlayPaths;
import android.content.pm.parsing.component.ComponentParseUtils;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedAttribution;
import android.content.pm.parsing.component.ParsedComponent;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;
import android.content.pm.parsing.component.ParsedUsesPermission;
import android.os.Environment;
import android.os.UserHandle;

import com.android.internal.util.ArrayUtils;

import libcore.util.EmptyArray;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** @hide **/
public class PackageInfoWithoutStateUtils {

    public static final String SYSTEM_DATA_PATH =
            Environment.getDataDirectoryPath() + File.separator + "system";

    @Nullable
    public static PackageInfo generate(ParsingPackageRead pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId) {
        return generateWithComponents(pkg, gids, flags, firstInstallTime, lastUpdateTime, grantedPermissions,
                state, userId, null);
    }

    @Nullable
    public static PackageInfo generate(ParsingPackageRead pkg, ApexInfo apexInfo, int flags) {
        return generateWithComponents(pkg, EmptyArray.INT, flags, 0, 0, Collections.emptySet(),
                new PackageUserState(), UserHandle.getCallingUserId(), apexInfo);
    }

    @Nullable
    private static PackageInfo generateWithComponents(ParsingPackageRead pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId,
            @Nullable ApexInfo apexInfo) {
        ApplicationInfo applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        if (applicationInfo == null) {
            return null;
        }
        PackageInfo info = generateWithoutComponents(pkg, gids, flags, firstInstallTime,
                lastUpdateTime, grantedPermissions, state, userId, apexInfo, applicationInfo);

        if (info == null) {
            return null;
        }

        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            final int N = pkg.getActivities().size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final ParsedActivity a = pkg.getActivities().get(i);
                    if (ComponentParseUtils.isMatch(state, false, pkg.isEnabled(), a,
                            flags)) {
                        if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(
                                a.getName())) {
                            continue;
                        }
                        res[num++] = generateActivityInfo(pkg, a, flags, state,
                                applicationInfo, userId);
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
                    if (ComponentParseUtils.isMatch(state, false, pkg.isEnabled(), a,
                            flags)) {
                        res[num++] = generateActivityInfo(pkg, a, flags, state,
                                applicationInfo, userId);
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
                    if (ComponentParseUtils.isMatch(state, false, pkg.isEnabled(), s,
                            flags)) {
                        res[num++] = generateServiceInfo(pkg, s, flags, state,
                                applicationInfo, userId);
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
                    if (ComponentParseUtils.isMatch(state, false, pkg.isEnabled(), pr,
                            flags)) {
                        res[num++] = generateProviderInfo(pkg, pr, flags, state,
                                applicationInfo, userId);
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
                            pkg.getInstrumentations().get(i), pkg, flags, userId,
                            true /* assignUserFields */);
                }
            }
        }

        return info;
    }

    @Nullable
    public static PackageInfo generateWithoutComponents(ParsingPackageRead pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId,
            @Nullable ApexInfo apexInfo, @NonNull ApplicationInfo applicationInfo) {
        if (!checkUseInstalled(pkg, state, flags)) {
            return null;
        }

        return generateWithoutComponentsUnchecked(pkg, gids, flags, firstInstallTime,
                lastUpdateTime, grantedPermissions, state, userId, apexInfo, applicationInfo);
    }

    /**
     * This bypasses critical checks that are necessary for usage with data passed outside of
     * system server.
     *
     * Prefer {@link #generateWithoutComponents(ParsingPackageRead, int[], int, long, long, Set,
     * PackageUserState, int, ApexInfo, ApplicationInfo)}.
     */
    @NonNull
    public static PackageInfo generateWithoutComponentsUnchecked(ParsingPackageRead pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId,
            @Nullable ApexInfo apexInfo, @NonNull ApplicationInfo applicationInfo) {
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
            int size = pkg.getConfigPreferences().size();
            if (size > 0) {
                pi.configPreferences = new ConfigurationInfo[size];
                pkg.getConfigPreferences().toArray(pi.configPreferences);
            }
            size = pkg.getReqFeatures().size();
            if (size > 0) {
                pi.reqFeatures = new FeatureInfo[size];
                pkg.getReqFeatures().toArray(pi.reqFeatures);
            }
            size = pkg.getFeatureGroups().size();
            if (size > 0) {
                pi.featureGroups = new FeatureGroupInfo[size];
                pkg.getFeatureGroups().toArray(pi.featureGroups);
            }
        }
        if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
            int size = ArrayUtils.size(pkg.getPermissions());
            if (size > 0) {
                pi.permissions = new PermissionInfo[size];
                for (int i = 0; i < size; i++) {
                    pi.permissions[i] = generatePermissionInfo(pkg.getPermissions().get(i),
                            flags);
                }
            }
            final List<ParsedUsesPermission> usesPermissions = pkg.getUsesPermissions();
            size = usesPermissions.size();
            if (size > 0) {
                pi.requestedPermissions = new String[size];
                pi.requestedPermissionsFlags = new int[size];
                for (int i = 0; i < size; i++) {
                    final ParsedUsesPermission usesPermission = usesPermissions.get(i);
                    pi.requestedPermissions[i] = usesPermission.name;
                    // The notion of required permissions is deprecated but for compatibility.
                    pi.requestedPermissionsFlags[i] |=
                            PackageInfo.REQUESTED_PERMISSION_REQUIRED;
                    if (grantedPermissions != null
                            && grantedPermissions.contains(usesPermission.name)) {
                        pi.requestedPermissionsFlags[i] |=
                                PackageInfo.REQUESTED_PERMISSION_GRANTED;
                    }
                    if ((usesPermission.usesPermissionFlags
                            & ParsedUsesPermission.FLAG_NEVER_FOR_LOCATION) != 0) {
                        pi.requestedPermissionsFlags[i] |=
                                PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION;
                    }
                }
            }
        }
        if ((flags & PackageManager.GET_ATTRIBUTIONS) != 0) {
            int size = ArrayUtils.size(pkg.getAttributions());
            if (size > 0) {
                pi.attributions = new Attribution[size];
                for (int i = 0; i < size; i++) {
                    pi.attributions[i] = generateAttribution(pkg.getAttributions().get(i));
                }
            }
            if (pkg.areAttributionsUserVisible()) {
                pi.applicationInfo.privateFlagsExt
                        |= ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE;
            } else {
                pi.applicationInfo.privateFlagsExt
                        &= ~ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE;
            }
        } else {
            pi.applicationInfo.privateFlagsExt
                    &= ~ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE;
        }

        if (apexInfo != null) {
            File apexFile = new File(apexInfo.modulePath);

            pi.applicationInfo.sourceDir = apexFile.getPath();
            pi.applicationInfo.publicSourceDir = apexFile.getPath();
            if (apexInfo.isFactory) {
                pi.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
                pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            } else {
                pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_SYSTEM;
                pi.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            }
            if (apexInfo.isActive) {
                pi.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
            } else {
                pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_INSTALLED;
            }
            pi.isApex = true;
        }

        final SigningDetails signingDetails = pkg.getSigningDetails();
        // deprecated method of getting signing certificates
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            if (signingDetails.hasPastSigningCertificates()) {
                // Package has included signing certificate rotation information.  Return the oldest
                // cert so that programmatic checks keep working even if unaware of key rotation.
                pi.signatures = new Signature[1];
                pi.signatures[0] = signingDetails.getPastSigningCertificates()[0];
            } else if (signingDetails.hasSignatures()) {
                // otherwise keep old behavior
                int numberOfSigs = signingDetails.getSignatures().length;
                pi.signatures = new Signature[numberOfSigs];
                System.arraycopy(signingDetails.getSignatures(), 0, pi.signatures, 0,
                        numberOfSigs);
            }
        }

        // replacement for GET_SIGNATURES
        if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            if (signingDetails != SigningDetails.UNKNOWN) {
                // only return a valid SigningInfo if there is signing information to report
                pi.signingInfo = new SigningInfo(signingDetails);
            } else {
                pi.signingInfo = null;
            }
        }

        return pi;
    }

    @Nullable
    public static ApplicationInfo generateApplicationInfo(ParsingPackageRead pkg,
            @PackageManager.ApplicationInfoFlags int flags, PackageUserState state, int userId) {
        if (pkg == null) {
            return null;
        }

        if (!checkUseInstalled(pkg, state, flags)) {
            return null;
        }

        return generateApplicationInfoUnchecked(pkg, flags, state, userId,
                true /* assignUserFields */);
    }

    /**
     * This bypasses critical checks that are necessary for usage with data passed outside of
     * system server.
     *
     * Prefer {@link #generateApplicationInfo(ParsingPackageRead, int, PackageUserState, int)}.
     *
     * @param assignUserFields whether to fill the returned {@link ApplicationInfo} with user
     *                         specific fields. This can be skipped when building from a system
     *                         server package, as there are cached strings which can be used rather
     *                         than querying and concatenating the comparatively expensive
     *                         {@link Environment#getDataDirectory(String)}}.
     */
    @NonNull
    public static ApplicationInfo generateApplicationInfoUnchecked(@NonNull ParsingPackageRead pkg,
            @PackageManager.ApplicationInfoFlags int flags, PackageUserState state, int userId,
            boolean assignUserFields) {
        // Make shallow copy so we can store the metadata/libraries safely
        ApplicationInfo ai = pkg.toAppInfoWithoutState();

        if (assignUserFields) {
            assignUserFields(pkg, ai, userId);
        }

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            ai.metaData = null;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) == 0) {
            ai.sharedLibraryFiles = null;
            ai.sharedLibraryInfos = null;
        }

        // CompatibilityMode is global state.
        if (!android.content.pm.PackageParser.sCompatibilityModeEnabled) {
            ai.disableCompatibilityMode();
        }

        ai.flags |= flag(state.stopped, ApplicationInfo.FLAG_STOPPED)
                | flag(state.installed, ApplicationInfo.FLAG_INSTALLED)
                | flag(state.suspended, ApplicationInfo.FLAG_SUSPENDED);
        ai.privateFlags |= flag(state.instantApp, ApplicationInfo.PRIVATE_FLAG_INSTANT)
                | flag(state.virtualPreload, ApplicationInfo.PRIVATE_FLAG_VIRTUAL_PRELOAD)
                | flag(state.hidden, ApplicationInfo.PRIVATE_FLAG_HIDDEN);

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
        final OverlayPaths overlayPaths = state.getAllOverlayPaths();
        if (overlayPaths != null) {
            ai.resourceDirs = overlayPaths.getResourceDirs().toArray(new String[0]);
            ai.overlayPaths = overlayPaths.getOverlayPaths().toArray(new String[0]);
        }

        return ai;
    }

    @Nullable
    public static ActivityInfo generateActivityInfo(ParsingPackageRead pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state,
            @Nullable ApplicationInfo applicationInfo, int userId) {
        if (a == null) return null;
        if (!checkUseInstalled(pkg, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        if (applicationInfo == null) {
            return null;
        }

        return generateActivityInfoUnchecked(a, flags, applicationInfo);
    }

    /**
     * This bypasses critical checks that are necessary for usage with data passed outside of
     * system server.
     *
     * Prefer {@link #generateActivityInfo(ParsingPackageRead, ParsedActivity, int,
     * PackageUserState, ApplicationInfo, int)}.
     */
    @NonNull
    public static ActivityInfo generateActivityInfoUnchecked(@NonNull ParsedActivity a,
            @PackageManager.ComponentInfoFlags int flags,
            @NonNull ApplicationInfo applicationInfo) {
        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo();
        assignSharedFieldsForComponentInfo(ai, a);
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
        Float maxAspectRatio = a.getMaxAspectRatio();
        ai.setMaxAspectRatio(maxAspectRatio != null ? maxAspectRatio : 0f);
        Float minAspectRatio = a.getMinAspectRatio();
        ai.setMinAspectRatio(minAspectRatio != null ? minAspectRatio : 0f);
        ai.supportsSizeChanges = a.getSupportsSizeChanges();
        ai.requestedVrComponent = a.getRequestedVrComponent();
        ai.rotationAnimation = a.getRotationAnimation();
        ai.colorMode = a.getColorMode();
        ai.windowLayout = a.getWindowLayout();
        ai.attributionTags = a.getAttributionTags();
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = a.getMetaData();
        }
        ai.applicationInfo = applicationInfo;
        return ai;
    }

    @Nullable
    public static ActivityInfo generateActivityInfo(ParsingPackageRead pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId) {
        return generateActivityInfo(pkg, a, flags, state, null, userId);
    }

    @Nullable
    public static ServiceInfo generateServiceInfo(ParsingPackageRead pkg, ParsedService s,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state,
            @Nullable ApplicationInfo applicationInfo, int userId) {
        if (s == null) return null;
        if (!checkUseInstalled(pkg, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        if (applicationInfo == null) {
            return null;
        }

        return generateServiceInfoUnchecked(s, flags,  applicationInfo);
    }

    /**
     * This bypasses critical checks that are necessary for usage with data passed outside of
     * system server.
     *
     * Prefer {@link #generateServiceInfo(ParsingPackageRead, ParsedService, int, PackageUserState,
     * ApplicationInfo, int)}.
     */
    @NonNull
    public static ServiceInfo generateServiceInfoUnchecked(@NonNull ParsedService s,
            @PackageManager.ComponentInfoFlags int flags,
            @NonNull ApplicationInfo applicationInfo) {
        // Make shallow copies so we can store the metadata safely
        ServiceInfo si = new ServiceInfo();
        assignSharedFieldsForComponentInfo(si, s);
        si.exported = s.isExported();
        si.flags = s.getFlags();
        si.permission = s.getPermission();
        si.processName = s.getProcessName();
        si.mForegroundServiceType = s.getForegroundServiceType();
        si.applicationInfo = applicationInfo;
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            si.metaData = s.getMetaData();
        }
        return si;
    }

    @Nullable
    public static ServiceInfo generateServiceInfo(ParsingPackageRead pkg, ParsedService s,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId) {
        return generateServiceInfo(pkg, s, flags, state, null, userId);
    }

    @Nullable
    public static ProviderInfo generateProviderInfo(ParsingPackageRead pkg, ParsedProvider p,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state,
            @Nullable ApplicationInfo applicationInfo, int userId) {
        if (p == null) return null;
        if (!checkUseInstalled(pkg, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        if (applicationInfo == null) {
            return null;
        }

        return generateProviderInfoUnchecked(p, flags, applicationInfo);
    }

    /**
     * This bypasses critical checks that are necessary for usage with data passed outside of
     * system server.
     *
     * Prefer {@link #generateProviderInfo(ParsingPackageRead, ParsedProvider, int,
     * PackageUserState, ApplicationInfo, int)}.
     */
    @NonNull
    public static ProviderInfo generateProviderInfoUnchecked(@NonNull ParsedProvider p,
            @PackageManager.ComponentInfoFlags int flags,
            @NonNull ApplicationInfo applicationInfo) {
        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo();
        assignSharedFieldsForComponentInfo(pi, p);
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
        pi.uriPermissionPatterns = p.getUriPermissionPatterns();
        pi.pathPermissions = p.getPathPermissions();
        if ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            pi.metaData = p.getMetaData();
        }
        pi.applicationInfo = applicationInfo;
        return pi;
    }

    @Nullable
    public static ProviderInfo generateProviderInfo(ParsingPackageRead pkg, ParsedProvider p,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId) {
        return generateProviderInfo(pkg, p, flags, state, null, userId);
    }

    /**
     * @param assignUserFields see {@link #generateApplicationInfoUnchecked(ParsingPackageRead, int,
     *                         PackageUserState, int, boolean)}
     */
    @Nullable
    public static InstrumentationInfo generateInstrumentationInfo(ParsedInstrumentation i,
            ParsingPackageRead pkg, @PackageManager.ComponentInfoFlags int flags, int userId,
            boolean assignUserFields) {
        if (i == null) return null;

        InstrumentationInfo ii = new InstrumentationInfo();
        assignSharedFieldsForPackageItemInfo(ii, i);
        ii.targetPackage = i.getTargetPackage();
        ii.targetProcesses = i.getTargetProcesses();
        ii.handleProfiling = i.isHandleProfiling();
        ii.functionalTest = i.isFunctionalTest();

        ii.sourceDir = pkg.getBaseApkPath();
        ii.publicSourceDir = pkg.getBaseApkPath();
        ii.splitNames = pkg.getSplitNames();
        ii.splitSourceDirs = pkg.getSplitCodePaths();
        ii.splitPublicSourceDirs = pkg.getSplitCodePaths();
        ii.splitDependencies = pkg.getSplitDependencies();

        if (assignUserFields) {
            assignUserFields(pkg, ii, userId);
        }

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return ii;
        }
        ii.metaData = i.getMetaData();
        return ii;
    }

    @Nullable
    public static PermissionInfo generatePermissionInfo(ParsedPermission p,
            @PackageManager.ComponentInfoFlags int flags) {
        if (p == null) return null;

        PermissionInfo pi = new PermissionInfo(p.getBackgroundPermission());

        assignSharedFieldsForPackageItemInfo(pi, p);

        pi.group = p.getGroup();
        pi.requestRes = p.getRequestRes();
        pi.protectionLevel = p.getProtectionLevel();
        pi.descriptionRes = p.getDescriptionRes();
        pi.flags = p.getFlags();
        pi.knownCerts = p.getKnownCerts();

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return pi;
        }
        pi.metaData = p.getMetaData();
        return pi;
    }

    @Nullable
    public static PermissionGroupInfo generatePermissionGroupInfo(ParsedPermissionGroup pg,
            @PackageManager.ComponentInfoFlags int flags) {
        if (pg == null) return null;

        PermissionGroupInfo pgi = new PermissionGroupInfo(
                pg.getRequestDetailResourceId(),
                pg.getBackgroundRequestResourceId(),
                pg.getBackgroundRequestDetailResourceId()
        );

        assignSharedFieldsForPackageItemInfo(pgi, pg);
        pgi.descriptionRes = pg.getDescriptionRes();
        pgi.priority = pg.getPriority();
        pgi.requestRes = pg.getRequestRes();
        pgi.flags = pg.getFlags();

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return pgi;
        }
        pgi.metaData = pg.getMetaData();
        return pgi;
    }

    @Nullable
    public static Attribution generateAttribution(ParsedAttribution pa) {
        if (pa == null) return null;
        return new Attribution(pa.tag, pa.label);
    }

    private static void assignSharedFieldsForComponentInfo(@NonNull ComponentInfo componentInfo,
            @NonNull ParsedMainComponent mainComponent) {
        assignSharedFieldsForPackageItemInfo(componentInfo, mainComponent);
        componentInfo.descriptionRes = mainComponent.getDescriptionRes();
        componentInfo.directBootAware = mainComponent.isDirectBootAware();
        componentInfo.enabled = mainComponent.isEnabled();
        componentInfo.splitName = mainComponent.getSplitName();
        componentInfo.attributionTags = mainComponent.getAttributionTags();
    }

    private static void assignSharedFieldsForPackageItemInfo(
            @NonNull PackageItemInfo packageItemInfo, @NonNull ParsedComponent component) {
        packageItemInfo.nonLocalizedLabel = ComponentParseUtils.getNonLocalizedLabel(component);
        packageItemInfo.icon = ComponentParseUtils.getIcon(component);

        packageItemInfo.banner = component.getBanner();
        packageItemInfo.labelRes = component.getLabelRes();
        packageItemInfo.logo = component.getLogo();
        packageItemInfo.name = component.getName();
        packageItemInfo.packageName = component.getPackageName();
    }

    @CheckResult
    private static int flag(boolean hasFlag, int flag) {
        if (hasFlag) {
            return flag;
        } else {
            return 0;
        }
    }

    /** @see ApplicationInfo#flags */
    public static int appInfoFlags(ParsingPackageRead pkg) {
        // @formatter:off
        return flag(pkg.isExternalStorage(), ApplicationInfo.FLAG_EXTERNAL_STORAGE)
                | flag(pkg.isBaseHardwareAccelerated(), ApplicationInfo.FLAG_HARDWARE_ACCELERATED)
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
                | flag(pkg.isAnyDensity(), ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES);
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlags */
    public static int appInfoPrivateFlags(ParsingPackageRead pkg) {
        // @formatter:off
        int privateFlags = flag(pkg.isStaticSharedLibrary(), ApplicationInfo.PRIVATE_FLAG_STATIC_SHARED_LIBRARY)
                | flag(pkg.isOverlay(), ApplicationInfo.PRIVATE_FLAG_IS_RESOURCE_OVERLAY)
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
                | flag(pkg.isAllowNativeHeapPointerTagging(), ApplicationInfo.PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING);
        // @formatter:on

        Boolean resizeableActivity = pkg.getResizeableActivity();
        if (resizeableActivity != null) {
            if (resizeableActivity) {
                privateFlags |= ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE;
            } else {
                privateFlags |= ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE;
            }
        }

        return privateFlags;
    }

    /** @see ApplicationInfo#privateFlagsExt */
    public static int appInfoPrivateFlagsExt(ParsingPackageRead pkg) {
        // @formatter:off
        int privateFlagsExt =
                flag(pkg.isProfileable(), ApplicationInfo.PRIVATE_FLAG_EXT_PROFILEABLE)
                | flag(pkg.hasRequestForegroundServiceExemption(),
                        ApplicationInfo.PRIVATE_FLAG_EXT_REQUEST_FOREGROUND_SERVICE_EXEMPTION)
                | flag(pkg.areAttributionsUserVisible(),
                        ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE);
        // @formatter:on
        return privateFlagsExt;
    }

    private static boolean checkUseInstalled(ParsingPackageRead pkg, PackageUserState state,
            @PackageManager.PackageInfoFlags int flags) {
        // If available for the target user
        return state.isAvailable(flags);
    }

    @NonNull
    public static File getDataDir(ParsingPackageRead pkg, int userId) {
        if ("android".equals(pkg.getPackageName())) {
            return Environment.getDataSystemDirectory();
        }

        if (pkg.isDefaultToDeviceProtectedStorage()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            return getDeviceProtectedDataDir(pkg, userId);
        } else {
            return getCredentialProtectedDataDir(pkg, userId);
        }
    }

    @NonNull
    public static File getDeviceProtectedDataDir(ParsingPackageRead pkg, int userId) {
        return Environment.getDataUserDePackageDirectory(pkg.getVolumeUuid(), userId,
                pkg.getPackageName());
    }

    @NonNull
    public static File getCredentialProtectedDataDir(ParsingPackageRead pkg, int userId) {
        return Environment.getDataUserCePackageDirectory(pkg.getVolumeUuid(), userId,
                pkg.getPackageName());
    }

    private static void assignUserFields(ParsingPackageRead pkg, ApplicationInfo info, int userId) {
        // This behavior is undefined for no-state ApplicationInfos when called by a public API,
        // since the uid is never assigned by the system. It will always effectively be appId 0.
        info.uid = UserHandle.getUid(userId, UserHandle.getAppId(info.uid));

        String pkgName = pkg.getPackageName();
        if ("android".equals(pkgName)) {
            info.dataDir = SYSTEM_DATA_PATH;
            return;
        }

        // For performance reasons, all these paths are built as strings
        String baseDataDirPrefix =
                Environment.getDataDirectoryPath(pkg.getVolumeUuid()) + File.separator;
        String userIdPkgSuffix = File.separator + userId + File.separator + pkgName;
        info.credentialProtectedDataDir = baseDataDirPrefix + Environment.DIR_USER_CE
                + userIdPkgSuffix;
        info.deviceProtectedDataDir = baseDataDirPrefix + Environment.DIR_USER_DE + userIdPkgSuffix;

        if (pkg.isDefaultToDeviceProtectedStorage()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            info.dataDir = info.deviceProtectedDataDir;
        } else {
            info.dataDir = info.credentialProtectedDataDir;
        }
    }

    private static void assignUserFields(ParsingPackageRead pkg, InstrumentationInfo info,
            int userId) {
        String pkgName = pkg.getPackageName();
        if ("android".equals(pkgName)) {
            info.dataDir = SYSTEM_DATA_PATH;
            return;
        }

        // For performance reasons, all these paths are built as strings
        String baseDataDirPrefix =
                Environment.getDataDirectoryPath(pkg.getVolumeUuid()) + File.separator;
        String userIdPkgSuffix = File.separator + userId + File.separator + pkgName;
        info.credentialProtectedDataDir = baseDataDirPrefix + Environment.DIR_USER_CE
                + userIdPkgSuffix;
        info.deviceProtectedDataDir = baseDataDirPrefix + Environment.DIR_USER_DE + userIdPkgSuffix;

        if (pkg.isDefaultToDeviceProtectedStorage()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            info.dataDir = info.deviceProtectedDataDir;
        } else {
            info.dataDir = info.credentialProtectedDataDir;
        }
    }
}
