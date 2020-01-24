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
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedComponent;
import android.content.pm.parsing.ComponentParseUtils.ParsedInstrumentation;
import android.content.pm.parsing.ComponentParseUtils.ParsedMainComponent;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermission;
import android.content.pm.parsing.ComponentParseUtils.ParsedPermissionGroup;
import android.content.pm.parsing.ComponentParseUtils.ParsedProcess;
import android.content.pm.parsing.ComponentParseUtils.ParsedProvider;
import android.content.pm.parsing.ComponentParseUtils.ParsedService;
import android.content.pm.parsing.PackageInfoWithoutStateUtils;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;

import libcore.util.EmptyArray;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** @hide **/
public class PackageInfoUtils {

    @Nullable
    public static PackageInfo generate(AndroidPackage pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId) {
        PackageSetting pkgSetting = null;
        return generateWithComponents(pkg, gids, flags, firstInstallTime, lastUpdateTime,
                grantedPermissions, state, userId, null, pkgSetting);
    }

    @Nullable
    public static PackageInfo generate(AndroidPackage pkg, ApexInfo apexInfo, int flags,
            PackageSetting pkgSetting) {
        return generateWithComponents(pkg, EmptyArray.INT, flags, 0, 0, Collections.emptySet(),
                new PackageUserState(), UserHandle.getCallingUserId(), apexInfo, pkgSetting);
    }

    private static PackageInfo generateWithComponents(AndroidPackage pkg, int[] gids,
            @PackageManager.PackageInfoFlags int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId,
            @Nullable ApexInfo apexInfo, @Nullable PackageSetting pkgSetting) {
        ApplicationInfo applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        if (applicationInfo == null) {
            return null;
        }

        PackageInfo info = PackageInfoWithoutStateUtils.generateWithoutComponents(pkg, gids, flags,
                firstInstallTime, lastUpdateTime, grantedPermissions, state, userId, apexInfo,
                applicationInfo);
        if (info == null) {
            return null;
        }

        info.isStub = pkg.isStub();
        info.coreApp = pkg.isCoreApp();

        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            final int N = pkg.getActivities().size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final ParsedActivity a = pkg.getActivities().get(i);
                    if (state.isMatch(pkg.isSystem(), pkg.isEnabled(), a, flags)) {
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
                    if (state.isMatch(pkg.isSystem(), pkg.isEnabled(), a, flags)) {
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
                    if (state.isMatch(pkg.isSystem(), pkg.isEnabled(), s, flags)) {
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
                    if (state.isMatch(pkg.isSystem(), pkg.isEnabled(), pr, flags)) {
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
                            pkg.getInstrumentations().get(i), pkg, flags, userId);
                }
            }
        }

        return info;
    }

    @Nullable
    public static ApplicationInfo generateApplicationInfo(AndroidPackage pkg,
            @PackageManager.ApplicationInfoFlags int flags, PackageUserState state, int userId) {
        PackageSetting pkgSetting = null;
        // TODO(b/135203078): Consider cases where we don't have a PkgSetting
        if (pkg == null) {
            return null;
        }

        if (!checkUseInstalledOrHidden(pkg, pkgSetting, state, flags)
                || !AndroidPackageUtils.isMatchForSystemOnly(pkg, flags)) {
            return null;
        }

        ApplicationInfo info = PackageInfoWithoutStateUtils.generateApplicationInfo(pkg, flags,
                state, userId);
        if (info == null) {
            return null;
        }

        info.flags |= appInfoFlags(pkg, pkgSetting);
        info.privateFlags |= appInfoPrivateFlags(pkg, pkgSetting);
        return info;
    }

    @Nullable
    public static ActivityInfo generateActivityInfo(AndroidPackage pkg, ParsedActivity a,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId) {
        PackageSetting pkgSetting = null;
        return generateActivityInfo(pkg, a, flags, state, null, userId, pkgSetting);
    }

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
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        ActivityInfo info = PackageInfoWithoutStateUtils.generateActivityInfo(pkg, a, flags, state,
                applicationInfo, userId);
        if (info == null) {
            return null;
        }

