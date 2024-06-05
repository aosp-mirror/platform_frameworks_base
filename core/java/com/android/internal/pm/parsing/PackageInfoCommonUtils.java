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
package com.android.internal.pm.parsing;

import static com.android.internal.pm.pkg.SEInfoUtil.COMPLETE_STR;

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
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.os.Debug;
import android.os.PatternMatcher;
import android.os.UserHandle;
import android.util.DebugUtils;
import android.util.Slog;

import com.android.internal.pm.parsing.pkg.AndroidPackageHidden;
import com.android.internal.pm.parsing.pkg.AndroidPackageLegacyUtils;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ComponentParseUtils;
import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.internal.pm.pkg.component.ParsedAttribution;
import com.android.internal.pm.pkg.component.ParsedComponent;
import com.android.internal.pm.pkg.component.ParsedInstrumentation;
import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.component.ParsedUsesPermission;
import com.android.internal.pm.pkg.parsing.ParsingPackageHidden;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.pm.pkg.parsing.ParsingUtils;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.pkg.AndroidPackage;

import java.util.List;

/**
 * Method that use a {@link AndroidPackage} to generate a {@link PackageInfo} though
 * the given {@link PackageManager.PackageInfoFlags}
 * @hide
 **/
// TODO(b/317215254): refactor coped code from PackageInfoUtils
public class PackageInfoCommonUtils {

    private static final String TAG = ParsingUtils.TAG;
    private static final boolean DEBUG = false;

