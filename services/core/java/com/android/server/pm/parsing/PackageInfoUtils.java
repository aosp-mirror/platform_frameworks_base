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
import android.apex.ApexInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageUserState;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.parsing.PackageInfoWithoutStateUtils;
import android.content.pm.parsing.ParsingUtils;
import android.content.pm.parsing.component.ComponentParseUtils;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedComponent;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.parsing.component.ParsedProcess;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.pkg.PackageStateUnserialized;

import libcore.util.EmptyArray;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Methods that use a {@link PackageSetting} use it to override information provided from the raw
 * package, or to provide information that would otherwise be missing. Null can be passed if none
 * of the state values should be applied.
 *
 * @hide
 **/
public class PackageInfoUtils {
    private static final String TAG = ParsingUtils.TAG;

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static PackageInfo generate(AndroidPackage pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId,
            @Nullable PackageSetting pkgSetting) {
        return generateWithComponents(pkg, gids, flags, firstInstallTime, lastUpdateTime,
                grantedPermissions, state, userId, null, pkgSetting);
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static PackageInfo generate(AndroidPackage pkg, ApexInfo apexInfo, int flags,
            @Nullable PackageSetting pkgSetting) {
        return generateWithComponents(pkg, EmptyArray.INT, flags, 0, 0, Collections.emptySet(),
                new PackageUserState(), UserHandle.getCallingUserId(), apexInfo, pkgSetting);
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    private static PackageInfo generateWithComponents(AndroidPackage pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId,
            @Nullable ApexInfo apexInfo, @Nullable PackageSetting pkgSetting) {
        ApplicationInfo applicationInfo = generateApplicationInfo(pkg, flags, state, userId,
                pkgSetting);
        if (applicationInfo == null) {
            return null;
        }

        PackageInfo info = PackageInfoWithoutStateUtils.generateWithoutComponentsUnchecked(pkg,
                gids, flags, firstInstallTime, lastUpdateTime, grantedPermissions, state, userId,
                apexInfo, applicationInfo);

        info.isStub = pkg.isStub();
        info.coreApp = pkg.isCoreApp();

        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            final int N = pkg.getActivities().size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final ParsedActivity a = pkg.getActivities().get(i);
                    if (ComponentParseUtils.isMatch(state, pkg.isSystem(), pkg.isEnabled(), a,
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
                    if (ComponentParseUtils.isMatch(state, pkg.isSystem(), pkg.isEnabled(), a,
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
                    if (ComponentParseUtils.isMatch(state, pkg.isSystem(), pkg.isEnabled(), s,
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
                    if (ComponentParseUtils.isMatch(state, pkg.isSystem(), pkg.isEnabled(), pr,
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
                            pkg.getInstrumentations().get(i), pkg, flags, userId, pkgSetting);
                }
            }
        }

        return info;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static ApplicationInfo generateApplicationInfo(AndroidPackage pkg,
            @PackageManager.ApplicationInfoFlags int flags, @NonNull PackageUserState state,
            int userId, @Nullable PackageSetting pkgSetting) {
        if (pkg == null) {
            return null;
        }

        if (!checkUseInstalledOrHidden(pkg, pkgSetting, state, flags)
                || !AndroidPackageUtils.isMatchForSystemOnly(pkg, flags)) {
            return null;
        }

        ApplicationInfo info = PackageInfoWithoutStateUtils.generateApplicationInfoUnchecked(pkg,
                flags, state, userId, false /* assignUserFields */);

        initForUser(info, pkg, userId);

        if (pkgSetting != null) {
            // TODO(b/135203078): Remove PackageParser1/toAppInfoWithoutState and clean all this up
            PackageStateUnserialized pkgState = pkgSetting.getPkgState();
            info.hiddenUntilInstalled = pkgState.isHiddenUntilInstalled();
            List<String> usesLibraryFiles = pkgState.getUsesLibraryFiles();
            List<SharedLibraryInfo> usesLibraryInfos = pkgState.getUsesLibraryInfos();
            info.sharedLibraryFiles = usesLibraryFiles.isEmpty()
                    ? null : usesLibraryFiles.toArray(new String[0]);
            info.sharedLibraryInfos = usesLibraryInfos.isEmpty() ? null : usesLibraryInfos;
            if (info.category == ApplicationInfo.CATEGORY_UNDEFINED) {
                info.category = pkgSetting.getCategoryOverride();
            }
        }

        info.seInfo = AndroidPackageUtils.getSeInfo(pkg, pkgSetting);
        info.primaryCpuAbi = AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting);
        info.secondaryCpuAbi = AndroidPackageUtils.getSecondaryCpuAbi(pkg, pkgSetting);

        info.flags |= appInfoFlags(info.flags, pkgSetting);
        info.privateFlags |= appInfoPrivateFlags(info.privateFlags, pkgSetting);
        info.privateFlagsExt |= appInfoPrivateFlagsExt(info.privateFlagsExt, pkgSetting);

        return info;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static ActivityInfo generateActivityInfo(AndroidPackage pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId,
            @Nullable PackageSetting pkgSetting) {
        return generateActivityInfo(pkg, a, flags, state, null, userId, pkgSetting);
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    private static ActivityInfo generateActivityInfo(AndroidPackage pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state,
            @Nullable ApplicationInfo applicationInfo, int userId,
            @Nullable PackageSetting pkgSetting) {
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

        final ActivityInfo info = PackageInfoWithoutStateUtils.generateActivityInfoUnchecked(
                a, flags, applicationInfo);
        assignSharedFieldsForComponentInfo(info, a, pkgSetting, userId);
        return info;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static ServiceInfo generateServiceInfo(AndroidPackage pkg, ParsedService s,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId,
            @Nullable PackageSetting pkgSetting) {
        return generateServiceInfo(pkg, s, flags, state, null, userId, pkgSetting);
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    private static ServiceInfo generateServiceInfo(AndroidPackage pkg, ParsedService s,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state,
            @Nullable ApplicationInfo applicationInfo, int userId,
            @Nullable PackageSetting pkgSetting) {
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

        final ServiceInfo info = PackageInfoWithoutStateUtils.generateServiceInfoUnchecked(
                s, flags, applicationInfo);
        assignSharedFieldsForComponentInfo(info, s, pkgSetting, userId);
        return info;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static ProviderInfo generateProviderInfo(AndroidPackage pkg, ParsedProvider p,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state,
            @NonNull ApplicationInfo applicationInfo, int userId,
            @Nullable PackageSetting pkgSetting) {
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
        ProviderInfo info = PackageInfoWithoutStateUtils.generateProviderInfoUnchecked(p, flags,
                applicationInfo);
        assignSharedFieldsForComponentInfo(info, p, pkgSetting, userId);
        return info;
    }

    /**
     * @param pkgSetting See {@link PackageInfoUtils} for description of pkgSetting usage.
     */
    @Nullable
    public static InstrumentationInfo generateInstrumentationInfo(ParsedInstrumentation i,
            AndroidPackage pkg, @PackageManager.ComponentInfoFlags int flags, int userId,
            @Nullable PackageSetting pkgSetting) {
        if (i == null) return null;

        InstrumentationInfo info =
                PackageInfoWithoutStateUtils.generateInstrumentationInfo(i, pkg, flags, userId,
                        false /* assignUserFields */);

        initForUser(info, pkg, userId);

        if (info == null) {
            return null;
        }

        info.primaryCpuAbi = AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting);
        info.secondaryCpuAbi = AndroidPackageUtils.getSecondaryCpuAbi(pkg, pkgSetting);
        info.nativeLibraryDir = pkg.getNativeLibraryDir();
        info.secondaryNativeLibraryDir = pkg.getSecondaryNativeLibraryDir();

        assignStateFieldsForPackageItemInfo(info, i, pkgSetting, userId);

        return info;
    }

    // TODO(b/135203078): Determine if permission methods need to pass in a non-null PackageSetting
    //  os that checkUseInstalledOrHidden filter can apply
    @Nullable
    public static PermissionInfo generatePermissionInfo(ParsedPermission p,
            @PackageManager.ComponentInfoFlags int flags) {
        // TODO(b/135203078): Remove null checks and make all usages @NonNull
        if (p == null) return null;

        // For now, permissions don't have state-adjustable fields; return directly
        return PackageInfoWithoutStateUtils.generatePermissionInfo(p, flags);
    }

    @Nullable
    public static PermissionGroupInfo generatePermissionGroupInfo(ParsedPermissionGroup pg,
            @PackageManager.ComponentInfoFlags int flags) {
        if (pg == null) return null;

        // For now, permissions don't have state-adjustable fields; return directly
        return PackageInfoWithoutStateUtils.generatePermissionGroupInfo(pg, flags);
    }

    @Nullable
    public static ArrayMap<String, ProcessInfo> generateProcessInfo(
            Map<String, ParsedProcess> procs, @PackageManager.ComponentInfoFlags int flags) {
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
     * Returns true if the package is installed and not hidden, or if the caller
     * explicitly wanted all uninstalled and hidden packages as well.
     */
    public static boolean checkUseInstalledOrHidden(AndroidPackage pkg,
            PackageSetting pkgSetting, PackageUserState state,
            @PackageManager.PackageInfoFlags int flags) {
        // Returns false if the package is hidden system app until installed.
        if ((flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) == 0
                && !state.installed
                && pkgSetting != null
                && pkgSetting.getPkgState().isHiddenUntilInstalled()) {
            return false;
        }

        // If available for the target user, or trying to match uninstalled packages and it's
        // a system app.
        return state.isAvailable(flags)
                || (pkg.isSystem()
                && ((flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0
                || (flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) != 0));
    }

    private static void assignSharedFieldsForComponentInfo(@NonNull ComponentInfo componentInfo,
            @NonNull ParsedMainComponent mainComponent, @Nullable PackageSetting pkgSetting,
            int userId) {
        assignStateFieldsForPackageItemInfo(componentInfo, mainComponent, pkgSetting, userId);
        componentInfo.descriptionRes = mainComponent.getDescriptionRes();
        componentInfo.directBootAware = mainComponent.isDirectBootAware();
        componentInfo.enabled = mainComponent.isEnabled();
        componentInfo.splitName = mainComponent.getSplitName();
        componentInfo.attributionTags = mainComponent.getAttributionTags();
    }

    private static void assignStateFieldsForPackageItemInfo(
            @NonNull PackageItemInfo packageItemInfo, @NonNull ParsedComponent component,
            @Nullable PackageSetting pkgSetting, int userId) {
        Pair<CharSequence, Integer> labelAndIcon =
                ParsedComponentStateUtils.getNonLocalizedLabelAndIcon(component, pkgSetting,
                        userId);
        packageItemInfo.nonLocalizedLabel = labelAndIcon.first;
        packageItemInfo.icon = labelAndIcon.second;
    }

    @CheckResult
    private static int flag(boolean hasFlag, int flag) {
        return hasFlag ? flag : 0;
    }

    /** @see ApplicationInfo#flags */
    public static int appInfoFlags(AndroidPackage pkg, @Nullable PackageSetting pkgSetting) {
        // @formatter:off
        int pkgWithoutStateFlags = PackageInfoWithoutStateUtils.appInfoFlags(pkg)
                | flag(pkg.isSystem(), ApplicationInfo.FLAG_SYSTEM)
                | flag(pkg.isFactoryTest(), ApplicationInfo.FLAG_FACTORY_TEST);

        return appInfoFlags(pkgWithoutStateFlags, pkgSetting);
        // @formatter:on
    }

    /** @see ApplicationInfo#flags */
    public static int appInfoFlags(int pkgWithoutStateFlags, @NonNull PackageSetting pkgSetting) {
        // @formatter:off
        int flags = pkgWithoutStateFlags;
        if (pkgSetting != null) {
            flags |= flag(pkgSetting.getPkgState().isUpdatedSystemApp(), ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
        }
        return flags;
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlags */
    public static int appInfoPrivateFlags(AndroidPackage pkg, @Nullable PackageSetting pkgSetting) {
        // @formatter:off
        int pkgWithoutStateFlags = PackageInfoWithoutStateUtils.appInfoPrivateFlags(pkg)
                | flag(pkg.isSystemExt(), ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT)
                | flag(pkg.isPrivileged(), ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                | flag(pkg.isOem(), ApplicationInfo.PRIVATE_FLAG_OEM)
                | flag(pkg.isVendor(), ApplicationInfo.PRIVATE_FLAG_VENDOR)
                | flag(pkg.isProduct(), ApplicationInfo.PRIVATE_FLAG_PRODUCT)
                | flag(pkg.isOdm(), ApplicationInfo.PRIVATE_FLAG_ODM)
                | flag(pkg.isSignedWithPlatformKey(), ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY);
        return appInfoPrivateFlags(pkgWithoutStateFlags, pkgSetting);
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlags */
    public static int appInfoPrivateFlags(int pkgWithoutStateFlags, @Nullable PackageSetting pkgSetting) {
        // @formatter:off
        // TODO: Add state specific flags
        return pkgWithoutStateFlags;
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlagsExt */
    public static int appInfoPrivateFlagsExt(AndroidPackage pkg,
                                             @Nullable PackageSetting pkgSetting) {
        // @formatter:off
        int pkgWithoutStateFlags = PackageInfoWithoutStateUtils.appInfoPrivateFlagsExt(pkg);
        return appInfoPrivateFlagsExt(pkgWithoutStateFlags, pkgSetting);
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlagsExt */
    public static int appInfoPrivateFlagsExt(int pkgWithoutStateFlags,
                                             @Nullable PackageSetting pkgSetting) {
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
            output.dataDir = PackageInfoWithoutStateUtils.SYSTEM_DATA_PATH;
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
            output.dataDir = PackageInfoWithoutStateUtils.SYSTEM_DATA_PATH;
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
                @PackageManager.ApplicationInfoFlags int flags, PackageUserState state, int userId,
                @Nullable PackageSetting pkgSetting) {
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
