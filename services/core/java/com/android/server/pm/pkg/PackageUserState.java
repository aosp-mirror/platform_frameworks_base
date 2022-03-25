/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.overlay.OverlayPaths;
import android.os.UserHandle;
import android.util.ArraySet;

import java.util.Map;

/**
 * The API surface for {@link PackageUserStateInternal}, for use by in-process mainline consumers.
 *
 * The parent of this class is {@link PackageState}, which handles non-user state, exposing this
 * interface for per-user state.
 *
 * @hide
 */
// TODO(b/173807334): Expose API
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface PackageUserState {

    /** @hide */
    @NonNull
    PackageUserState DEFAULT = PackageUserStateInternal.DEFAULT;

    /**
     * Combination of {@link #getOverlayPaths()} and {@link #getSharedLibraryOverlayPaths()}
     * @hide
     */
    @Nullable
    OverlayPaths getAllOverlayPaths();

    /**
     * Credential encrypted /data partition inode.
     */
    long getCeDataInode();

    /**
     * Fully qualified class names of components explicitly disabled.
     */
    @NonNull
    ArraySet<String> getDisabledComponents();

    @PackageManager.DistractionRestriction
    int getDistractionFlags();

    /**
     * Fully qualified class names of components explicitly enabled.
     */
    @NonNull
    ArraySet<String> getEnabledComponents();

    /**
     * Retrieve the effective enabled state of the package itself.
     */
    @PackageManager.EnabledState
    int getEnabledState();

    /**
     * @see PackageManager#setHarmfulAppWarning(String, CharSequence)
     */
    @Nullable
    String getHarmfulAppWarning();

    @PackageManager.InstallReason
    int getInstallReason();

    /**
     * Tracks the last calling package to set a specific enabled state for the package.
     */
    @Nullable
    String getLastDisableAppCaller();

    /** @hide */
    @Nullable
    OverlayPaths getOverlayPaths();

    /** @hide */
    @NonNull
    Map<String, OverlayPaths> getSharedLibraryOverlayPaths();

    @PackageManager.UninstallReason
    int getUninstallReason();

    /**
     * @return whether the given fully qualified class name is explicitly enabled
     */
    boolean isComponentEnabled(@NonNull String componentName);

    /**
     * @return {@link #isComponentEnabled(String)} but for explicitly disabled
     */
    boolean isComponentDisabled(@NonNull String componentName);

    /**
     * @see PackageManager#setApplicationHiddenSettingAsUser(String, boolean, UserHandle)
     */
    boolean isHidden();

    /**
     * @return whether the package is marked as installed for all users
     */
    boolean isInstalled();

    /**
     * @return whether the package is marked as an ephemeral app, which restricts permissions,
     * features, visibility
     */
    boolean isInstantApp();

    /**
     * @return whether the package has not been launched since being explicitly stopped
     */
    boolean isNotLaunched();

    /**
     * @return whether the package has been stopped, which can occur if it's force-stopped, data
     * cleared, or just been installed
     */
    boolean isStopped();

    /**
     * @return whether the package has been suspended, maybe by the device admin, disallowing its
     * launch
     */
    boolean isSuspended();

    /**
     * @return whether the package was installed as a virtual preload, which may be done as part
     * of device infrastructure auto installation outside of the initial device image
     */
    boolean isVirtualPreload();

    /**
     * The "package:type/entry" form of the theme resource ID previously set as the splash screen.
     * @see android.window.SplashScreen#setSplashScreenTheme(int)
     * @see android.content.res.Resources#getResourceName(int)
     */
    @Nullable
    String getSplashScreenTheme();

    /**
     * In epoch milliseconds. The timestamp of the first install of the app of the particular user
     * on the device, surviving past app updates. Different users might have a different first
     * install time.
     *
     * This does not survive full removal of the app (i.e., uninstalls for all users).
     */
    long getFirstInstallTime();
}
