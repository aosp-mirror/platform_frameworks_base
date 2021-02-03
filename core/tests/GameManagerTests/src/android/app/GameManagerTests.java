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

package android.app;

import static junit.framework.Assert.assertEquals;

import android.app.GameManager.GameMode;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link android.app.GameManager}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public final class GameManagerTests {
    private static final String PACKAGE_NAME_0 = "com.android.app0";
    private static final String PACKAGE_NAME_1 = "com.android.app1";

    private TestGameManagerService mService;
    private GameManager mGameManager;

    @Before
    public void setUp() {
        mService = new TestGameManagerService();
        mGameManager = new GameManager(
                InstrumentationRegistry.getContext(), mService);
    }

    @Test
    public void testGameModeGetterSetter() {
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                mGameManager.getGameMode(PACKAGE_NAME_0));

        mGameManager.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_STANDARD);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                mGameManager.getGameMode(PACKAGE_NAME_1));

        mGameManager.setGameMode(PACKAGE_NAME_1, GameManager.GAME_MODE_PERFORMANCE);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                mGameManager.getGameMode(PACKAGE_NAME_1));
    }

    private final class TestGameManagerService extends IGameManagerService.Stub {
        private final ArrayMap<Pair<String, Integer>, Integer> mGameModes = new ArrayMap<>();

        @Override
        public @GameMode int getGameMode(String packageName, int userId) {
            final Pair key = Pair.create(packageName, userId);
            if (mGameModes.containsKey(key)) {
                return mGameModes.get(key);
            }
            return GameManager.GAME_MODE_UNSUPPORTED;
        }

        @Override
        public void setGameMode(String packageName, @GameMode int gameMode, int userId) {
            mGameModes.put(Pair.create(packageName, userId), gameMode);
        }
    }
}
