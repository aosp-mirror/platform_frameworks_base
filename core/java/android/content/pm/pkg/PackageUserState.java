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
import android.annotation.SystemApi;
import android.content.pm.PackageManager;
import android.content.pm.overlay.OverlayPaths;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The API surface for a {@link android.content.pm.PackageUserState}. Methods are expected to return
 * immutable objects. This may mean copying data on each invocation until related classes are
 * refactored to be immutable.
 * <p>
 * TODO: Replace implementation usage with the interface. Currently the name overlap is intentional.
 * <p>
 *
 * @hide
 */
// TODO(b/173807334): Expose API
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface PackageUserState {
    /**
     * Credential encrypted /data partition inode.
     */
    long getCeDataInode();

    @NonNull
    Set<String> getDisabledComponents();

    @PackageManager.DistractionRestriction
    int getDistractionFlags();

    @NonNull
    Set<String> getEnabledComponents();

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

    boolean isHidden();

    boolean isInstalled();

    boolean isInstantApp();

    boolean isNotLaunched();

    boolean isStopped();

    boolean isSuspended();

    boolean isVirtualPreload();
}
