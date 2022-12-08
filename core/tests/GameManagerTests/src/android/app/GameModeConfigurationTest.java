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

package android.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class GameModeConfigurationTest {
    @Test
    public void testEqualsAndHashCode() {
        GameModeConfiguration config = new GameModeConfiguration.Builder()
                .setScalingFactor(0.5f).setFpsOverride(10).build();
        assertTrue(config.equals(config));

        GameModeConfiguration config1 = new GameModeConfiguration.Builder()
                .setScalingFactor(0.5f).setFpsOverride(10).build();
        assertTrue(config.equals(config1));
        assertEquals(config.hashCode(), config1.hashCode());

        GameModeConfiguration config2 = new GameModeConfiguration.Builder()
                .setScalingFactor(0.5f).build();
        assertFalse(config.equals(config2));
        assertNotEquals(config.hashCode(), config2.hashCode());

        GameModeConfiguration config3 = new GameModeConfiguration.Builder()
                .setFpsOverride(10).build();
        assertFalse(config.equals(config3));
        assertNotEquals(config.hashCode(), config3.hashCode());
        assertFalse(config2.equals(config3));
        assertNotEquals(config2.hashCode(), config3.hashCode());

        GameModeConfiguration config4 = new GameModeConfiguration.Builder()
                .setScalingFactor(0.2f).setFpsOverride(10).build();
        assertFalse(config.equals(config4));
        assertNotEquals(config.hashCode(), config4.hashCode());

        GameModeConfiguration config5 = new GameModeConfiguration.Builder()
                .setScalingFactor(0.5f).setFpsOverride(30).build();
        assertFalse(config.equals(config5));
        assertNotEquals(config.hashCode(), config5.hashCode());

        GameModeConfiguration config6 = new GameModeConfiguration.Builder()
                .build();
        assertFalse(config.equals(config6));
        assertNotEquals(config.hashCode(), config6.hashCode());
        assertFalse(config2.equals(config6));
        assertNotEquals(config2.hashCode(), config6.hashCode());
        assertFalse(config3.equals(config6));
        assertNotEquals(config3.hashCode(), config6.hashCode());
    }

    @Test
    public void testBuilderConstructor() {
        GameModeConfiguration config = new GameModeConfiguration
                .Builder().setFpsOverride(40).setScalingFactor(0.5f).build();
        GameModeConfiguration newConfig = new GameModeConfiguration.Builder(config).build();
        assertEquals(config, newConfig);
    }

    @Test
    public void testGetters() {
        GameModeConfiguration config = new GameModeConfiguration.Builder()
                .setScalingFactor(0.5f).setFpsOverride(10).build();
        assertEquals(0.5f, config.getScalingFactor(), 0.01f);
        assertEquals(10, config.getFpsOverride());
    }
}
