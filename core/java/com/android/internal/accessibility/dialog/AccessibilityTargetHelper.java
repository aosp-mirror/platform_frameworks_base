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

package com.android.internal.accessibility.dialog;

import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.ONE_HANDED_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_COMPONENT_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.internal.accessibility.util.AccessibilityUtils.getAccessibilityServiceFragmentType;
import static com.android.internal.accessibility.util.ShortcutUtils.isShortcutContained;
import static com.android.internal.os.RoSystemProperties.SUPPORT_ONE_HANDED_MODE;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;
import com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collection of utilities for accessibility target.
 */
public final class AccessibilityTargetHelper {
    private AccessibilityTargetHelper() {}

    /**
     * Returns list of {@link AccessibilityTarget} of assigned accessibility shortcuts from
     * {@link AccessibilityManager#getAccessibilityShortcutTargets} including accessibility
     * feature's package name, component id, etc.
     *
     * @param context The context of the application.
     * @param shortcutType The shortcut type.
     * @return The list of {@link AccessibilityTarget}.
     * @hide
     */
    public static List<AccessibilityTarget> getTargets(Context context,
            @UserShortcutType int shortcutType) {
        // List all accessibility target
        final List<AccessibilityTarget> installedTargets = getInstalledTargets(context,
                shortcutType);

        // List accessibility shortcut target
        final AccessibilityManager am = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        final List<String> assignedTargets = am.getAccessibilityShortcutTargets(shortcutType);

        // Get the list of accessibility shortcut target in all accessibility target
        final List<AccessibilityTarget> results = new ArrayList<>();
        for (String assignedTarget : assignedTargets) {
            for (AccessibilityTarget installedTarget : installedTargets) {
                if (!MAGNIFICATION_CONTROLLER_NAME.contentEquals(assignedTarget)) {
                    final ComponentName assignedTargetComponentName =
                            ComponentName.unflattenFromString(assignedTarget);
                    final ComponentName targetComponentName = ComponentName.unflattenFromString(
                            installedTarget.getId());
                    if (assignedTargetComponentName.equals(targetComponentName)) {
                        results.add(installedTarget);
                        continue;
                    }
                }
                if (assignedTarget.contentEquals(installedTarget.getId())) {
                    results.add(installedTarget);
                }
            }
        }
        return results;
    }

    /**
     * Returns list of {@link AccessibilityTarget} of the installed accessibility service,
     * accessibility activity, and allowlisting feature including accessibility feature's package
     * name, component id, etc.
     *
     * @param context The context of the application.
     * @param shortcutType The shortcut type.
     * @return The list of {@link AccessibilityTarget}.
     * @hide
     */
    public static List<AccessibilityTarget> getInstalledTargets(Context context,
            @UserShortcutType int shortcutType) {
        final List<AccessibilityTarget> targets = new ArrayList<>();
        targets.addAll(getAccessibilityServiceTargets(context, shortcutType));
        targets.addAll(getAccessibilityActivityTargets(context, shortcutType));
        targets.addAll(getAllowListingFeatureTargets(context, shortcutType));

        return targets;
    }

    private static List<AccessibilityTarget> getAccessibilityServiceTargets(Context context,
            @UserShortcutType int shortcutType) {
        final AccessibilityManager am = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        final List<AccessibilityServiceInfo> installedServices =
                am.getInstalledAccessibilityServiceList();
        if (installedServices == null) {
            return Collections.emptyList();
        }

        final List<AccessibilityTarget> targets = new ArrayList<>(installedServices.size());
        for (AccessibilityServiceInfo info : installedServices) {
            if (isValidServiceTarget(info, shortcutType)) {
                targets.add(createAccessibilityServiceTarget(context, shortcutType, info));
            }
        }

        return targets;
    }

