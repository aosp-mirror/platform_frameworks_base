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

package com.android.systemui.flags;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.FlagReaderPlugin;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class FeatureFlagsTest extends SysuiTestCase {

    @Mock FeatureFlagReader mFeatureFlagReader;

    private FeatureFlags mFeatureFlags;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mFeatureFlags = new FeatureFlags(mFeatureFlagReader, getContext());
    }

    @Test
    public void testAddListener() {
        Flag<?> flag = new BooleanFlag(1);
        mFeatureFlags.addFlag(flag);

        // Assert and capture that a plugin listener was added.
        ArgumentCaptor<FlagReaderPlugin.Listener> pluginListenerCaptor =
                ArgumentCaptor.forClass(FlagReaderPlugin.Listener.class);

        verify(mFeatureFlagReader).addListener(pluginListenerCaptor.capture());
        FlagReaderPlugin.Listener pluginListener = pluginListenerCaptor.getValue();

        // Signal a change. No listeners, so no real effect.
        pluginListener.onFlagChanged(flag.getId());

        // Add a listener for the flag
        final Flag<?>[] changedFlag = {null};
        FeatureFlags.Listener listener = f -> changedFlag[0] = f;
        mFeatureFlags.addFlagListener(flag, listener);

        // No changes seen yet.
        assertThat(changedFlag[0]).isNull();

        // Signal a change.
        pluginListener.onFlagChanged(flag.getId());

        // Assert that the change was for the correct flag.
        assertThat(changedFlag[0]).isEqualTo(flag);
    }

    @Test
    public void testRemoveListener() {
        Flag<?> flag = new BooleanFlag(1);
        mFeatureFlags.addFlag(flag);

        // Assert and capture that a plugin listener was added.
        ArgumentCaptor<FlagReaderPlugin.Listener> pluginListenerCaptor =
                ArgumentCaptor.forClass(FlagReaderPlugin.Listener.class);

        verify(mFeatureFlagReader).addListener(pluginListenerCaptor.capture());
        FlagReaderPlugin.Listener pluginListener = pluginListenerCaptor.getValue();

        // Add a listener for the flag
        final Flag<?>[] changedFlag = {null};
        FeatureFlags.Listener listener = f -> changedFlag[0] = f;
        mFeatureFlags.addFlagListener(flag, listener);

        // Signal a change.
        pluginListener.onFlagChanged(flag.getId());

        // Assert that the change was for the correct flag.
        assertThat(changedFlag[0]).isEqualTo(flag);

        changedFlag[0] = null;

        // Now remove the listener.
        mFeatureFlags.removeFlagListener(flag, listener);
        // Signal a change.
        pluginListener.onFlagChanged(flag.getId());
        // Assert that the change was not triggered
        assertThat(changedFlag[0]).isNull();

    }
}
