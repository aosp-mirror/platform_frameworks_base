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

package com.android.server.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.GameManager;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.util.AtomicFile;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.app.GameManagerService.GamePackageConfiguration;
import com.android.server.app.GameManagerService.GamePackageConfiguration.GameModeConfiguration;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class GameManagerServiceSettingsTests {

    private static final String TAG = "GameServiceSettingsTests";
    private static final String PACKAGE_NAME_1 = "com.android.app1";
    private static final String PACKAGE_NAME_2 = "com.android.app2";
    private static final String PACKAGE_NAME_3 = "com.android.app3";
    private static final String PACKAGE_NAME_4 = "com.android.app4";


    private void writeFile(File file, byte[] data) {
        file.mkdirs();
        try {
            AtomicFile aFile = new AtomicFile(file);
            FileOutputStream fos = aFile.startWrite();
            fos.write(data);
            aFile.finishWrite(fos);
        } catch (IOException ioe) {
            Log.e(TAG, "Cannot write file " + file.getPath());
        }
    }

    private void writeGameServiceXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(),
                        "system/game-manager-service.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                        + "<packages>"
                        + "\n" // app1: no package config setting
                        + "\n" // app2: performance mode is selected with override
                        + "  <package name=\"com.android.app2\" gameMode=\"2\">\n"
                        + "     <gameModeConfig gameMode=\"2\" scaling=\"0.99\" "
                        + "useAngle=\"true\" fps=\"90\" loadingBoost=\"123\"></gameModeConfig>\n"
                        + "     <gameModeConfig gameMode=\"3\"></gameModeConfig>\n"
                        + "  </package>"
                        + "\n" // app3: only battery mode is selected
                        + "  <package name=\"com.android.app3\" gameMode=\"3\">\n"
                        + "  </package>"
                        + "\n" // app4: no game mode selected but custom game mode config
                        + "  <package name=\"com.android.app4\">\n"
                        + "     <gameModeConfig gameMode=\"4\" scaling=\"0.4\" "
                        + "fps=\"30\"></gameModeConfig>\n"
                        + "  </package>"
                        + "\n"
                        + "</packages>\n").getBytes());
    }

    private void deleteSystemFolder() {
        File systemFolder = new File(InstrumentationRegistry.getContext().getFilesDir(), "system");
        deleteFolder(systemFolder);
    }

    private static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        folder.delete();
    }

    private void writeOldFiles() {
        deleteSystemFolder();
        writeGameServiceXml();
    }

    @After
    public void tearDown() throws Exception {
        deleteFolder(InstrumentationRegistry.getTargetContext().getFilesDir());
    }

    @Test
    public void testReadGameServiceSettings() {
        writeOldFiles();
        final Context context = InstrumentationRegistry.getContext();
        GameManagerSettings settings = new GameManagerSettings(context.getFilesDir());
        assertTrue(settings.readPersistentDataLocked());

        // test game modes
        assertEquals(GameManager.GAME_MODE_STANDARD, settings.getGameModeLocked(PACKAGE_NAME_1));
        assertEquals(GameManager.GAME_MODE_PERFORMANCE, settings.getGameModeLocked(PACKAGE_NAME_2));
        assertEquals(GameManager.GAME_MODE_BATTERY, settings.getGameModeLocked(PACKAGE_NAME_3));
        assertEquals(GameManager.GAME_MODE_STANDARD, settings.getGameModeLocked(PACKAGE_NAME_4));

        // test game mode configs
        assertNull(settings.getConfigOverrideLocked(PACKAGE_NAME_1));
        assertNull(settings.getConfigOverrideLocked(PACKAGE_NAME_3));
        GamePackageConfiguration config = settings.getConfigOverrideLocked(PACKAGE_NAME_2);
        assertNotNull(config);

        assertNull(config.getGameModeConfiguration(GameManager.GAME_MODE_STANDARD));
        final GameModeConfiguration performanceConfig = config.getGameModeConfiguration(
                GameManager.GAME_MODE_PERFORMANCE);
        assertNotNull(performanceConfig);
        assertEquals(performanceConfig.getScaling(), 0.99, 0.01f);
        assertEquals(performanceConfig.getLoadingBoostDuration(), 123);
        assertEquals(performanceConfig.getFpsStr(), "90");
        assertTrue(performanceConfig.getUseAngle());
        final GameModeConfiguration batteryConfig = config.getGameModeConfiguration(
                GameManager.GAME_MODE_BATTERY);
        assertNotNull(batteryConfig);
        assertEquals(batteryConfig.getScaling(), GameModeConfiguration.DEFAULT_SCALING, 0.01f);
        assertEquals(batteryConfig.getLoadingBoostDuration(),
                GameModeConfiguration.DEFAULT_LOADING_BOOST_DURATION);
        assertEquals(batteryConfig.getFpsStr(), GameModeConfiguration.DEFAULT_FPS);
        assertFalse(batteryConfig.getUseAngle());

        config = settings.getConfigOverrideLocked(PACKAGE_NAME_4);
        assertNotNull(config);
        GameModeConfiguration customConfig = config.getGameModeConfiguration(
                GameManager.GAME_MODE_CUSTOM);
        assertNotNull(customConfig);
        assertEquals(customConfig.getScaling(), 0.4f, 0.1f);
        assertEquals(customConfig.getFps(), 30);
    }

    @Test
    public void testReadGameServiceSettings_invalidConfigAttributes() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(),
                        "system/game-manager-service.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                        + "<packages>\n"
                        + "  <package name=\"com.android.app1\" gameMode=\"1\">\n"
                        + "     <gameModeConfig gameMode=\"3\" scaling=\"invalid\" "
                        + "useAngle=\"invalid\" fps=\"invalid\" "
                        + "loadingBoost=\"invalid\"></gameModeConfig>\n"
                        + "  </package>\n"
                        + "</packages>\n").getBytes());
        final Context context = InstrumentationRegistry.getContext();
        GameManagerSettings settings = new GameManagerSettings(context.getFilesDir());
        assertTrue(settings.readPersistentDataLocked());

        final GamePackageConfiguration config = settings.getConfigOverrideLocked(PACKAGE_NAME_1);
        assertNotNull(config);
        final GameModeConfiguration batteryConfig = config.getGameModeConfiguration(
                GameManager.GAME_MODE_BATTERY);
        assertNotNull(batteryConfig);
        assertEquals(batteryConfig.getScaling(), GameModeConfiguration.DEFAULT_SCALING, 0.01f);
        assertEquals(batteryConfig.getLoadingBoostDuration(),
                GameModeConfiguration.DEFAULT_LOADING_BOOST_DURATION);
        assertEquals(batteryConfig.getFpsStr(), "invalid");
        assertFalse(batteryConfig.getUseAngle());
    }

    @Test
    public void testReadGameServiceSettings_invalidTags() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(),
                        "system/game-manager-service.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                        + "<packages>"
                        + "\n" // missing package name
                        + "  <package gameMode=\"1\">\n"
                        + "  </package>"
                        + "\n" // app2 with unknown sub element
                        + "  <package name=\"com.android.app2\" gameMode=\"2\">\n"
                        + "     <unknown></unknown>"
                        + "     <gameModeConfig gameMode=\"3\" fps=\"90\"></gameModeConfig>\n"
                        + "     foo bar"
                        + "  </package>"
                        + "\n" // unknown package element
                        + "  <unknownTag></unknownTag>\n"
                        + "    foo bar"
                        + "\n" // app3 after unknown element
                        + "  <package name=\"com.android.app3\" gameMode=\"3\">\n"
                        + "  </package>\n"
                        + "</packages>\n").getBytes());
        final Context context = InstrumentationRegistry.getContext();
        GameManagerSettings settings = new GameManagerSettings(context.getFilesDir());
        assertTrue(settings.readPersistentDataLocked());
        assertEquals(1, settings.getGameModeLocked(PACKAGE_NAME_1));
        assertEquals(2, settings.getGameModeLocked(PACKAGE_NAME_2));
        assertEquals(3, settings.getGameModeLocked(PACKAGE_NAME_3));

        final GamePackageConfiguration config = settings.getConfigOverrideLocked(PACKAGE_NAME_2);
        assertNotNull(config);
        final GameModeConfiguration batteryConfig = config.getGameModeConfiguration(
                GameManager.GAME_MODE_BATTERY);
        assertNotNull(batteryConfig);
        assertEquals(batteryConfig.getFpsStr(), "90");
    }


    @Test
    public void testWriteGameServiceSettings() {
        final Context context = InstrumentationRegistry.getContext();
        GameManagerSettings settings = new GameManagerSettings(context.getFilesDir());

        // set package settings and write out to file
        settings.setGameModeLocked(PACKAGE_NAME_1, GameManager.GAME_MODE_BATTERY);
        settings.setGameModeLocked(PACKAGE_NAME_2, GameManager.GAME_MODE_PERFORMANCE);
        settings.setGameModeLocked(PACKAGE_NAME_3, GameManager.GAME_MODE_STANDARD);

        // set config for app2
        GamePackageConfiguration config = new GamePackageConfiguration(PACKAGE_NAME_2);
        GameModeConfiguration performanceConfig = config.getOrAddDefaultGameModeConfiguration(
                GameManager.GAME_MODE_PERFORMANCE);
        performanceConfig.setLoadingBoostDuration(321);
        performanceConfig.setScaling(0.66f);
        performanceConfig.setUseAngle(true);
        performanceConfig.setFpsStr("60");
        GameModeConfiguration batteryConfig = config.getOrAddDefaultGameModeConfiguration(
                GameManager.GAME_MODE_BATTERY);
        batteryConfig.setScaling(0.77f);
        settings.setConfigOverrideLocked(PACKAGE_NAME_2, config);

        // set config for app4
        config = new GamePackageConfiguration(PACKAGE_NAME_4);
        GameModeConfiguration customConfig = config.getOrAddDefaultGameModeConfiguration(
                GameManager.GAME_MODE_CUSTOM);
        customConfig.setScaling(0.4f);
        customConfig.setFpsStr("30");
        settings.setConfigOverrideLocked(PACKAGE_NAME_4, config);

        settings.writePersistentDataLocked();

        // clear the settings in memory
        settings.removeGameLocked(PACKAGE_NAME_1);
        settings.removeGameLocked(PACKAGE_NAME_2);
        settings.removeGameLocked(PACKAGE_NAME_3);
        settings.removeGameLocked(PACKAGE_NAME_4);

        // read back in and verify
        assertTrue(settings.readPersistentDataLocked());
        assertEquals(3, settings.getGameModeLocked(PACKAGE_NAME_1));
        assertEquals(2, settings.getGameModeLocked(PACKAGE_NAME_2));
        assertEquals(1, settings.getGameModeLocked(PACKAGE_NAME_3));
        assertEquals(1, settings.getGameModeLocked(PACKAGE_NAME_4));

        config = settings.getConfigOverrideLocked(PACKAGE_NAME_1);
        assertNull(config);
        config = settings.getConfigOverrideLocked(PACKAGE_NAME_2);
        assertNotNull(config);
        batteryConfig = config.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY);
        assertNotNull(batteryConfig);
        assertEquals(batteryConfig.getScaling(), 0.77f, 0.01f);
        assertEquals(batteryConfig.getLoadingBoostDuration(),
                GameModeConfiguration.DEFAULT_LOADING_BOOST_DURATION);
        assertEquals(batteryConfig.getFpsStr(), GameModeConfiguration.DEFAULT_FPS);
        assertFalse(batteryConfig.getUseAngle());

        performanceConfig = config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE);
        assertNotNull(performanceConfig);
        assertEquals(performanceConfig.getScaling(), 0.66f, 0.01f);
        assertEquals(performanceConfig.getLoadingBoostDuration(), 321);
        assertEquals(performanceConfig.getFpsStr(), "60");
        assertTrue(performanceConfig.getUseAngle());

        config = settings.getConfigOverrideLocked(PACKAGE_NAME_4);
        assertNotNull(config);
        customConfig = config.getGameModeConfiguration(GameManager.GAME_MODE_CUSTOM);
        assertNotNull(customConfig);
        assertEquals(customConfig.getScaling(), 0.4f, 0.1f);
        assertEquals(customConfig.getFps(), 30);
        assertNull(config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
        assertNull(config.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
    }
}
