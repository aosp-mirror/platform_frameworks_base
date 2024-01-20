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

package com.android.server.inputmethod;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.ArrayMap;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class InputMethodManagerServiceRestrictImeAmountTest extends
        InputMethodManagerServiceTestBase {

    @Test
    public void testFilterInputMethodServices_loadsAllImesBelowThreshold() {
        List<ResolveInfo> resolveInfoList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            resolveInfoList.add(
                    createFakeResolveInfo("com.android.apps.inputmethod.simpleime", "IME" + i));
        }

        final List<InputMethodInfo> methodList = filterInputMethodServices(resolveInfoList,
                List.of());
        assertEquals(5, methodList.size());
    }

    @Test
    public void testFilterInputMethodServices_ignoresImesBeyondThreshold() {
        List<ResolveInfo> resolveInfoList = new ArrayList<>();
        for (int i = 0; i < 2 * InputMethodInfo.MAX_IMES_PER_PACKAGE; i++) {
            resolveInfoList.add(
                    createFakeResolveInfo("com.android.apps.inputmethod.simpleime", "IME" + i));
        }

        final List<InputMethodInfo> methodList = filterInputMethodServices(resolveInfoList,
                List.of());
        assertWithMessage("Filtered IMEs").that(methodList.size()).isEqualTo(
                InputMethodInfo.MAX_IMES_PER_PACKAGE);
    }

    @Test
    public void testFilterInputMethodServices_loadsSystemImesBeyondThreshold() {
        List<ResolveInfo> resolveInfoList = new ArrayList<>();
        for (int i = 0; i < 2 * InputMethodInfo.MAX_IMES_PER_PACKAGE; i++) {
            resolveInfoList.add(
                    createFakeSystemResolveInfo("com.android.apps.inputmethod.systemime",
                            "SystemIME" + i));
        }

        final List<InputMethodInfo> methodList = filterInputMethodServices(resolveInfoList,
                List.of());
        assertWithMessage("Filtered IMEs").that(methodList.size()).isEqualTo(
                2 * InputMethodInfo.MAX_IMES_PER_PACKAGE);
    }

    @Test
    public void testFilterInputMethodServices_ignoresImesBeyondThresholdFromTwoPackages() {
        List<ResolveInfo> resolveInfoList = new ArrayList<>();
        for (int i = 0; i < 2 * InputMethodInfo.MAX_IMES_PER_PACKAGE; i++) {
            resolveInfoList.add(
                    createFakeResolveInfo("com.android.apps.inputmethod.simpleime1", "IME1_" + i));
        }
        for (int i = 0; i < 2 * InputMethodInfo.MAX_IMES_PER_PACKAGE; i++) {
            resolveInfoList.add(
                    createFakeResolveInfo("com.android.apps.inputmethod.simpleime2", "IME2_" + i));
        }

        final List<InputMethodInfo> methodList = filterInputMethodServices(resolveInfoList,
                List.of());
        assertWithMessage("Filtered IMEs").that(methodList.size()).isEqualTo(
                2 * InputMethodInfo.MAX_IMES_PER_PACKAGE);
    }

    @Test
    public void testFilterInputMethodServices_stillLoadsEnabledImesBeyondThreshold() {
        final ResolveInfo enabledIme = createFakeResolveInfo(
                "com.android.apps.inputmethod.simpleime_enabled", "EnabledIME");

        List<ResolveInfo> resolveInfoList = new ArrayList<>();
        for (int i = 0; i < 2 * InputMethodInfo.MAX_IMES_PER_PACKAGE; i++) {
            resolveInfoList.add(
                    createFakeResolveInfo("com.android.apps.inputmethod.simpleime", "IME" + i));
        }
        resolveInfoList.add(enabledIme);

        final List<InputMethodInfo> methodList = filterInputMethodServices(resolveInfoList,
                List.of(new ComponentName(enabledIme.serviceInfo.packageName,
                        enabledIme.serviceInfo.name).flattenToShortString()));

        assertWithMessage("Filtered IMEs").that(methodList.size()).isEqualTo(
                1 + InputMethodInfo.MAX_IMES_PER_PACKAGE);
    }

    private List<InputMethodInfo> filterInputMethodServices(List<ResolveInfo> resolveInfoList,
            List<String> enabledComponents) {
        final ArrayMap<String, List<InputMethodSubtype>> emptyAdditionalSubtypeMap =
                new ArrayMap<>();
        final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
        final ArrayList<InputMethodInfo> methodList = new ArrayList<>();
        InputMethodManagerService.filterInputMethodServices(emptyAdditionalSubtypeMap, methodMap,
                methodList, enabledComponents, mContext, resolveInfoList);
        return methodList;
    }

    private ResolveInfo createFakeSystemResolveInfo(String packageName, String componentName) {
        final ResolveInfo ime = createFakeResolveInfo(packageName, componentName);
        ime.serviceInfo.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        return ime;
    }

    private ResolveInfo createFakeResolveInfo(String packageName, String componentName) {
        final ResolveInfo ime = getResolveInfo("com.android.apps.inputmethod.simpleime");
        if (packageName != null) {
            ime.serviceInfo.packageName = packageName;
        }
        if (componentName != null) {
            ime.serviceInfo.name = componentName;
        }
        return ime;
    }

    private ResolveInfo getResolveInfo(String packageName) {
        final int flags = PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        final List<ResolveInfo> ime = mContext.getPackageManager().queryIntentServices(
                new Intent(InputMethod.SERVICE_INTERFACE).setPackage(packageName),
                PackageManager.ResolveInfoFlags.of(flags));
        assertWithMessage("Loaded IMEs").that(ime.size()).isGreaterThan(0);
        return ime.get(0);
    }
}