        assignSharedFieldsForComponentInfo(info, a, pkgSetting);
        return info;
    }

    @Nullable
    public static ServiceInfo generateServiceInfo(AndroidPackage pkg, ParsedService s,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId) {
        PackageSetting pkgSetting = null;
        return generateServiceInfo(pkg, s, flags, state, null, userId, pkgSetting);
    }

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
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        ServiceInfo info = PackageInfoWithoutStateUtils.generateServiceInfo(pkg, s, flags, state,
                applicationInfo, userId);
        if (info == null) {
            return null;
        }

        assignSharedFieldsForComponentInfo(info, s, pkgSetting);
        return info;
    }

    @Nullable
    public static ProviderInfo generateProviderInfo(AndroidPackage pkg, ParsedProvider p,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state, int userId) {
        PackageSetting pkgSetting = null;
        return generateProviderInfo(pkg, p, flags, state, null, userId, pkgSetting);
    }

    @Nullable
    private static ProviderInfo generateProviderInfo(AndroidPackage pkg, ParsedProvider p,
            @PackageManager.ComponentInfoFlags int flags, PackageUserState state,
            @Nullable ApplicationInfo applicationInfo, int userId,
            @Nullable PackageSetting pkgSetting) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(pkg, pkgSetting, state, flags)) {
            return null;
        }
        if (applicationInfo == null) {
            applicationInfo = generateApplicationInfo(pkg, flags, state, userId);
        }
        ProviderInfo info = PackageInfoWithoutStateUtils.generateProviderInfo(pkg, p, flags, state,
                applicationInfo, userId);
        if (info == null) {
            return null;
        }

        assignSharedFieldsForComponentInfo(info, p, pkgSetting);
        return info;
    }

    @Nullable
    public static InstrumentationInfo generateInstrumentationInfo(ParsedInstrumentation i,
            AndroidPackage pkg, @PackageManager.ComponentInfoFlags int flags, int userId) {
        PackageSetting pkgSetting = null;
        if (i == null) return null;

        InstrumentationInfo info =
                PackageInfoWithoutStateUtils.generateInstrumentationInfo(i, pkg, flags, userId);
        if (info == null) {
            return null;
        }

        // TODO(b/135203078): Add setting related state
        info.primaryCpuAbi = pkg.getPrimaryCpuAbi();
        info.secondaryCpuAbi = pkg.getSecondaryCpuAbi();
        info.nativeLibraryDir = pkg.getNativeLibraryDir();
        info.secondaryNativeLibraryDir = pkg.getSecondaryNativeLibraryDir();

        assignStateFieldsForPackageItemInfo(info, i, pkgSetting);

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
            retProcs.put(proc.name, new ProcessInfo(proc.name,
                    proc.deniedPermissions != null
                            ? new ArraySet<>(proc.deniedPermissions) : null));
        }
        return retProcs;
    }

    /**
     * Returns true if the package is installed and not hidden, or if the caller
     * explicitly wanted all uninstalled and hidden packages as well.
     */
    private static boolean checkUseInstalledOrHidden(AndroidPackage pkg,
            PackageSetting pkgSetting, PackageUserState state,
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
                || (pkg.isSystem()
                && ((flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0
                || (flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) != 0));
    }

    private static void assignSharedFieldsForComponentInfo(@NonNull ComponentInfo componentInfo,
            @NonNull ParsedMainComponent mainComponent, @Nullable PackageSetting pkgSetting) {
        assignStateFieldsForPackageItemInfo(componentInfo, mainComponent, pkgSetting);
        componentInfo.descriptionRes = mainComponent.descriptionRes;
        componentInfo.directBootAware = mainComponent.isDirectBootAware();
        componentInfo.enabled = mainComponent.isEnabled();
        componentInfo.splitName = mainComponent.getSplitName();
    }

    private static void assignStateFieldsForPackageItemInfo(
            @NonNull PackageItemInfo packageItemInfo, @NonNull ParsedComponent component,
            @Nullable PackageSetting pkgSetting) {
        // TODO(b/135203078): Add setting related state
    }

    @CheckResult
    private static int flag(boolean hasFlag, int flag) {
        return hasFlag ? flag : 0;
    }

    /** @see ApplicationInfo#flags */
    public static int appInfoFlags(AndroidPackage pkg, @Nullable PackageSetting pkgSetting) {
        // TODO(b/135203078): Add setting related state
        // @formatter:off
        return PackageInfoWithoutStateUtils.appInfoFlags(pkg)
                | flag(pkg.isSystem(), ApplicationInfo.FLAG_SYSTEM)
                | flag(pkg.isFactoryTest(), ApplicationInfo.FLAG_FACTORY_TEST)
                | flag(pkg.isUpdatedSystemApp(), ApplicationInfo.FLAG_UPDATED_SYSTEM_APP);
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlags */
    public static int appInfoPrivateFlags(AndroidPackage pkg, @Nullable PackageSetting pkgSetting) {
        // TODO(b/135203078): Add setting related state
        // @formatter:off
        return PackageInfoWithoutStateUtils.appInfoPrivateFlags(pkg)
                | flag(pkg.isSystemExt(), ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT)
                | flag(pkg.isPrivileged(), ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                | flag(pkg.isOem(), ApplicationInfo.PRIVATE_FLAG_OEM)
                | flag(pkg.isVendor(), ApplicationInfo.PRIVATE_FLAG_VENDOR)
                | flag(pkg.isProduct(), ApplicationInfo.PRIVATE_FLAG_PRODUCT)
                | flag(pkg.isOdm(), ApplicationInfo.PRIVATE_FLAG_ODM)
                | flag(pkg.isSignedWithPlatformKey(), ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY);
        // @formatter:on
    }
}
