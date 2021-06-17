/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;
import static android.hardware.biometrics.BiometricManager.Authenticators;
import static android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.os.UserManager;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.widget.LockPatternUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

public class Utils {

    public static final int CREDENTIAL_PIN = 1;
    public static final int CREDENTIAL_PATTERN = 2;
    public static final int CREDENTIAL_PASSWORD = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CREDENTIAL_PIN, CREDENTIAL_PATTERN, CREDENTIAL_PASSWORD})
    @interface CredentialType {}

    static float dpToPixels(Context context, float dp) {
        return dp * ((float) context.getResources().getDisplayMetrics().densityDpi
                / DisplayMetrics.DENSITY_DEFAULT);
    }

    static float pixelsToDp(Context context, float pixels) {
        return pixels / ((float) context.getResources().getDisplayMetrics().densityDpi
                / DisplayMetrics.DENSITY_DEFAULT);
    }

    static void notifyAccessibilityContentChanged(AccessibilityManager am, ViewGroup view) {
        if (!am.isEnabled()) {
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setContentChangeTypes(CONTENT_CHANGE_TYPE_SUBTREE);
        view.sendAccessibilityEventUnchecked(event);
        view.notifySubtreeAccessibilityStateChanged(view, view, CONTENT_CHANGE_TYPE_SUBTREE);
    }

    static boolean isDeviceCredentialAllowed(PromptInfo promptInfo) {
        @Authenticators.Types final int authenticators = promptInfo.getAuthenticators();
        return (authenticators & Authenticators.DEVICE_CREDENTIAL) != 0;
    }

    static boolean isBiometricAllowed(PromptInfo promptInfo) {
        @Authenticators.Types final int authenticators = promptInfo.getAuthenticators();
        return (authenticators & Authenticators.BIOMETRIC_WEAK) != 0;
    }

    static @CredentialType int getCredentialType(Context context, int userId) {
        final LockPatternUtils lpu = new LockPatternUtils(context);
        switch (lpu.getKeyguardStoredPasswordQuality(userId)) {
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                return CREDENTIAL_PATTERN;
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
                return CREDENTIAL_PIN;
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
            case DevicePolicyManager.PASSWORD_QUALITY_MANAGED:
                return CREDENTIAL_PASSWORD;
            default:
                return CREDENTIAL_PASSWORD;
        }
    }

    static boolean isManagedProfile(Context context, int userId) {
        final UserManager userManager = context.getSystemService(UserManager.class);
        return userManager.isManagedProfile(userId);
    }

    static boolean containsSensorId(@Nullable List<? extends SensorPropertiesInternal> properties,
            int sensorId) {
        if (properties == null) {
            return false;
        }

        for (SensorPropertiesInternal prop : properties) {
            if (prop.sensorId == sensorId) {
                return true;
            }
        }

        return false;
    }

    static boolean isSystem(@NonNull Context context, @Nullable String clientPackage) {
        final boolean hasPermission = context.checkCallingOrSelfPermission(USE_BIOMETRIC_INTERNAL)
                == PackageManager.PERMISSION_GRANTED;
        return hasPermission && "android".equals(clientPackage);
    }
}
