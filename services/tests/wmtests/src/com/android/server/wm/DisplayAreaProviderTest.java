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

package com.android.server.wm;

import static com.android.server.wm.testing.Assert.assertThrows;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest WmTests:DisplayAreaProviderTest
 */
@Presubmit
public class DisplayAreaProviderTest {

    @Test
    public void testFromResources_emptyProvider() {
        Assert.assertThat(DisplayAreaPolicy.Provider.fromResources(resourcesWithProvider("")),
                Matchers.instanceOf(DisplayAreaPolicy.DefaultProvider.class));
    }

    @Test
    public void testFromResources_nullProvider() {
        Assert.assertThat(DisplayAreaPolicy.Provider.fromResources(resourcesWithProvider(null)),
                Matchers.instanceOf(DisplayAreaPolicy.DefaultProvider.class));
    }

    @Test
    public void testFromResources_customProvider() {
        Assert.assertThat(DisplayAreaPolicy.Provider.fromResources(resourcesWithProvider(
                TestProvider.class.getName())), Matchers.instanceOf(TestProvider.class));
    }

    @Test
    public void testFromResources_badProvider_notImplementingProviderInterface() {
        assertThrows(IllegalStateException.class, () -> {
            DisplayAreaPolicy.Provider.fromResources(resourcesWithProvider(
                    Object.class.getName()));
        });
    }

    @Test
    public void testFromResources_badProvider_doesntExist() {
        assertThrows(IllegalStateException.class, () -> {
            DisplayAreaPolicy.Provider.fromResources(resourcesWithProvider(
                    "com.android.wmtests.nonexistent.Provider"));
        });
    }

    private static Resources resourcesWithProvider(String provider) {
        Resources mock = mock(Resources.class);
        when(mock.getString(
                com.android.internal.R.string.config_deviceSpecificDisplayAreaPolicyProvider))
                .thenReturn(provider);
        return mock;
    }

    static class TestProvider implements DisplayAreaPolicy.Provider {

        @Override
        public DisplayAreaPolicy instantiate(WindowManagerService wmService, DisplayContent content,
                RootDisplayArea root, DisplayArea.Tokens imeContainer) {
            throw new RuntimeException("test stub");
        }
    }
}
