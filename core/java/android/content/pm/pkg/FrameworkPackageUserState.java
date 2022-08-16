/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.content.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.overlay.OverlayPaths;

import java.util.Map;
import java.util.Set;

/**
 * A framework copy of the services PackageUserState, which acts as compatibility layer for existing
 * usages of PackageUserState in the framework. One day this can hopefully be removed.
 *
 * See the services variant for method documentation.
 *
 * @hide
 * @deprecated Unused by framework.
 */
@Deprecated
public interface FrameworkPackageUserState {

    FrameworkPackageUserState DEFAULT = new FrameworkPackageUserStateDefault();

    @Nullable
    OverlayPaths getAllOverlayPaths();

    long getCeDataInode();

    @NonNull
    Set<String> getDisabledComponents();

    @PackageManager.DistractionRestriction
    int getDistractionFlags();

    @NonNull
    Set<String> getEnabledComponents();

    @PackageManager.EnabledState
    int getEnabledState();

    @Nullable
    String getHarmfulAppWarning();

    @PackageManager.InstallReason
    int getInstallReason();

    @Nullable
    String getLastDisableAppCaller();

    @Nullable
    OverlayPaths getOverlayPaths();

    @NonNull
    Map<String, OverlayPaths> getSharedLibraryOverlayPaths();

    @PackageManager.UninstallReason
    int getUninstallReason();

    boolean isComponentEnabled(@NonNull String componentName);
    boolean isComponentDisabled(@NonNull String componentName);
    boolean isHidden();
    boolean isInstalled();
    boolean isInstantApp();
    boolean isNotLaunched();
    boolean isStopped();
    boolean isSuspended();
    boolean isVirtualPreload();

    @Nullable
    String getSplashScreenTheme();
}
