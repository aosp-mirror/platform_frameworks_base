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
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;

import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import static com.android.internal.accessibility.util.AccessibilityUtils.setAccessibilityServiceState;
import static com.android.internal.accessibility.util.ShortcutUtils.isComponentIdExistingInSettings;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.view.accessibility.AccessibilityManager.ShortcutType;

import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;

/**
 * Extension for {@link AccessibilityServiceTarget} with
 * {@link AccessibilityFragmentType#INVISIBLE_TOGGLE} type.
 */
class InvisibleToggleAccessibilityServiceTarget extends AccessibilityServiceTarget {

    InvisibleToggleAccessibilityServiceTarget(Context context, @ShortcutType int shortcutType,
            @NonNull AccessibilityServiceInfo serviceInfo) {
        super(context,
                shortcutType,
                AccessibilityFragmentType.INVISIBLE_TOGGLE,
                serviceInfo);
    }

    @Override
    public void onCheckedChanged(boolean isChecked) {
        final ComponentName componentName = ComponentName.unflattenFromString(getId());

        if (!isComponentIdExistingInOtherShortcut()) {
            setAccessibilityServiceState(getContext(), componentName, isChecked);
        }

        super.onCheckedChanged(isChecked);
    }

    private boolean isComponentIdExistingInOtherShortcut() {
        switch (getShortcutType()) {
            case ACCESSIBILITY_BUTTON:
                return isComponentIdExistingInSettings(getContext(), UserShortcutType.HARDWARE,
                        getId());
            case ACCESSIBILITY_SHORTCUT_KEY:
                return isComponentIdExistingInSettings(getContext(), UserShortcutType.SOFTWARE,
                        getId());
            default:
                throw new IllegalStateException("Unexpected shortcut type");
        }
    }
}
