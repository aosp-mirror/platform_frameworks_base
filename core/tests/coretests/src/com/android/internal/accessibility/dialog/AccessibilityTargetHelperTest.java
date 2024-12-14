/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.accessibilityservice.AccessibilityServiceInfo.DEFAULT;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;

import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.HARDWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;

import static com.google.common.truth.Truth.assertThat;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidJUnit4.class)
public class AccessibilityTargetHelperTest {
    @Test
    public void isValidServiceTarget_legacyService_hardware_isTrue() {
        assertThat(AccessibilityTargetHelper.isValidServiceTarget(generateServiceInfo(
                /* flags = */ 0, /* targetSdk = */ 0), HARDWARE)).isTrue();
    }

    @Test
    public void isValidServiceTarget_legacyService_software_requestsButton_isTrue() {
        assertThat(AccessibilityTargetHelper.isValidServiceTarget(generateServiceInfo(
                FLAG_REQUEST_ACCESSIBILITY_BUTTON, /* targetSdk = */ 0), SOFTWARE)).isTrue();
    }

    @Test
    public void isValidServiceTarget_legacyService_software_isFalse() {
        assertThat(AccessibilityTargetHelper.isValidServiceTarget(generateServiceInfo(
                /* flags = */ 0, /* targetSdk = */ 0), SOFTWARE)).isFalse();
    }

    @Test
    public void isValidServiceTarget_modernService_isTrue() {
        assertThat(AccessibilityTargetHelper.isValidServiceTarget(generateServiceInfo(
                /* flags = */ 0, Build.VERSION_CODES.Q + 1), DEFAULT)).isTrue();
    }

    private AccessibilityServiceInfo generateServiceInfo(int flags, int targetSdk) {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = flags;
        info.setResolveInfo(new ResolveInfo());
        info.getResolveInfo().serviceInfo = new ServiceInfo();
        info.getResolveInfo().serviceInfo.applicationInfo = new ApplicationInfo();
        info.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion = targetSdk;
        return info;
    }
}
