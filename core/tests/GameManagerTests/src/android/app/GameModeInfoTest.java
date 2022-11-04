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
        int[] optedInGameModes = new int[]{GameManager.GAME_MODE_PERFORMANCE};
        GameModeInfo gameModeInfo = new GameModeInfo.Builder()
                .setActiveGameMode(activeGameMode)
                .setAvailableGameModes(availableGameModes)
                .setOptedInGameModes(optedInGameModes)
                .setDownscalingAllowed(true)
                .setFpsOverrideAllowed(false).build();

        assertArrayEquals(availableGameModes, gameModeInfo.getAvailableGameModes());
        assertArrayEquals(optedInGameModes, gameModeInfo.getOptedInGameModes());
        assertEquals(activeGameMode, gameModeInfo.getActiveGameMode());
        assertTrue(gameModeInfo.isDownscalingAllowed());
        assertFalse(gameModeInfo.isFpsOverrideAllowed());

        Parcel parcel = Parcel.obtain();
        gameModeInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        GameModeInfo newGameModeInfo = new GameModeInfo(parcel);
        assertEquals(gameModeInfo.getActiveGameMode(), newGameModeInfo.getActiveGameMode());
        assertArrayEquals(gameModeInfo.getAvailableGameModes(),
                newGameModeInfo.getAvailableGameModes());
        assertArrayEquals(gameModeInfo.getOptedInGameModes(),
                newGameModeInfo.getOptedInGameModes());
        assertTrue(newGameModeInfo.isDownscalingAllowed());
        assertFalse(newGameModeInfo.isFpsOverrideAllowed());
    }
}
