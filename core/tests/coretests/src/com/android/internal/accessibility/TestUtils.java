/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.accessibility;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import com.android.internal.os.RoSystemProperties;

import java.lang.reflect.Field;

/**
 * Test utility methods.
 */
public class TestUtils {

    /**
     * Sets the {@code enabled} of the given OneHandedMode flags to simulate device behavior.
     */
    public static void setOneHandedModeEnabled(Object obj, boolean enabled) {
        try {
            final Field field = RoSystemProperties.class.getDeclaredField(
                    "SUPPORT_ONE_HANDED_MODE");
            field.setAccessible(true);
            field.setBoolean(obj, enabled);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates fake accessibility service info.
     */
    public static AccessibilityServiceInfo createFakeServiceInfo(
            String packageLabel, String serviceComponent,
            String serviceSummary, boolean isAlwaysOnService) {
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.R;
        ServiceInfo serviceInfo = mock(ServiceInfo.class);
        ResolveInfo resolveInfo = mock(ResolveInfo.class);
        resolveInfo.serviceInfo = serviceInfo;
        resolveInfo.serviceInfo.applicationInfo = applicationInfo;
        when(resolveInfo.loadLabel(any())).thenReturn(packageLabel);

        AccessibilityServiceInfo a11yServiceInfo = mock(AccessibilityServiceInfo.class);
        when(a11yServiceInfo.getResolveInfo()).thenReturn(resolveInfo);
        when(a11yServiceInfo.getComponentName())
                .thenReturn(ComponentName.unflattenFromString(serviceComponent));
        when(a11yServiceInfo.loadSummary(any())).thenReturn(serviceSummary);

        if (isAlwaysOnService) {
            a11yServiceInfo.flags |= AccessibilityServiceInfo
                    .FLAG_REQUEST_ACCESSIBILITY_BUTTON;
        }

        return a11yServiceInfo;
    }
}
