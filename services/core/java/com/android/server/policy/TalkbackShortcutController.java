/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.policy;

import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_WEAR_TRIPLE_PRESS_GESTURE;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.accessibility.util.AccessibilityStatsLogUtils;
import com.android.internal.accessibility.util.AccessibilityUtils;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.Set;

/**
 * This class controls talkback shortcut related operations such as toggling, quering and
 * logging.
 */
@VisibleForTesting
class TalkbackShortcutController {
    private static final String TALKBACK_LABEL = "TalkBack";
    private final Context mContext;
    private final PackageManager mPackageManager;

    TalkbackShortcutController(Context context) {
        mContext = context;
        mPackageManager = mContext.getPackageManager();
    }

    /**
     * A function that toggles talkback service.
     *
     * @return talkback state after toggle. {@code true} if talkback is enabled, {@code false} if
     * talkback is disabled
     */
    boolean toggleTalkback(int userId) {
        final Set<ComponentName> enabledServices =
                AccessibilityUtils.getEnabledServicesFromSettings(mContext, userId);
        ComponentName componentName = getTalkbackComponent();
        boolean isTalkbackAlreadyEnabled = enabledServices.contains(componentName);

        if (isTalkBackShortcutGestureEnabled()) {
            isTalkbackAlreadyEnabled = !isTalkbackAlreadyEnabled;
            AccessibilityUtils.setAccessibilityServiceState(mContext, componentName,
                    isTalkbackAlreadyEnabled);

            // log stem triple press telemetry if it's a talkback enabled event.
            if (componentName != null && isTalkbackAlreadyEnabled) {
                logStemTriplePressAccessibilityTelemetry(componentName);
            }
        }
        return isTalkbackAlreadyEnabled;
    }

    private ComponentName getTalkbackComponent() {
        AccessibilityManager accessibilityManager = mContext.getSystemService(
                AccessibilityManager.class);
        List<AccessibilityServiceInfo> serviceInfos =
                accessibilityManager.getInstalledAccessibilityServiceList();

        for (AccessibilityServiceInfo service : serviceInfos) {
            final ServiceInfo serviceInfo = service.getResolveInfo().serviceInfo;
            if (isTalkback(serviceInfo)) {
                return new ComponentName(serviceInfo.packageName, serviceInfo.name);
            }
        }
        return null;
    }

    boolean isTalkBackShortcutGestureEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.WEAR_ACCESSIBILITY_GESTURE_ENABLED,
                /* def= */ 0, UserHandle.USER_CURRENT) == 1;
    }

    /**
     * A function that logs stem triple press accessibility telemetry. If the user setup (Oobe)
     * is not completed, set the WEAR_ACCESSIBILITY_GESTURE_ENABLED_DURING_OOBE setting which
     * will be later logged via Settings Snapshot  else, log ACCESSIBILITY_SHORTCUT_REPORTED atom
     */
    private void logStemTriplePressAccessibilityTelemetry(ComponentName componentName) {
        if (!AccessibilityUtils.isUserSetupCompleted(mContext)) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.System.WEAR_ACCESSIBILITY_GESTURE_ENABLED_DURING_OOBE, 1);
            return;
        }
        AccessibilityStatsLogUtils.logAccessibilityShortcutActivated(mContext,
                componentName,
                ACCESSIBILITY_SHORTCUT_REPORTED__SHORTCUT_TYPE__A11Y_WEAR_TRIPLE_PRESS_GESTURE,
                /* serviceEnabled= */ true);
    }

    private boolean isTalkback(ServiceInfo info) {
        return TALKBACK_LABEL.equals(info.loadLabel(mPackageManager).toString());
    }
}
