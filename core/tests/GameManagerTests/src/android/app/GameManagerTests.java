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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static junit.framework.Assert.assertEquals;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

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
@Presubmit
public final class GameManagerTests {
    protected Context mContext;
    private GameManager mGameManager;
    private String mPackageName;

    @Before
    public void setUp() {
        mContext = getInstrumentation().getContext();
        mGameManager = mContext.getSystemService(GameManager.class);
        mPackageName = mContext.getPackageName();

        // Reset the Game Mode for the test app, since it persists across invocations.
        mGameManager.setGameMode(mPackageName, GameManager.GAME_MODE_UNSUPPORTED);
    }

    @Test
    public void testPublicApiGameModeGetterSetter() {
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                mGameManager.getGameMode());

        mGameManager.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                mGameManager.getGameMode());

        mGameManager.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                mGameManager.getGameMode());
    }

    @Test
    public void testPrivilegedGameModeGetterSetter() {
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                mGameManager.getGameMode(mPackageName));

        mGameManager.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                mGameManager.getGameMode(mPackageName));

        mGameManager.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                mGameManager.getGameMode(mPackageName));
    }
}