    /**
     * Generates a {@link PackageInfo} from the given {@link AndroidPackage}
     */
    @Nullable
    public static PackageInfo generate(@Nullable AndroidPackage pkg,
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        if (pkg == null) {
            return null;
        }
        ApplicationInfo applicationInfo = generateApplicationInfo(pkg, flags, userId);

        PackageInfo info = new PackageInfo();
        info.packageName = pkg.getPackageName();
        info.splitNames = pkg.getSplitNames();
        info.versionCode = ((ParsingPackageHidden) pkg).getVersionCode();
        info.versionCodeMajor = ((ParsingPackageHidden) pkg).getVersionCodeMajor();
        info.baseRevisionCode = pkg.getBaseRevisionCode();
        info.splitRevisionCodes = pkg.getSplitRevisionCodes();
        info.versionName = pkg.getVersionName();
        if (!pkg.isLeavingSharedUser()) {
            info.sharedUserId = pkg.getSharedUserId();
            info.sharedUserLabel = pkg.getSharedUserLabelResourceId();
        }
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
        info.isStub = pkg.isStub();
        info.coreApp = pkg.isCoreApp();
        info.isApex = pkg.isApex();

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
                    final var permission = pkg.getPermissions().get(i);
                    final var permissionInfo = generatePermissionInfo(permission, flags);
                    info.permissions[i] = permissionInfo;
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
        if ((flags & PackageManager.GET_ATTRIBUTIONS_LONG) != 0) {
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

        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            final int size = pkg.getActivities().size();
            if (size > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[size];
                for (int i = 0; i < size; i++) {
                    final ParsedActivity a = pkg.getActivities().get(i);
                    if (isMatch(pkg, a.isDirectBootAware(), flags)) {
                        if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(
                                a.getName())) {
                            continue;
                        }
                        res[num++] = generateActivityInfo(a, flags, applicationInfo);
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
                    if (isMatch(pkg, a.isDirectBootAware(), flags)) {
                        res[num++] = generateActivityInfo(a, flags, applicationInfo);
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
                    if (isMatch(pkg, s.isDirectBootAware(), flags)) {
                        res[num++] = generateServiceInfo(s, flags, applicationInfo);
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
                    final ParsedProvider pr = pkg.getProviders().get(i);
                    if (isMatch(pkg, pr.isDirectBootAware(), flags)) {
                        res[num++] = generateProviderInfo(pkg, pr, flags, applicationInfo, userId);
                    }
                }
                info.providers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            final int size = pkg.getInstrumentations().size();
            if (size > 0) {
                info.instrumentation = new InstrumentationInfo[size];
                for (int i = 0; i < size; i++) {
                    info.instrumentation[i] = generateInstrumentationInfo(
                            pkg.getInstrumentations().get(i), pkg, flags, userId);
                }
            }
        }

        return info;
    }

    private static void updateApplicationInfo(ApplicationInfo ai, long flags) {
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

        if (ai.category == ApplicationInfo.CATEGORY_UNDEFINED) {
            ai.category = FallbackCategoryProvider.getFallbackCategory(ai.packageName);
        }
        ai.seInfoUser = COMPLETE_STR;
    }

    @Nullable
    private static ApplicationInfo generateApplicationInfo(@NonNull AndroidPackage pkg,
            @PackageManager.ApplicationInfoFlagsBits long flags, @UserIdInt int userId) {

        // Make shallow copy so we can store the metadata/libraries safely
        ApplicationInfo info = ((AndroidPackageHidden) pkg).toAppInfoWithoutState();

        updateApplicationInfo(info, flags);

        initForUser(info, pkg, userId);

        info.primaryCpuAbi = AndroidPackageLegacyUtils.getRawPrimaryCpuAbi(pkg);
        info.secondaryCpuAbi = AndroidPackageLegacyUtils.getRawSecondaryCpuAbi(pkg);

        if ((flags & PackageManager.GET_META_DATA) != 0) {
            info.metaData = pkg.getMetaData();
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0) {
            List<String> usesLibraryFiles = pkg.getUsesLibraries();

            info.sharedLibraryFiles = usesLibraryFiles.isEmpty()
                    ? null : usesLibraryFiles.toArray(new String[0]);
        }

        return info;
    }

    @Nullable
    private static ActivityInfo generateActivityInfo(ParsedActivity a,
            @PackageManager.ComponentInfoFlagsBits long flags,
            @NonNull ApplicationInfo applicationInfo) {
        if (a == null) return null;

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
        assignFieldsComponentInfoParsedMainComponent(ai, a);
        return ai;
    }

    @Nullable
    private static ServiceInfo generateServiceInfo(ParsedService s,
            @PackageManager.ComponentInfoFlagsBits long flags,
            @NonNull ApplicationInfo applicationInfo) {
        if (s == null) return null;

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
        assignFieldsComponentInfoParsedMainComponent(si, s);
        return si;
    }

    @Nullable
    private static ProviderInfo generateProviderInfo(AndroidPackage pkg, ParsedProvider p,
            @PackageManager.ComponentInfoFlagsBits long flags,
            @NonNull ApplicationInfo applicationInfo, int userId) {
        if (p == null) return null;

        if (!pkg.getPackageName().equals(applicationInfo.packageName)) {
            Slog.wtf(TAG, "AppInfo's package name is different. Expected=" + pkg.getPackageName()
                    + " actual=" + applicationInfo.packageName);
            applicationInfo = generateApplicationInfo(pkg, flags, userId);
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
        assignFieldsComponentInfoParsedMainComponent(pi, p);
        return pi;
    }

    @Nullable
    private static InstrumentationInfo generateInstrumentationInfo(ParsedInstrumentation i,
            AndroidPackage pkg, @PackageManager.ComponentInfoFlagsBits long flags, int userId) {
        if (i == null) return null;

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

        info.primaryCpuAbi = AndroidPackageLegacyUtils.getRawPrimaryCpuAbi(pkg);
        info.secondaryCpuAbi = AndroidPackageLegacyUtils.getRawSecondaryCpuAbi(pkg);
        info.nativeLibraryDir = pkg.getNativeLibraryDir();
        info.secondaryNativeLibraryDir = pkg.getSecondaryNativeLibraryDir();

        assignFieldsPackageItemInfoParsedComponent(info, i);

        if ((flags & PackageManager.GET_META_DATA) == 0) {
            info.metaData = null;
        } else {
            var metaData = i.getMetaData();
            // Backwards compatibility, coerce to null if empty
            info.metaData = metaData.isEmpty() ? null : metaData;
        }

        return info;
    }

    @Nullable
    private static PermissionInfo generatePermissionInfo(ParsedPermission p,
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

    private static void assignFieldsComponentInfoParsedMainComponent(
            @NonNull ComponentInfo info, @NonNull ParsedMainComponent component) {
        assignFieldsPackageItemInfoParsedComponent(info, component);
        info.descriptionRes = component.getDescriptionRes();
        info.directBootAware = component.isDirectBootAware();
        info.enabled = component.isEnabled();
        info.splitName = component.getSplitName();
        info.attributionTags = component.getAttributionTags();
        info.nonLocalizedLabel = component.getNonLocalizedLabel();
        info.icon = component.getIcon();
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

    private static void initForUser(ApplicationInfo output, AndroidPackage input,
            @UserIdInt int userId) {
        PackageImpl pkg = ((PackageImpl) input);
        String packageName = input.getPackageName();
        output.uid = UserHandle.getUid(userId, UserHandle.getAppId(input.getUid()));

        // For performance reasons, all these paths are built as strings
        final String credentialDir = pkg.getBaseAppDataCredentialProtectedDirForSystemUser();
        final String deviceDir = pkg.getBaseAppDataDeviceProtectedDirForSystemUser();
        if (credentialDir !=  null && deviceDir != null) {
            if (userId == UserHandle.USER_SYSTEM) {
                output.credentialProtectedDataDir = credentialDir + packageName;
                output.deviceProtectedDataDir = deviceDir + packageName;
            } else {
                // Convert /data/user/0/ -> /data/user/1/com.example.app
                String userIdString = String.valueOf(userId);
                int credentialLength = credentialDir.length();
                output.credentialProtectedDataDir = new StringBuilder(credentialDir)
                        .replace(credentialLength - 2, credentialLength - 1, userIdString)
                        .append(packageName)
                        .toString();
                int deviceLength = deviceDir.length();
                output.deviceProtectedDataDir = new StringBuilder(deviceDir)
                        .replace(deviceLength - 2, deviceLength - 1, userIdString)
                        .append(packageName)
                        .toString();
            }
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

        // For performance reasons, all these paths are built as strings
        final String credentialDir = pkg.getBaseAppDataCredentialProtectedDirForSystemUser();
        final String deviceDir = pkg.getBaseAppDataDeviceProtectedDirForSystemUser();
        if (credentialDir !=  null && deviceDir != null) {
            if (userId == UserHandle.USER_SYSTEM) {
                output.credentialProtectedDataDir = credentialDir + packageName;
                output.deviceProtectedDataDir = deviceDir + packageName;
            } else {
                // Convert /data/user/0/ -> /data/user/1/com.example.app
                String userIdString = String.valueOf(userId);
                int credentialLength = credentialDir.length();
                output.credentialProtectedDataDir = new StringBuilder(credentialDir)
                        .replace(credentialLength - 2, credentialLength - 1, userIdString)
                        .append(packageName)
                        .toString();
                int deviceLength = deviceDir.length();
                output.deviceProtectedDataDir = new StringBuilder(deviceDir)
                        .replace(deviceLength - 2, deviceLength - 1, userIdString)
                        .append(packageName)
                        .toString();
            }
        }

        if (input.isDefaultToDeviceProtectedStorage()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            output.dataDir = output.deviceProtectedDataDir;
        } else {
            output.dataDir = output.credentialProtectedDataDir;
        }
    }

    /**
     * Test if the given component is considered system, enabled and a match for the given
     * flags.
     *
     * <p>
     * Expects at least one of {@link PackageManager#MATCH_DIRECT_BOOT_AWARE} and {@link
     * PackageManager#MATCH_DIRECT_BOOT_UNAWARE} are specified in {@code flags}.
     * </p>
     */
    private static boolean isMatch(AndroidPackage pkg,
            boolean isComponentDirectBootAware, long flags) {
        final boolean isSystem = ((AndroidPackageHidden) pkg).isSystem();
        if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
            if (!isSystem) {
                return reportIfDebug(false, flags);
            }
        }

        final boolean matchesUnaware = ((flags & PackageManager.MATCH_DIRECT_BOOT_UNAWARE) != 0)
                && !isComponentDirectBootAware;
        final boolean matchesAware = ((flags & PackageManager.MATCH_DIRECT_BOOT_AWARE) != 0)
                && isComponentDirectBootAware;
        return reportIfDebug(matchesUnaware || matchesAware, flags);
    }

    private static boolean reportIfDebug(boolean result, long flags) {
        if (DEBUG && !result) {
            Slog.i(TAG, "No match!; flags: "
                    + DebugUtils.flagsToString(PackageManager.class, "MATCH_", flags) + " "
                    + Debug.getCaller());
        }
        return result;
    }
}
