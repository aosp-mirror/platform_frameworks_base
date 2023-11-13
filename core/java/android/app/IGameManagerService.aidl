/*
** Copyright 2021, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.app;

import android.app.GameModeConfiguration;
import android.app.GameModeInfo;
import android.app.GameState;
import android.app.IGameModeListener;
import android.app.IGameStateListener;

/**
 * @hide
 */
interface IGameManagerService {
    int getGameMode(String packageName, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    void setGameMode(String packageName, int gameMode, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    int[] getAvailableGameModes(String packageName, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    boolean isAngleEnabled(String packageName, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    void notifyGraphicsEnvironmentSetup(String packageName, int userId);
    void setGameState(String packageName, in GameState gameState, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    GameModeInfo getGameModeInfo(String packageName, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.SET_GAME_SERVICE)")
    void setGameServiceProvider(String packageName);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    void updateResolutionScalingFactor(String packageName, int gameMode, float scalingFactor, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    float getResolutionScalingFactor(String packageName, int gameMode, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    void updateCustomGameModeConfiguration(String packageName, in GameModeConfiguration gameModeConfig, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    void addGameModeListener(IGameModeListener gameModeListener);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_MODE)")
    void removeGameModeListener(IGameModeListener gameModeListener);
    void addGameStateListener(IGameStateListener gameStateListener);
    void removeGameStateListener(IGameStateListener gameStateListener);
    @EnforcePermission("MANAGE_GAME_MODE")
    void toggleGameDefaultFrameRate(boolean isEnabled);
}
