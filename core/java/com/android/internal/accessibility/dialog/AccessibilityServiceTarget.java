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

import static com.android.internal.accessibility.util.ShortcutUtils.convertToKey;
import static com.android.internal.accessibility.util.ShortcutUtils.convertToUserType;
import static com.android.internal.accessibility.util.ShortcutUtils.isShortcutContained;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.content.Context;
import android.view.accessibility.AccessibilityManager.ShortcutType;

import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;

/**
 * Base class for creating accessibility service target with various fragment types related to
 * legacy type, invisible type and intuitive type.
 */
class AccessibilityServiceTarget extends AccessibilityTarget {

    AccessibilityServiceTarget(Context context, @ShortcutType int shortcutType,
            @AccessibilityFragmentType int fragmentType,
            @NonNull AccessibilityServiceInfo serviceInfo) {
        super(context,
                shortcutType,
                fragmentType,
                isShortcutContained(context, shortcutType,
                        serviceInfo.getComponentName().flattenToString()),
                serviceInfo.getComponentName().flattenToString(),
                serviceInfo.getResolveInfo().loadLabel(context.getPackageManager()),
                serviceInfo.getResolveInfo().loadIcon(context.getPackageManager()),
                convertToKey(convertToUserType(shortcutType)));
    }
}