    /**
     * Check for maintaining compatibility on prior versions.
     * Determines if a given service should be accumulated in a list of installed services.
     * @param info service info to check.
     * @param shortcutType type of shortcut to accumulate a list for.
     * @return {@code true} if the service should be added (always true past version Q),
     * otherwise {@code false}.
     */
    @VisibleForTesting
    public static boolean isValidServiceTarget(
            AccessibilityServiceInfo info, @UserShortcutType int shortcutType) {
        final boolean hasRequestAccessibilityButtonFlag =
                (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
        return (info.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion
                > Build.VERSION_CODES.Q) || hasRequestAccessibilityButtonFlag
                || shortcutType != SOFTWARE;
    }

    private static List<AccessibilityTarget> getAccessibilityActivityTargets(Context context,
            @UserShortcutType int shortcutType) {
        final AccessibilityManager am = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        final List<AccessibilityShortcutInfo> installedServices =
                am.getInstalledAccessibilityShortcutListAsUser(context,
                        ActivityManager.getCurrentUser());
        if (installedServices == null) {
            return Collections.emptyList();
        }

        final List<AccessibilityTarget> targets = new ArrayList<>(installedServices.size());
        for (AccessibilityShortcutInfo info : installedServices) {
            targets.add(new AccessibilityActivityTarget(context, shortcutType, info));
        }

        return targets;
    }

    private static List<AccessibilityTarget> getAllowListingFeatureTargets(Context context,
            @UserShortcutType int shortcutType) {
        final List<AccessibilityTarget> targets = new ArrayList<>();
        final int uid = context.getApplicationInfo().uid;

        final InvisibleToggleAllowListingFeatureTarget magnification =
                new InvisibleToggleAllowListingFeatureTarget(context,
                        shortcutType,
                        isShortcutContained(context, shortcutType, MAGNIFICATION_CONTROLLER_NAME),
                        MAGNIFICATION_CONTROLLER_NAME,
                        uid,
                        context.getString(R.string.accessibility_magnification_chooser_text),
                        context.getDrawable(R.drawable.ic_accessibility_magnification),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED);
        targets.add(magnification);

        final ToggleAllowListingFeatureTarget daltonizer =
                new ToggleAllowListingFeatureTarget(context,
                        shortcutType,
                        isShortcutContained(context, shortcutType,
                                DALTONIZER_COMPONENT_NAME.flattenToString()),
                        DALTONIZER_COMPONENT_NAME.flattenToString(),
                        uid,
                        context.getString(R.string.color_correction_feature_name),
                        context.getDrawable(R.drawable.ic_accessibility_color_correction),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED);
        targets.add(daltonizer);

        final ToggleAllowListingFeatureTarget colorInversion =
                new ToggleAllowListingFeatureTarget(context,
                        shortcutType,
                        isShortcutContained(context, shortcutType,
                                COLOR_INVERSION_COMPONENT_NAME.flattenToString()),
                        COLOR_INVERSION_COMPONENT_NAME.flattenToString(),
                        uid,
                        context.getString(R.string.color_inversion_feature_name),
                        context.getDrawable(R.drawable.ic_accessibility_color_inversion),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
        targets.add(colorInversion);

        if (SUPPORT_ONE_HANDED_MODE) {
            final ToggleAllowListingFeatureTarget oneHandedMode =
                    new ToggleAllowListingFeatureTarget(context,
                            shortcutType,
                            isShortcutContained(context, shortcutType,
                                    ONE_HANDED_COMPONENT_NAME.flattenToString()),
                            ONE_HANDED_COMPONENT_NAME.flattenToString(),
                            uid,
                            context.getString(R.string.one_handed_mode_feature_name),
                            context.getDrawable(R.drawable.ic_accessibility_one_handed),
                            Settings.Secure.ONE_HANDED_MODE_ACTIVATED);
            targets.add(oneHandedMode);
        }

        final ToggleAllowListingFeatureTarget reduceBrightColors =
                new ToggleAllowListingFeatureTarget(context,
                        shortcutType,
                        isShortcutContained(context, shortcutType,
                                REDUCE_BRIGHT_COLORS_COMPONENT_NAME.flattenToString()),
                        REDUCE_BRIGHT_COLORS_COMPONENT_NAME.flattenToString(),
                        uid,
                        context.getString(R.string.reduce_bright_colors_feature_name),
                        context.getDrawable(R.drawable.ic_accessibility_reduce_bright_colors),
                        Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED);
        targets.add(reduceBrightColors);

        final InvisibleToggleAllowListingFeatureTarget hearingAids =
                new InvisibleToggleAllowListingFeatureTarget(context,
                        shortcutType,
                        isShortcutContained(context, shortcutType,
                                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString()),
                        ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString(),
                        uid,
                        context.getString(R.string.hearing_aids_feature_name),
                        context.getDrawable(R.drawable.ic_accessibility_hearing_aid),
                        /* key= */ null);
        targets.add(hearingAids);

        return targets;
    }

    private static AccessibilityTarget createAccessibilityServiceTarget(Context context,
            @UserShortcutType int shortcutType, @NonNull AccessibilityServiceInfo info) {
        switch (getAccessibilityServiceFragmentType(info)) {
            case AccessibilityFragmentType.VOLUME_SHORTCUT_TOGGLE:
                return new VolumeShortcutToggleAccessibilityServiceTarget(context, shortcutType,
                        info);
            case AccessibilityFragmentType.INVISIBLE_TOGGLE:
                return new InvisibleToggleAccessibilityServiceTarget(context, shortcutType, info);
            case AccessibilityFragmentType.TOGGLE:
                return new ToggleAccessibilityServiceTarget(context, shortcutType, info);
            default:
                throw new IllegalStateException("Unexpected fragment type");
        }
    }

    /**
     * Determines if the{@link AccessibilityTarget} is allowed.
     */
    public static boolean isAccessibilityTargetAllowed(Context context, String packageName,
            int uid) {
        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        return am.isAccessibilityTargetAllowed(packageName, uid, UserHandle.myUserId());
    }

    /**
     * Sends restricted dialog intent if the accessibility target is disallowed.
     */
    public static boolean sendRestrictedDialogIntent(Context context, String packageName, int uid) {
        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        return am.sendRestrictedDialogIntent(packageName, uid, UserHandle.myUserId());
    }
}
