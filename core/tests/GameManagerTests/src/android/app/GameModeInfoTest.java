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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link android.app.GameModeInfo}.
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class GameModeInfoTest {
    @Test
    public void testParcelable() {
        int activeGameMode = GameManager.GAME_MODE_PERFORMANCE;
        int[] availableGameModes =
                new int[]{GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_PERFORMANCE,
                        GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_CUSTOM};
        int[] overriddenGameModes = new int[]{GameManager.GAME_MODE_PERFORMANCE};
        GameModeConfiguration batteryConfig = new GameModeConfiguration
                .Builder().setFpsOverride(40).setScalingFactor(0.5f).build();
        GameModeConfiguration performanceConfig = new GameModeConfiguration
                .Builder().setFpsOverride(90).setScalingFactor(0.9f).build();
        GameModeInfo gameModeInfo = new GameModeInfo.Builder()
                .setActiveGameMode(activeGameMode)
                .setAvailableGameModes(availableGameModes)
                .setOverriddenGameModes(overriddenGameModes)
                .setDownscalingAllowed(true)
                .setFpsOverrideAllowed(false)
                .setGameModeConfiguration(GameManager.GAME_MODE_BATTERY, batteryConfig)
                .setGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE, performanceConfig)
                .build();

        assertArrayEquals(availableGameModes, gameModeInfo.getAvailableGameModes());
        assertArrayEquals(overriddenGameModes, gameModeInfo.getOverriddenGameModes());
        assertEquals(activeGameMode, gameModeInfo.getActiveGameMode());
        assertTrue(gameModeInfo.isDownscalingAllowed());
        assertFalse(gameModeInfo.isFpsOverrideAllowed());
        assertEquals(performanceConfig,
                gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
        assertEquals(batteryConfig,
                gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));

        Parcel parcel = Parcel.obtain();
        gameModeInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        GameModeInfo newGameModeInfo = new GameModeInfo(parcel);
        assertEquals(gameModeInfo.getActiveGameMode(), newGameModeInfo.getActiveGameMode());
        assertArrayEquals(gameModeInfo.getAvailableGameModes(),
                newGameModeInfo.getAvailableGameModes());
        assertArrayEquals(gameModeInfo.getOverriddenGameModes(),
                newGameModeInfo.getOverriddenGameModes());
        assertTrue(newGameModeInfo.isDownscalingAllowed());
        assertFalse(newGameModeInfo.isFpsOverrideAllowed());
        assertEquals(performanceConfig,
                newGameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
        assertEquals(batteryConfig,
                newGameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
    }
}
