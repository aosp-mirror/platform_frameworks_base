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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.Annotation;
import android.telephony.TelephonyManager;
import android.text.ParcelableSpan;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.VisibleForTesting;

import libcore.util.EmptyArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Collection of utilities for accessibility service.
 */
public final class AccessibilityUtils {
    private AccessibilityUtils() {
    }

    /** @hide */
    @IntDef(value = {
            NONE,
            TEXT,
            PARCELABLE_SPAN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface A11yTextChangeType {
    }

    /** Denotes the accessibility enabled status */
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
        int OFF = 0;
        int ON = 1;
    }

    /** Specifies no content has been changed for accessibility. */
    public static final int NONE = 0;
    /** Specifies some readable sequence has been changed. */
    public static final int TEXT = 1;
    /** Specifies some parcelable spans has been changed. */
    public static final int PARCELABLE_SPAN = 2;

    @VisibleForTesting
    public static final String MENU_SERVICE_RELATIVE_CLASS_NAME = ".AccessibilityMenuService";

    /**
     * {@link ComponentName} for the Accessibility Menu {@link AccessibilityService} as provided
     * inside the system build, used for automatic migration to this version of the service.
     * @hide
     */
    public static final ComponentName ACCESSIBILITY_MENU_IN_SYSTEM =
            new ComponentName("com.android.systemui.accessibility.accessibilitymenu",
                    "com.android.systemui.accessibility.accessibilitymenu"
                            + MENU_SERVICE_RELATIVE_CLASS_NAME);

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
     * Changes an accessibility component's state for the calling process userId
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
        final AccessibilityManager am = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
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

    /**
     * Intercepts the {@link AccessibilityService#GLOBAL_ACTION_KEYCODE_HEADSETHOOK} action
     * by directly interacting with TelecomManager if a call is incoming or in progress.
     *
     * <p>
     * Provided here in shared utils to be used by both the legacy and modern (SysUI)
     * system action implementations.
     * </p>
     *
     * @return True if the action was propagated to TelecomManager, otherwise false.
     */
    public static boolean interceptHeadsetHookForActiveCall(Context context) {
        final TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
        @Annotation.CallState final int callState =
                telecomManager != null ? telecomManager.getCallState()
                        : TelephonyManager.CALL_STATE_IDLE;
        if (callState == TelephonyManager.CALL_STATE_RINGING) {
            telecomManager.acceptRingingCall();
            return true;
        } else if (callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            telecomManager.endCall();
            return true;
        }
        return false;
    }

    /**
     * Indicates whether the current user has completed setup via the setup wizard.
     * {@link android.provider.Settings.Secure#USER_SETUP_COMPLETE}
     *
     * @return {@code true} if the setup is completed.
     */
    public static boolean isUserSetupCompleted(Context context) {
        return Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, /* def= */ 0, UserHandle.USER_CURRENT)
                != /* false */ 0;
    }

    /**
     * Returns the text change type for accessibility. It only cares about readable sequence changes
     * or {@link ParcelableSpan} changes which are able to pass via IPC.
     *
     * @param before The CharSequence before changing
     * @param after  The CharSequence after changing
     * @return Returns {@code TEXT} for readable sequence changes or {@code PARCELABLE_SPAN} for
     * ParcelableSpan changes. Otherwise, returns {@code NONE}.
     */
    @A11yTextChangeType
    public static int textOrSpanChanged(CharSequence before, CharSequence after) {
        if (!TextUtils.equals(before, after)) {
            return TEXT;
        }
        if (before instanceof Spanned || after instanceof Spanned) {
            if (!parcelableSpansEquals(before, after)) {
                return PARCELABLE_SPAN;
            }
        }
        return NONE;
    }

