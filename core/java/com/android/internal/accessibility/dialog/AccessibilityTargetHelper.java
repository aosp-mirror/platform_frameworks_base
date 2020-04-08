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

import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;

import static com.android.internal.accessibility.AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.util.AccessibilityUtils.getAccessibilityServiceFragmentType;
import static com.android.internal.accessibility.util.ShortcutUtils.isShortcutContained;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.ShortcutType;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Collection of utilities for accessibility target.
 */
final class AccessibilityTargetHelper {
    private AccessibilityTargetHelper() {}

    static List<AccessibilityTarget> getTargets(Context context,
            @ShortcutType int shortcutType) {
        final List<AccessibilityTarget> targets = getInstalledTargets(context, shortcutType);
        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<String> requiredTargets = ams.getAccessibilityShortcutTargets(shortcutType);
        targets.removeIf(target -> !requiredTargets.contains(target.getId()));

        return targets;
    }

    static List<AccessibilityTarget> getInstalledTargets(Context context,
            @ShortcutType int shortcutType) {
        final List<AccessibilityTarget> targets = new ArrayList<>();
        targets.addAll(getAccessibilityFilteredTargets(context, shortcutType));
        targets.addAll(getWhiteListingFeatureTargets(context, shortcutType));

        return targets;
    }

    private static List<AccessibilityTarget> getAccessibilityFilteredTargets(Context context,
            @ShortcutType int shortcutType) {
        final List<AccessibilityTarget> serviceTargets =
                getAccessibilityServiceTargets(context, shortcutType);
        final List<AccessibilityTarget> activityTargets =
                getAccessibilityActivityTargets(context, shortcutType);

        for (AccessibilityTarget activityTarget : activityTargets) {
            serviceTargets.removeIf(
                    serviceTarget -> arePackageNameAndLabelTheSame(serviceTarget, activityTarget));
        }

        final List<AccessibilityTarget> targets = new ArrayList<>();
        targets.addAll(serviceTargets);
        targets.addAll(activityTargets);

        return targets;
    }

    private static boolean arePackageNameAndLabelTheSame(@NonNull AccessibilityTarget serviceTarget,
            @NonNull AccessibilityTarget activityTarget) {
        final ComponentName serviceComponentName =
                ComponentName.unflattenFromString(serviceTarget.getId());
        final ComponentName activityComponentName =
                ComponentName.unflattenFromString(activityTarget.getId());
        final boolean isSamePackageName = activityComponentName.getPackageName().equals(
                serviceComponentName.getPackageName());
        final boolean isSameLabel = activityTarget.getLabel().equals(
                serviceTarget.getLabel());

        return isSamePackageName && isSameLabel;
    }

    private static List<AccessibilityTarget> getAccessibilityServiceTargets(Context context,
            @ShortcutType int shortcutType) {
        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<AccessibilityServiceInfo> installedServices =
                ams.getInstalledAccessibilityServiceList();
        if (installedServices == null) {
            return Collections.emptyList();
        }

        final List<AccessibilityTarget> targets = new ArrayList<>(installedServices.size());
        for (AccessibilityServiceInfo info : installedServices) {
            final int targetSdk =
                    info.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion;
            final boolean hasRequestAccessibilityButtonFlag =
                    (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            if ((targetSdk <= Build.VERSION_CODES.Q) && !hasRequestAccessibilityButtonFlag
                    && (shortcutType == ACCESSIBILITY_BUTTON)) {
                continue;
            }

            targets.add(createAccessibilityServiceTarget(context, shortcutType, info));
        }

        return targets;
    }

    private static List<AccessibilityTarget> getAccessibilityActivityTargets(Context context,
            @ShortcutType int shortcutType) {
        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<AccessibilityShortcutInfo> installedServices =
                ams.getInstalledAccessibilityShortcutListAsUser(context,
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

    private static List<AccessibilityTarget> getWhiteListingFeatureTargets(Context context,
            @ShortcutType int shortcutType) {
        final List<AccessibilityTarget> targets = new ArrayList<>();

        final InvisibleToggleWhiteListingFeatureTarget magnification =
                new InvisibleToggleWhiteListingFeatureTarget(context,
                shortcutType,
                isShortcutContained(context, shortcutType, MAGNIFICATION_CONTROLLER_NAME),
                MAGNIFICATION_CONTROLLER_NAME,
                context.getString(R.string.accessibility_magnification_chooser_text),
                context.getDrawable(R.drawable.ic_accessibility_magnification),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED);

        final ToggleWhiteListingFeatureTarget daltonizer =
                new ToggleWhiteListingFeatureTarget(context,
                shortcutType,
                isShortcutContained(context, shortcutType,
                        DALTONIZER_COMPONENT_NAME.flattenToString()),
                DALTONIZER_COMPONENT_NAME.flattenToString(),
                context.getString(R.string.color_correction_feature_name),
                context.getDrawable(R.drawable.ic_accessibility_color_correction),
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED);

        final ToggleWhiteListingFeatureTarget colorInversion =
                new ToggleWhiteListingFeatureTarget(context,
                shortcutType,
                isShortcutContained(context, shortcutType,
                        COLOR_INVERSION_COMPONENT_NAME.flattenToString()),
                COLOR_INVERSION_COMPONENT_NAME.flattenToString(),
                context.getString(R.string.color_inversion_feature_name),
                context.getDrawable(R.drawable.ic_accessibility_color_inversion),
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);

        targets.add(magnification);
        targets.add(daltonizer);
        targets.add(colorInversion);

        return targets;
    }

    private static AccessibilityTarget createAccessibilityServiceTarget(Context context,
            @ShortcutType int shortcutType, @NonNull AccessibilityServiceInfo info) {
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

    static View createEnableDialogContentView(Context context,
            AccessibilityServiceTarget target, View.OnClickListener allowListener,
            View.OnClickListener denyListener) {
        final LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        final View content = inflater.inflate(
                R.layout.accessibility_enable_service_encryption_warning, /* root= */ null);

        final TextView encryptionWarningView = (TextView) content.findViewById(
                R.id.accessibility_encryption_warning);
        if (StorageManager.isNonDefaultBlockEncrypted()) {
            final String text = context.getString(
                    R.string.accessibility_enable_service_encryption_warning,
                    getServiceName(context, target.getLabel()));
            encryptionWarningView.setText(text);
            encryptionWarningView.setVisibility(View.VISIBLE);
        } else {
            encryptionWarningView.setVisibility(View.GONE);
        }

        final ImageView dialogIcon = content.findViewById(
                R.id.accessibility_permissionDialog_icon);
        dialogIcon.setImageDrawable(target.getIcon());

        final TextView dialogTitle = content.findViewById(
                R.id.accessibility_permissionDialog_title);
        dialogTitle.setText(context.getString(R.string.accessibility_enable_service_title,
                getServiceName(context, target.getLabel())));

        final Button allowButton = content.findViewById(
                R.id.accessibility_permission_enable_allow_button);
        final Button denyButton = content.findViewById(
                R.id.accessibility_permission_enable_deny_button);
        allowButton.setOnClickListener((view) -> {
            target.onCheckedChanged(/* isChecked= */ true);
            allowListener.onClick(view);
        });
        denyButton.setOnClickListener((view) -> {
            target.onCheckedChanged(/* isChecked= */ false);
            denyListener.onClick(view);
        });

        return content;
    }

    // Gets the service name and bidi wrap it to protect from bidi side effects.
    private static CharSequence getServiceName(Context context, CharSequence label) {
        final Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return BidiFormatter.getInstance(locale).unicodeWrap(label);
    }
}
