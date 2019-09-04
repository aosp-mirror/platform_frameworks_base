/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.om.hosttest.update_overlay_test;

import static org.junit.Assert.assertEquals;

import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UpdateOverlayTest {
    private Resources mResources;

    @Before
    public void setUp() throws Exception {
        final Configuration defaultLocaleConfiguration = new Configuration();
        defaultLocaleConfiguration.setLocale(Locale.US);
        mResources = InstrumentationRegistry
                .getInstrumentation()
                .getContext()
                .createConfigurationContext(defaultLocaleConfiguration)
                .getResources();
    }

    @Test
    public void expectAppResource() throws Exception {
        assertEquals("App Resource", mResources.getString(R.string.app_resource));
    }

    @Test
    public void expectAppOverlayV1Resource() throws Exception {
        assertEquals("App Resource Overlay V1", mResources.getString(R.string.app_resource));
    }

    @Test
    public void expectAppOverlayV2Resource() throws Exception {
        assertEquals("App Resource Overlay V2", mResources.getString(R.string.app_resource));
    }

    @Test
    public void expectFrameworkResource() throws Exception {
        assertEquals("OK", mResources.getString(android.R.string.ok));
    }

    @Test
    public void expectFrameworkOverlayV1Resource() throws Exception {
        assertEquals("Framework Overlay V1", mResources.getString(android.R.string.ok));
    }

    @Test
    public void expectFrameworkOverlayV2Resource() throws Exception {
        assertEquals("Framework Overlay V2", mResources.getString(android.R.string.ok));
    }
}