    private static boolean parcelableSpansEquals(CharSequence before, CharSequence after) {
        Object[] spansA = EmptyArray.OBJECT;
        Object[] spansB = EmptyArray.OBJECT;
        Spanned a = null;
        Spanned b = null;
        if (before instanceof Spanned) {
            a = (Spanned) before;
            spansA = a.getSpans(0, a.length(), ParcelableSpan.class);
        }
        if (after instanceof Spanned) {
            b = (Spanned) after;
            spansB = b.getSpans(0, b.length(), ParcelableSpan.class);
        }
        if (spansA.length != spansB.length) {
            return false;
        }
        for (int i = 0; i < spansA.length; ++i) {
            final Object thisSpan = spansA[i];
            final Object otherSpan = spansB[i];
            if ((thisSpan.getClass() != otherSpan.getClass())
                    || (a.getSpanStart(thisSpan) != b.getSpanStart(otherSpan))
                    || (a.getSpanEnd(thisSpan) != b.getSpanEnd(otherSpan))
                    || (a.getSpanFlags(thisSpan) != b.getSpanFlags(otherSpan))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the {@link ComponentName} of the AccessibilityMenu accessibility service that the
     * device should be migrated off. Devices using this service should be migrated to
     * {@link #ACCESSIBILITY_MENU_IN_SYSTEM}.
     *
     * <p>
     * Requirements:
     * <li>There are exactly two installed accessibility service components with class name
     * {@link #MENU_SERVICE_RELATIVE_CLASS_NAME}.</li>
     * <li>Exactly one of these components is equal to {@link #ACCESSIBILITY_MENU_IN_SYSTEM}.</li>
     * </p>
     *
     * @return The {@link ComponentName} of the service that is not {@link
     * #ACCESSIBILITY_MENU_IN_SYSTEM},
     * or <code>null</code> if the above requirements are not met.
     */
    @Nullable
    public static ComponentName getAccessibilityMenuComponentToMigrate(
            PackageManager packageManager, int userId) {
        final Set<ComponentName> menuComponentNames = findA11yMenuComponentNames(packageManager,
                userId);
        Optional<ComponentName> menuOutsideSystem = menuComponentNames.stream().filter(
                name -> !name.equals(ACCESSIBILITY_MENU_IN_SYSTEM)).findFirst();
        final boolean shouldMigrateToMenuInSystem = menuComponentNames.size() == 2
                && menuComponentNames.contains(ACCESSIBILITY_MENU_IN_SYSTEM)
                && menuOutsideSystem.isPresent();
        return shouldMigrateToMenuInSystem ? menuOutsideSystem.get() : null;
    }

    /**
     * Returns all {@link ComponentName}s whose class name ends in {@link
     * #MENU_SERVICE_RELATIVE_CLASS_NAME}.
     **/
    private static Set<ComponentName> findA11yMenuComponentNames(
            PackageManager packageManager, int userId) {
        Set<ComponentName> result = new ArraySet<>();
        final PackageManager.ResolveInfoFlags flags = PackageManager.ResolveInfoFlags.of(
                PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        for (ResolveInfo resolveInfo : packageManager.queryIntentServicesAsUser(
                new Intent(AccessibilityService.SERVICE_INTERFACE), flags, userId)) {
            final ComponentName componentName = resolveInfo.serviceInfo.getComponentName();
            if (componentName.getClassName().endsWith(MENU_SERVICE_RELATIVE_CLASS_NAME)) {
                result.add(componentName);
            }
        }
        return result;
    }

    /** Returns the {@link ComponentName} of an installed accessibility service by label. */
    @Nullable
    public static ComponentName getInstalledAccessibilityServiceComponentNameByLabel(
            Context context, String label) {
        AccessibilityManager accessibilityManager =
                context.getSystemService(AccessibilityManager.class);
        List<AccessibilityServiceInfo> serviceInfos =
                accessibilityManager.getInstalledAccessibilityServiceList();

        for (AccessibilityServiceInfo service : serviceInfos) {
            final ServiceInfo serviceInfo = service.getResolveInfo().serviceInfo;
            if (label.equals(serviceInfo.loadLabel(context.getPackageManager()).toString())
                    && (serviceInfo.applicationInfo.isSystemApp()
                            || serviceInfo.applicationInfo.isUpdatedSystemApp())) {
                return new ComponentName(serviceInfo.packageName, serviceInfo.name);
            }
        }
        return null;
    }
}
