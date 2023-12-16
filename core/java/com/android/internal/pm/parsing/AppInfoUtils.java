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

import android.annotation.CheckResult;
import android.content.pm.ApplicationInfo;

import com.android.internal.pm.parsing.pkg.AndroidPackageLegacyUtils;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;

public class AppInfoUtils {

    /**
     * @see ApplicationInfo#flags
     */
    public static int appInfoFlags(AndroidPackage pkg) {
        // @formatter:off
        int pkgWithoutStateFlags = flag(pkg.isExternalStorage(), ApplicationInfo.FLAG_EXTERNAL_STORAGE)
                | flag(pkg.isHardwareAccelerated(), ApplicationInfo.FLAG_HARDWARE_ACCELERATED)
                | flag(pkg.isBackupAllowed(), ApplicationInfo.FLAG_ALLOW_BACKUP)
                | flag(pkg.isKillAfterRestoreAllowed(), ApplicationInfo.FLAG_KILL_AFTER_RESTORE)
                | flag(pkg.isRestoreAnyVersion(), ApplicationInfo.FLAG_RESTORE_ANY_VERSION)
                | flag(pkg.isFullBackupOnly(), ApplicationInfo.FLAG_FULL_BACKUP_ONLY)
                | flag(pkg.isPersistent(), ApplicationInfo.FLAG_PERSISTENT)
                | flag(pkg.isDebuggable(), ApplicationInfo.FLAG_DEBUGGABLE)
                | flag(pkg.isVmSafeMode(), ApplicationInfo.FLAG_VM_SAFE_MODE)
                | flag(pkg.isDeclaredHavingCode(), ApplicationInfo.FLAG_HAS_CODE)
                | flag(pkg.isTaskReparentingAllowed(), ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING)
                | flag(pkg.isClearUserDataAllowed(), ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA)
                | flag(pkg.isLargeHeap(), ApplicationInfo.FLAG_LARGE_HEAP)
                | flag(pkg.isCleartextTrafficAllowed(), ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
                | flag(pkg.isRtlSupported(), ApplicationInfo.FLAG_SUPPORTS_RTL)
                | flag(pkg.isTestOnly(), ApplicationInfo.FLAG_TEST_ONLY)
                | flag(pkg.isMultiArch(), ApplicationInfo.FLAG_MULTIARCH)
                | flag(pkg.isExtractNativeLibrariesRequested(), ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS)
                | flag(pkg.isGame(), ApplicationInfo.FLAG_IS_GAME)
                | flag(pkg.isSmallScreensSupported(), ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS)
                | flag(pkg.isNormalScreensSupported(), ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS)
                | flag(pkg.isLargeScreensSupported(), ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS)
                | flag(pkg.isExtraLargeScreensSupported(), ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS)
                | flag(pkg.isResizeable(), ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS)
                | flag(pkg.isAnyDensity(), ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES)
                | flag(AndroidPackageLegacyUtils.isSystem(pkg), ApplicationInfo.FLAG_SYSTEM)
                | flag(pkg.isFactoryTest(), ApplicationInfo.FLAG_FACTORY_TEST);

        return pkgWithoutStateFlags;
        // @formatter:on
    }

    /** @see ApplicationInfo#privateFlags */
    public static int appInfoPrivateFlags(AndroidPackage pkg) {
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
                | flag(pkg.isClearUserDataOnFailedRestoreAllowed(), ApplicationInfo.PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE)
                | flag(pkg.isAllowAudioPlaybackCapture(), ApplicationInfo.PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE)
                | flag(pkg.isRequestLegacyExternalStorage(), ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE)
                | flag(pkg.isNonSdkApiRequested(), ApplicationInfo.PRIVATE_FLAG_USES_NON_SDK_API)
                | flag(pkg.isUserDataFragile(), ApplicationInfo.PRIVATE_FLAG_HAS_FRAGILE_USER_DATA)
                | flag(pkg.isSaveStateDisallowed(), ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE)
                | flag(pkg.isResizeableActivityViaSdkVersion(), ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION)
                | flag(pkg.isAllowNativeHeapPointerTagging(), ApplicationInfo.PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING)
                | flag(AndroidPackageLegacyUtils.isSystemExt(pkg), ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT)
                | flag(AndroidPackageLegacyUtils.isPrivileged(pkg), ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                | flag(AndroidPackageLegacyUtils.isOem(pkg), ApplicationInfo.PRIVATE_FLAG_OEM)
                | flag(AndroidPackageLegacyUtils.isVendor(pkg), ApplicationInfo.PRIVATE_FLAG_VENDOR)
                | flag(AndroidPackageLegacyUtils.isProduct(pkg), ApplicationInfo.PRIVATE_FLAG_PRODUCT)
                | flag(AndroidPackageLegacyUtils.isOdm(pkg), ApplicationInfo.PRIVATE_FLAG_ODM)
                | flag(pkg.isSignedWithPlatformKey(), ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY);

        Boolean resizeableActivity = pkg.getResizeableActivity();
        if (resizeableActivity != null) {
            if (resizeableActivity) {
                pkgWithoutStateFlags |= ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE;
            } else {
                pkgWithoutStateFlags |= ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE;
            }
        }

        return pkgWithoutStateFlags;
        // @formatter:on
    }


    /** @see ApplicationInfo#privateFlagsExt */
    public static int appInfoPrivateFlagsExt(AndroidPackage pkg,
            boolean isAllowlistedForHiddenApis) {
        // @formatter:off
        int pkgWithoutStateFlags = flag(pkg.isProfileable(), ApplicationInfo.PRIVATE_FLAG_EXT_PROFILEABLE)
                | flag(pkg.hasRequestForegroundServiceExemption(), ApplicationInfo.PRIVATE_FLAG_EXT_REQUEST_FOREGROUND_SERVICE_EXEMPTION)
                | flag(pkg.isAttributionsUserVisible(), ApplicationInfo.PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE)
                | flag(pkg.isOnBackInvokedCallbackEnabled(), ApplicationInfo.PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK)
                | flag(isAllowlistedForHiddenApis, ApplicationInfo.PRIVATE_FLAG_EXT_ALLOWLISTED_FOR_HIDDEN_APIS);
        return pkgWithoutStateFlags;
        // @formatter:on
    }

    @CheckResult
    private static int flag(boolean hasFlag, int flag) {
        return hasFlag ? flag : 0;
    }
}
