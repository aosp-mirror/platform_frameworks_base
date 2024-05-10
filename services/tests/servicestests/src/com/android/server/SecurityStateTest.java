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

package com.android.server;

import static android.os.SecurityStateManager.KEY_KERNEL_VERSION;
import static android.os.SecurityStateManager.KEY_SYSTEM_SPL;
import static android.os.SecurityStateManager.KEY_VENDOR_SPL;

import static com.android.server.SecurityStateManagerService.KERNEL_RELEASE_PATTERN;
import static com.android.server.SecurityStateManagerService.VENDOR_SECURITY_PATCH_PROPERTY_KEY;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.VintfRuntimeInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.regex.Matcher;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SecurityStateTest {
    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private Context mMockContext;

    @Mock
    private PackageManager mMockPackageManager;

    @Mock
    private Resources mMockResources;

    private static final String DEFAULT_MODULE_METADATA_PROVIDER = "com.android.modulemetadata";
    private static final String DEFAULT_MODULE_METADATA_PROVIDER_VERSION = "2023-12-01";
    private static final String DEFAULT_SECURITY_STATE_PACKAGE = "com.google.android.gms";
    private static final String DEFAULT_SECURITY_STATE_PACKAGE_VERSION = "2023-12-05";
    private static final String[] SECURITY_STATE_PACKAGES =
            new String[]{DEFAULT_SECURITY_STATE_PACKAGE};

    @Before
    public void setUp() throws Exception {
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getString(R.string.config_defaultModuleMetadataProvider))
                .thenReturn(DEFAULT_MODULE_METADATA_PROVIDER);
        when(mMockPackageManager.getPackageInfo(anyString(), anyInt()))
                .thenReturn(new PackageInfo());
        PackageInfo moduleMetadataPackageInfo = new PackageInfo();
        moduleMetadataPackageInfo.versionName = DEFAULT_MODULE_METADATA_PROVIDER_VERSION;
        when(mMockPackageManager.getPackageInfo(DEFAULT_MODULE_METADATA_PROVIDER, 0))
                .thenReturn(moduleMetadataPackageInfo);
        PackageInfo securityStatePackageInfo = new PackageInfo();
        securityStatePackageInfo.versionName = DEFAULT_SECURITY_STATE_PACKAGE_VERSION;
        when(mMockPackageManager.getPackageInfo(DEFAULT_SECURITY_STATE_PACKAGE, 0))
                .thenReturn(securityStatePackageInfo);
        when(mMockResources.getStringArray(R.array.config_securityStatePackages))
                .thenReturn(SECURITY_STATE_PACKAGES);
    }

    @Test
    public void testGetGlobalSecurityState_returnsBundle() {
        SecurityStateManagerService securityState = new SecurityStateManagerService(mMockContext);

        Bundle bundle = securityState.getGlobalSecurityState();

        assertEquals(bundle.getString(KEY_SYSTEM_SPL), Build.VERSION.SECURITY_PATCH);
        assertEquals(bundle.getString(KEY_VENDOR_SPL),
                SystemProperties.get(VENDOR_SECURITY_PATCH_PROPERTY_KEY, ""));
        Matcher matcher = KERNEL_RELEASE_PATTERN.matcher(VintfRuntimeInfo.getKernelVersion());
        String kernelVersion = "";
        if (matcher.matches()) {
            kernelVersion = matcher.group(1);
        }
        assertEquals(bundle.getString(KEY_KERNEL_VERSION), kernelVersion);
        assertEquals(bundle.getString(DEFAULT_MODULE_METADATA_PROVIDER),
                DEFAULT_MODULE_METADATA_PROVIDER_VERSION);
        assertEquals(bundle.getString(DEFAULT_SECURITY_STATE_PACKAGE),
                DEFAULT_SECURITY_STATE_PACKAGE_VERSION);
    }
}
