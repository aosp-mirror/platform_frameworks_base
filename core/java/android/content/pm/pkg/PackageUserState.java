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

package android.content.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.overlay.OverlayPaths;
import android.os.UserHandle;

import java.util.Map;
import java.util.Set;

/**
 * The API surface for a {@link PackageUserStateImpl}. Methods are expected to return
 * immutable objects. This may mean copying data on each invocation until related classes are
 * refactored to be immutable.
 * @hide
 */
// TODO(b/173807334): Expose API
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface PackageUserState {

    PackageUserState DEFAULT = new PackageUserStateDefault();

    /**
     * Combination of {@link #getOverlayPaths()} and {@link #getSharedLibraryOverlayPaths()}
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
    Set<String> getDisabledComponents();

    @PackageManager.DistractionRestriction
    int getDistractionFlags();

    /**
     * Fully qualified class names of components explicitly enabled.
     */
    @NonNull
    Set<String> getEnabledComponents();

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

    @Nullable
    OverlayPaths getOverlayPaths();

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
}
