/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import android.app.ResourcesManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Tests for {@link ConfigurationHelper}
 *
 * <p>Build/Install/Run:
 *  atest FrameworksMockingCoreTests:ConfigurationHelperTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ConfigurationHelperTest {
    MockitoSession mMockitoSession;
    ResourcesManager mResourcesManager;

    @Before
    public void setUp() {
        mMockitoSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(ResourcesManager.class)
                .startMocking();
        doReturn(mock(ResourcesManager.class)).when(ResourcesManager::getInstance);
        mResourcesManager = ResourcesManager.getInstance();
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testShouldUpdateResources_NullConfig_ReturnsTrue() {
        assertThat(ConfigurationHelper.shouldUpdateResources(new Binder(), null /* config */,
                new Configuration(), new Configuration(), false /* displayChanged */,
                null /* configChanged */)).isTrue();
    }

    @Test
    public void testShouldUpdateResources_DisplayChanged_ReturnsTrue() {
        assertThat(ConfigurationHelper.shouldUpdateResources(new Binder(), new Configuration(),
                new Configuration(), new Configuration(), true /* displayChanged */,
                null /* configChanged */)).isTrue();
    }

    @Test
    public void testShouldUpdateResources_DifferentResources_ReturnsTrue() {
        doReturn(false).when(mResourcesManager).isSameResourcesOverrideConfig(any(), any());

        assertThat(ConfigurationHelper.shouldUpdateResources(new Binder(), new Configuration(),
                new Configuration(), new Configuration(), false /* displayChanged */,
                null /* configChanged */)).isTrue();
    }

    @Test
    public void testShouldUpdateResources_DifferentBounds_ReturnsTrue() {
        doReturn(true).when(mResourcesManager).isSameResourcesOverrideConfig(any(), any());

        final Configuration config = new Configuration();
        config.windowConfiguration.setBounds(new Rect(0, 0, 10, 10));
        config.windowConfiguration.setMaxBounds(new Rect(0, 0, 20, 20));

        final Configuration newConfig = new Configuration();
        newConfig.windowConfiguration.setBounds(new Rect(0, 0, 20, 20));
        newConfig.windowConfiguration.setMaxBounds(new Rect(0, 0, 20, 20));

        assertThat(ConfigurationHelper.shouldUpdateResources(new Binder(), config, newConfig,
                new Configuration(), false /* displayChanged */, null /* configChanged */))
                .isTrue();
    }

    @Test
    public void testShouldUpdateResources_SameConfig_ReturnsFalse() {
        doReturn(true).when(mResourcesManager).isSameResourcesOverrideConfig(any(), any());

        final Configuration config = new Configuration();
        final Configuration newConfig = new Configuration();

        assertThat(ConfigurationHelper.shouldUpdateResources(new Binder(), config, newConfig,
                new Configuration(), false /* displayChanged */, null /* configChanged */))
                .isFalse();
    }

    @Test
    public void testShouldUpdateResources_DifferentConfig_ReturnsTrue() {
        doReturn(true).when(mResourcesManager).isSameResourcesOverrideConfig(any(), any());

        final Configuration config = new Configuration();
        final Configuration newConfig = new Configuration();
        newConfig.setToDefaults();

        assertThat(ConfigurationHelper.shouldUpdateResources(new Binder(), config, newConfig,
                new Configuration(), false /* displayChanged */, null /* configChanged */))
                .isTrue();
    }

    @Test
    public void testShouldUpdateResources_DifferentNonPublicConfig_ReturnsTrue() {
        doReturn(true).when(mResourcesManager).isSameResourcesOverrideConfig(any(), any());

        final Configuration config = new Configuration();
        final Configuration newConfig = new Configuration();
        newConfig.windowConfiguration.setAppBounds(new Rect(0, 0, 10, 10));

        assertThat(ConfigurationHelper.shouldUpdateResources(new Binder(), config, newConfig,
                new Configuration(), false /* displayChanged */, null /* configChanged */))
                .isTrue();
    }

    @Test
    public void testShouldUpdateResources_OverrideConfigChanged_ReturnsFalse() {
        doReturn(true).when(mResourcesManager).isSameResourcesOverrideConfig(any(), any());

        final Configuration config = new Configuration();
        final Configuration newConfig = new Configuration();
        final boolean configChanged = true;

        assertThat(ConfigurationHelper.shouldUpdateResources(new Binder(), config, newConfig,
                new Configuration(), false /* displayChanged */, configChanged))
                .isEqualTo(configChanged);
    }
}
