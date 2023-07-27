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

package com.android.settingslib.media;

import static com.android.settingslib.media.flags.Flags.FLAG_USE_MEDIA_ROUTER2_FOR_INFO_MEDIA_MANAGER;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.app.UiAutomation;
import android.content.Context;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InfoMediaManagerIntegTest {

    private static final String FAKE_PACKAGE = "FAKE_PACKAGE";

    private Context mContext;
    private UiAutomation mUiAutomation;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mUiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL);
    }

    @After
    public void tearDown() {
        mUiAutomation.dropShellPermissionIdentity();
    }

    @Test
    @RequiresFlagsEnabled(FLAG_USE_MEDIA_ROUTER2_FOR_INFO_MEDIA_MANAGER)
    public void createInstance_withMR2FlagOn_returnsRouterInfoMediaManager() {
        InfoMediaManager manager =
                InfoMediaManager.createInstance(mContext, mContext.getPackageName(), null, null);
        assertThat(manager).isInstanceOf(RouterInfoMediaManager.class);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_USE_MEDIA_ROUTER2_FOR_INFO_MEDIA_MANAGER)
    public void createInstance_withMR2FlagOn_withFakePackage_returnsNoOpInfoMediaManager() {
        InfoMediaManager manager =
                InfoMediaManager.createInstance(mContext, FAKE_PACKAGE, null, null);
        assertThat(manager).isInstanceOf(NoOpInfoMediaManager.class);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_USE_MEDIA_ROUTER2_FOR_INFO_MEDIA_MANAGER)
    public void createInstance_withMR2FlagOn_withNullPackage_returnsRouterInfoMediaManager() {
        InfoMediaManager manager = InfoMediaManager.createInstance(mContext, null, null, null);
        assertThat(manager).isInstanceOf(RouterInfoMediaManager.class);
    }

    @Test
    @RequiresFlagsDisabled(FLAG_USE_MEDIA_ROUTER2_FOR_INFO_MEDIA_MANAGER)
    public void createInstance_withMR2FlagOff_returnsManagerInfoMediaManager() {
        InfoMediaManager manager =
                InfoMediaManager.createInstance(mContext, mContext.getPackageName(), null, null);
        assertThat(manager).isInstanceOf(ManagerInfoMediaManager.class);
    }
}
