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

package com.android.server.inputmethod;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MultiClientInputMethodManagerServiceTest {

    @Before
    public void setUp() {
        // MultiClientInputMethodManagerService is only testable if build is debuggable.
        assumeTrue(Build.IS_DEBUGGABLE);
    }

    @Test
    public void testQueryInputMethod_noIMEFound() {
        assertThat(MultiClientInputMethodManagerService.resolveMultiClientImeService(
                emptyList())).isNull();
    }

    @Test
    public void testQueryInputMethod_multipleIMEsFound() {
        assertThat(MultiClientInputMethodManagerService.resolveMultiClientImeService(
                asList(new ResolveInfo(), new ResolveInfo()))).isNull();
    }

    @Test
    public void testQueryInputMethod_IMEFound_invalidPermission() {
        // Arrange
        ResolveInfo imeService = buildResolveInfo(/* permission= */ "",
                ApplicationInfo.FLAG_SYSTEM);

        // Act and assert
        assertThat(MultiClientInputMethodManagerService.resolveMultiClientImeService(
                asList(imeService))).isNull();
    }

    @Test
    public void testQueryInputMethod_IMEFound() {
        // Arrange
        ResolveInfo imeService = buildResolveInfo(android.Manifest.permission.BIND_INPUT_METHOD,
                ApplicationInfo.FLAG_SYSTEM);

        // Act and assert
        assertThat(MultiClientInputMethodManagerService.resolveMultiClientImeService(
                asList(imeService))).isSameInstanceAs(imeService);
    }

    private ResolveInfo buildResolveInfo(String permission, int flags) {
        ResolveInfo imeService = new ResolveInfo();
        imeService.serviceInfo = new ServiceInfo();
        imeService.serviceInfo.packageName = "com.android.server.inputmethod";
        imeService.serviceInfo.name = "someIMEService";
        imeService.serviceInfo.permission = permission;
        imeService.serviceInfo.applicationInfo = new ApplicationInfo();
        imeService.serviceInfo.applicationInfo.flags = flags;
        return imeService;
    }
}
