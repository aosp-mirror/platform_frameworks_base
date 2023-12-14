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

package com.android.internal.pm.pkg.component;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Contains mutation methods so that code doesn't have to cast to the Impl. Meant to eventually
 * be removed once all post-parsing mutation is moved to parsing.
 *
 * @hide
 */
public class ComponentMutateUtils {

    public static void setMaxAspectRatio(@NonNull ParsedActivity activity, int resizeMode,
            float maxAspectRatio) {
        ((ParsedActivityImpl) activity).setMaxAspectRatio(resizeMode, maxAspectRatio);
    }

    public static void setMinAspectRatio(@NonNull ParsedActivity activity, int resizeMode,
            float minAspectRatio) {
        ((ParsedActivityImpl) activity).setMinAspectRatio(resizeMode, minAspectRatio);
    }

    public static void setSupportsSizeChanges(@NonNull ParsedActivity activity,
            boolean supportsSizeChanges) {
        ((ParsedActivityImpl) activity).setSupportsSizeChanges(supportsSizeChanges);
    }

    public static void setResizeMode(@NonNull ParsedActivity activity, int resizeMode) {
        ((ParsedActivityImpl) activity).setResizeMode(resizeMode);
    }

    public static void setExactFlags(ParsedComponent component, int exactFlags) {
        ((ParsedComponentImpl) component).setFlags(exactFlags);
    }

    public static void setEnabled(@NonNull ParsedMainComponent component, boolean enabled) {
        ((ParsedMainComponentImpl) component).setEnabled(enabled);
    }

    public static void setPackageName(@NonNull ParsedComponent component,
            @NonNull String packageName) {
        ((ParsedComponentImpl) component).setPackageName(packageName);
    }

    public static void setDirectBootAware(@NonNull ParsedMainComponent component,
            boolean directBootAware) {
        ((ParsedMainComponentImpl) component).setDirectBootAware(directBootAware);
    }

    public static void setExported(@NonNull ParsedMainComponent component, boolean exported) {
        ((ParsedMainComponentImpl) component).setExported(exported);
    }

    public static void setAuthority(@NonNull ParsedProvider provider, @Nullable String authority) {
        ((ParsedProviderImpl) provider).setAuthority(authority);
    }

    public static void setSyncable(@NonNull ParsedProvider provider, boolean syncable) {
        ((ParsedProviderImpl) provider).setSyncable(syncable);
    }

    public static void setProtectionLevel(@NonNull ParsedPermission permission,
            int protectionLevel) {
        ((ParsedPermissionImpl) permission).setProtectionLevel(protectionLevel);
    }

    public static void setParsedPermissionGroup(@NonNull ParsedPermission permission,
            @NonNull ParsedPermissionGroup permissionGroup) {
        ((ParsedPermissionImpl) permission).setParsedPermissionGroup(permissionGroup);
    }

    public static void setPriority(@NonNull ParsedPermissionGroup parsedPermissionGroup,
            int priority) {
        ((ParsedPermissionGroupImpl) parsedPermissionGroup).setPriority(priority);
    }

    public static void addStateFrom(@NonNull ParsedProcess oldProcess,
            @NonNull ParsedProcess newProcess) {
        ((ParsedProcessImpl) oldProcess).addStateFrom(newProcess);
    }
}
