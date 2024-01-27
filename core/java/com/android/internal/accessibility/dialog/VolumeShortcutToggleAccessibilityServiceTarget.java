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

import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import static com.android.internal.accessibility.util.AccessibilityUtils.setAccessibilityServiceState;
import static com.android.internal.accessibility.util.ShortcutUtils.optOutValueFromSettings;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;

/**
 * Extension for {@link AccessibilityServiceTarget} with
 * {@link AccessibilityFragmentType#VOLUME_SHORTCUT_TOGGLE} type.
 */
class VolumeShortcutToggleAccessibilityServiceTarget extends AccessibilityServiceTarget {

    VolumeShortcutToggleAccessibilityServiceTarget(Context context,
            @UserShortcutType int shortcutType,
            @NonNull AccessibilityServiceInfo serviceInfo) {
        super(context,
                shortcutType,
                AccessibilityFragmentType.VOLUME_SHORTCUT_TOGGLE,
                serviceInfo);
    }

    @Override
    public void onCheckedChanged(boolean isChecked) {
        switch (getShortcutType()) {
            case UserShortcutType.SOFTWARE:
                onCheckedFromAccessibilityButton(isChecked);
                return;
            case UserShortcutType.HARDWARE:
                super.onCheckedChanged(isChecked);
                return;
            default:
                throw new IllegalStateException("Unexpected shortcut type");
        }
    }

    private void onCheckedFromAccessibilityButton(boolean isChecked) {
        setShortcutEnabled(isChecked);
        final ComponentName componentName = ComponentName.unflattenFromString(getId());
        setAccessibilityServiceState(getContext(), componentName, isChecked);

        if (!isChecked) {
            optOutValueFromSettings(getContext(), UserShortcutType.HARDWARE, getId());

            final String warningText =
                    getContext().getString(R.string.accessibility_uncheck_legacy_item_warning,
                            getLabel());
            Toast.makeText(getContext(), warningText, Toast.LENGTH_SHORT).show();
        }
    }
}
