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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.PackageManager;
import android.content.pm.overlay.OverlayPaths;
import android.os.UserHandle;
import android.processor.immutability.Immutable;
import android.util.ArraySet;

import java.util.Map;


/**
 * State for an app for a specific user, such as installed/enabled.
 *
 * Querying for a non-existent user does not throw an exception, so it is the responsibility of
 * the caller to check for valid users if necessary. A default value assuming the app is installed
 * for the non-existent user will be returned.
 *
 * The parent of this class is {@link PackageState}, which handles non-user state and holds one or
 * many different {@link PackageUserState PackageUserStates}. This class is
 * accessed through {@link PackageState#getStateForUser(UserHandle)}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
@Immutable
public interface PackageUserState {

    /**
     * @return whether the package is marked as installed
     */
    boolean isInstalled();

    /**
     * In epoch milliseconds. The timestamp of the first install of the app of the particular user
     * on the device, surviving past app updates. Different users might have a different first
     * install time.
     * <p/>
     * This does not survive full removal of the app (i.e., uninstalls for all users).
     */
    @CurrentTimeMillisLong
    long getFirstInstallTimeMillis();

    // Methods below this comment are not yet exposed as API

    /**
     * @hide
     */
    @NonNull
    PackageUserState DEFAULT = PackageUserStateInternal.DEFAULT;

    /**
     * Combination of {@link #getOverlayPaths()} and {@link #getSharedLibraryOverlayPaths()}
     *
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    OverlayPaths getAllOverlayPaths();

    /**
     * Credential encrypted /data partition inode.
     *
     * @hide
     */
    long getCeDataInode();

    /**
     * Device encrypted /data partition inode.
     *
     * @hide
     */
    long getDeDataInode();

    /**
     * Fully qualified class names of components explicitly disabled.
     *
     * @hide
     */
    @NonNull
    ArraySet<String> getDisabledComponents();

    /**
     * @hide
     */
    @PackageManager.DistractionRestriction
    int getDistractionFlags();

    /**
     * Fully qualified class names of components explicitly enabled.
     *
     * @hide
     */
    @NonNull
    ArraySet<String> getEnabledComponents();

    /**
     * Retrieve the effective enabled state of the package itself.
     *
     * @hide
     */
    @PackageManager.EnabledState
    int getEnabledState();

    /**
     * @hide
     * @see PackageManager#setHarmfulAppWarning(String, CharSequence)
     */
    @Nullable
    String getHarmfulAppWarning();

    /**
     * @hide
     */
    @PackageManager.InstallReason
    int getInstallReason();

    /**
     * Tracks the last calling package to set a specific enabled state for the package.
     *
     * @hide
     */
    @Nullable
    String getLastDisableAppCaller();

    /**
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    OverlayPaths getOverlayPaths();

    /**
     * @hide
     */
    @Immutable.Ignore
    @NonNull
    Map<String, OverlayPaths> getSharedLibraryOverlayPaths();

    /**
     * @hide
     */
    @PackageManager.UninstallReason
    int getUninstallReason();

    /**
     * @return whether the given fully qualified class name is explicitly enabled
     * @hide
     */
    boolean isComponentEnabled(@NonNull String componentName);

    /**
     * @return {@link #isComponentEnabled(String)} but for explicitly disabled
     * @hide
     */
    boolean isComponentDisabled(@NonNull String componentName);

    /**
     * @hide
     * @see PackageManager#setApplicationHiddenSettingAsUser(String, boolean, UserHandle)
     */
    boolean isHidden();

    /**
     * @return whether the package is marked as an ephemeral app, which restricts permissions,
     * features, visibility
     * @hide
     */
    boolean isInstantApp();

    /**
     * @return whether the package has not been launched since being explicitly stopped
     * @hide
     */
    boolean isNotLaunched();

    /**
     * @return whether the package has been stopped, which can occur if it's force-stopped, data
     * cleared, or just been installed
     * @hide
     */
    boolean isStopped();

    /**
     * @return whether the package has been suspended, maybe by the device admin, disallowing its
     * launch
     * @hide
     */
    boolean isSuspended();

    /**
     * @return whether the package was installed as a virtual preload, which may be done as part
     * of device infrastructure auto installation outside of the initial device image
     * @hide
     */
    boolean isVirtualPreload();

    /**
     * @return whether the package is quarantined in order to minimize ad-spam and pop ups
     * when-not-in-use.
     * @hide
     */
    boolean isQuarantined();

    /**
     * The "package:type/entry" form of the theme resource ID previously set as the splash screen.
     *
     * @hide
     * @see android.window.SplashScreen#setSplashScreenTheme(int)
     * @see android.content.res.Resources#getResourceName(int)
     */
    @Nullable
    String getSplashScreenTheme();

    /**
     * @return the min aspect ratio setting of the package which by default is unset
     * unless it has been set by the user
     * @hide
     */
    @PackageManager.UserMinAspectRatio
    int getMinAspectRatio();

    /**
     * Information about the archived state of an app. Set only if an app is archived.
     *
     * @hide
     */
    @Immutable.Ignore
    @Nullable
    ArchiveState getArchiveState();

    /**
     * @return whether the data dir exists. True when the app is installed for the user, or when the
     * app is uninstalled for the user with {@link PackageManager#DELETE_KEEP_DATA}.
     *
     * @hide
     */
    boolean dataExists();

    /**
     * Timestamp of when the app is archived on the user.
     * @hide
     */
    long getArchiveTimeMillis();
}
