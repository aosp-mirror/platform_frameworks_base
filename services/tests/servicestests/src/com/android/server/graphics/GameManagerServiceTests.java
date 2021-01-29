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

package com.android.server.graphics;

import static org.junit.Assert.assertEquals;

import android.graphics.GameManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class GameManagerServiceTests {

    private static final String TAG = "GameServiceTests";
    private static final String PACKAGE_NAME_0 = "com.android.app0";
    private static final String PACKAGE_NAME_1 = "com.android.app1";
    private static final int USER_ID_1 = 1001;
    private static final int USER_ID_2 = 1002;

    /**
     * By default game mode is not supported.
     */
    @Test
    public void testGameModeDefaultValue() {
        GameManagerService gameManagerService =
                new GameManagerService(InstrumentationRegistry.getContext());
        gameManagerService.onUserStarting(USER_ID_1);

        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(PACKAGE_NAME_0, USER_ID_1));
    }

    /**
     * Test the default behaviour for a nonexistent user.
     */
    @Test
    public void testDefaultValueForNonexistentUser() {
        GameManagerService gameManagerService =
                new GameManagerService(InstrumentationRegistry.getContext());
        gameManagerService.onUserStarting(USER_ID_1);

        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_STANDARD, USER_ID_2);
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_2));
    }

    /**
     * Test getter and setter of game modes.
     */
    @Test
    public void testGameMode() {
        GameManagerService gameManagerService =
                new GameManagerService(InstrumentationRegistry.getContext());
        gameManagerService.onUserStarting(USER_ID_1);

        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));
        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));
        gameManagerService.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_PERFORMANCE,
                USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(PACKAGE_NAME_1, USER_ID_1));
    }
}
