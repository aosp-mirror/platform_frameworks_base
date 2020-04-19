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

package com.android.internal.accessibility.util;

import static com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;
import static com.android.internal.accessibility.common.ShortcutConstants.SERVICES_SEPARATOR;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collection of utilities for accessibility service.
 */
public final class AccessibilityUtils {
    private AccessibilityUtils() {}

    /**
     * Returns the set of enabled accessibility services for userId. If there are no
     * services, it returns the unmodifiable {@link Collections#emptySet()}.
     */
    public static Set<ComponentName> getEnabledServicesFromSettings(Context context, int userId) {
        final String enabledServicesSetting = Settings.Secure.getStringForUser(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userId);
        if (TextUtils.isEmpty(enabledServicesSetting)) {
            return Collections.emptySet();
        }

        final Set<ComponentName> enabledServices = new HashSet<>();
        final TextUtils.StringSplitter colonSplitter =
                new TextUtils.SimpleStringSplitter(SERVICES_SEPARATOR);
        colonSplitter.setString(enabledServicesSetting);

        for (String componentNameString : colonSplitter) {
            final ComponentName enabledService = ComponentName.unflattenFromString(
                    componentNameString);
            if (enabledService != null) {
                enabledServices.add(enabledService);
            }
        }

        return enabledServices;
    }

    /**
     * Changes an accessibility component's state.
     */
    public static void setAccessibilityServiceState(Context context, ComponentName componentName,
            boolean enabled) {
        setAccessibilityServiceState(context, componentName, enabled, UserHandle.myUserId());
    }

    /**
     * Changes an accessibility component's state for {@param userId}.
     */
    public static void setAccessibilityServiceState(Context context, ComponentName componentName,
            boolean enabled, int userId) {
        Set<ComponentName> enabledServices = getEnabledServicesFromSettings(
                context, userId);

        if (enabledServices.isEmpty()) {
            enabledServices = new ArraySet<>(/* capacity= */ 1);
        }

        if (enabled) {
            enabledServices.add(componentName);
        } else {
            enabledServices.remove(componentName);
        }

        final StringBuilder enabledServicesBuilder = new StringBuilder();
        for (ComponentName enabledService : enabledServices) {
            enabledServicesBuilder.append(enabledService.flattenToString());
            enabledServicesBuilder.append(
                    SERVICES_SEPARATOR);
        }

        final int enabledServicesBuilderLength = enabledServicesBuilder.length();
        if (enabledServicesBuilderLength > 0) {
            enabledServicesBuilder.deleteCharAt(enabledServicesBuilderLength - 1);
        }

        Settings.Secure.putStringForUser(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabledServicesBuilder.toString(), userId);
    }

    /**
     * Gets the corresponding fragment type of a given accessibility service.
     *
     * @param accessibilityServiceInfo The accessibilityService's info.
     * @return int from {@link AccessibilityFragmentType}.
     */
    public static @AccessibilityFragmentType int getAccessibilityServiceFragmentType(
            @NonNull AccessibilityServiceInfo accessibilityServiceInfo) {
        final int targetSdk = accessibilityServiceInfo.getResolveInfo()
                .serviceInfo.applicationInfo.targetSdkVersion;
        final boolean requestA11yButton = (accessibilityServiceInfo.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;

        if (targetSdk <= Build.VERSION_CODES.Q) {
            return AccessibilityFragmentType.VOLUME_SHORTCUT_TOGGLE;
        }
        return requestA11yButton
                ? AccessibilityFragmentType.INVISIBLE_TOGGLE
                : AccessibilityFragmentType.TOGGLE;
    }

    /**
     * Returns if a {@code componentId} service is enabled.
     *
     * @param context The current context.
     * @param componentId The component id that need to be checked.
     * @return {@code true} if a {@code componentId} service is enabled.
     */
    public static boolean isAccessibilityServiceEnabled(Context context,
            @NonNull String componentId) {
        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        final List<AccessibilityServiceInfo> enabledServices =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        for (AccessibilityServiceInfo info : enabledServices) {
            final String id = info.getComponentName().flattenToString();
            if (id.equals(componentId)) {
                return true;
            }
        }

        return false;
    }
}
