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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.GameManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class GameManagerServiceTests {
    @Mock MockContext mMockContext;
    private static final String TAG = "GameServiceTests";
    private static final String PACKAGE_NAME_INVALID = "com.android.app";
    private static final int USER_ID_1 = 1001;
    private static final int USER_ID_2 = 1002;

    private MockitoSession mMockingSession;
    private String mPackageName;
    @Mock
    private PackageManager mMockPackageManager;

    // Stolen from ConnectivityServiceTest.MockContext
    class MockContext extends ContextWrapper {
        private static final String TAG = "MockContext";

        // Map of permission name -> PermissionManager.Permission_{GRANTED|DENIED} constant
        private final HashMap<String, Integer> mMockedPermissions = new HashMap<>();

        MockContext(Context base) {
            super(base);
        }

        /**
         * Mock checks for the specified permission, and have them behave as per {@code granted}.
         *
         * <p>Passing null reverts to default behavior, which does a real permission check on the
         * test package.
         *
         * @param granted One of {@link PackageManager#PERMISSION_GRANTED} or
         *                {@link PackageManager#PERMISSION_DENIED}.
         */
        public void setPermission(String permission, Integer granted) {
            mMockedPermissions.put(permission, granted);
        }

        private int checkMockedPermission(String permission, Supplier<Integer> ifAbsent) {
            final Integer granted = mMockedPermissions.get(permission);
            return granted != null ? granted : ifAbsent.get();
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return checkMockedPermission(
                    permission, () -> super.checkPermission(permission, pid, uid));
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return checkMockedPermission(
                    permission, () -> super.checkCallingOrSelfPermission(permission));
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            final Integer granted = mMockedPermissions.get(permission);
            if (granted == null) {
                super.enforceCallingOrSelfPermission(permission, message);
                return;
            }

            if (!granted.equals(PackageManager.PERMISSION_GRANTED)) {
                throw new SecurityException("[Test] permission denied: " + permission);
            }
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockPackageManager;
        }
    }

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(DeviceConfig.class)
                .strictness(Strictness.WARN)
                .startMocking();
        mMockContext = new MockContext(InstrumentationRegistry.getContext());
        mPackageName = mMockContext.getPackageName();
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.category = ApplicationInfo.CATEGORY_GAME;
        final PackageInfo pi = new PackageInfo();
        pi.packageName = mPackageName;
        final List<PackageInfo> packages = new ArrayList<>();
        packages.add(pi);
        when(mMockPackageManager.getInstalledPackages(anyInt())).thenReturn(packages);
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    @After
    public void tearDown() throws Exception {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void mockModifyGameModeGranted() {
        mMockContext.setPermission(Manifest.permission.MANAGE_GAME_MODE,
                PackageManager.PERMISSION_GRANTED);
    }

    private void mockModifyGameModeDenied() {
        mMockContext.setPermission(Manifest.permission.MANAGE_GAME_MODE,
                PackageManager.PERMISSION_DENIED);
    }

    private void mockDeviceConfigDefault() {
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder(
                DeviceConfig.NAMESPACE_GAME_OVERLAY).setString(mPackageName, "").build();
        when(DeviceConfig.getProperties(anyString(), anyString()))
                .thenReturn(properties);
    }

    private void mockDeviceConfigNone() {
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder(
                DeviceConfig.NAMESPACE_GAME_OVERLAY).build();
        when(DeviceConfig.getProperties(anyString(), anyString()))
                .thenReturn(properties);
    }

    private void mockDeviceConfigPerformance() {
        String configString = "mode=2,downscaleFactor=0.5";
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder(
                DeviceConfig.NAMESPACE_GAME_OVERLAY).setString(mPackageName, configString).build();
        when(DeviceConfig.getProperties(anyString(), anyString()))
                .thenReturn(properties);
    }

    private void mockDeviceConfigBattery() {
        String configString = "mode=3,downscaleFactor=0.7";
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder(
                DeviceConfig.NAMESPACE_GAME_OVERLAY).setString(mPackageName, configString).build();
        when(DeviceConfig.getProperties(anyString(), anyString()))
                .thenReturn(properties);
    }

    private void mockDeviceConfigAll() {
        String configString = "mode=3,downscaleFactor=0.7:mode=2,downscaleFactor=0.5";
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder(
                DeviceConfig.NAMESPACE_GAME_OVERLAY).setString(mPackageName, configString).build();
        when(DeviceConfig.getProperties(anyString(), anyString()))
                .thenReturn(properties);
    }

    private void mockDeviceConfigInvalid() {
        String configString = "mode=2,downscaleFactor=0.55";
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder(
                DeviceConfig.NAMESPACE_GAME_OVERLAY).setString(mPackageName, configString).build();
        when(DeviceConfig.getProperties(anyString(), anyString()))
                .thenReturn(properties);
    }

    private void mockDeviceConfigMalformed() {
        String configString = "adsljckv=nin3rn9hn1231245:8795tq=21ewuydg";
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder(
                DeviceConfig.NAMESPACE_GAME_OVERLAY).setString(mPackageName, configString).build();
        when(DeviceConfig.getProperties(anyString(), anyString()))
                .thenReturn(properties);
    }

    /**
     * By default game mode is not supported.
     */
    @Test
    public void testGameModeDefaultValue() {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        mockModifyGameModeGranted();

        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Test the default behaviour for a nonexistent user.
     */
    @Test
    public void testDefaultValueForNonexistentUser() {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        mockModifyGameModeGranted();

        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_2);
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_2));
    }

    /**
     * Test getter and setter of game modes.
     */
    @Test
    public void testGameMode() {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        mockModifyGameModeGranted();

        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE,
                USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Test permission.MANAGE_GAME_MODE is checked
     */
    @Test
    public void testGetGameModeInvalidPackageName() {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        try {
            assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                    gameManagerService.getGameMode(PACKAGE_NAME_INVALID,
                            USER_ID_1));

            fail("GameManagerService failed to generate SecurityException when "
                    + "permission.MANAGE_GAME_MODE is not granted.");
        } catch (SecurityException ignored) {
        }

        // The test should throw an exception, so the test is passing if we get here.
    }

    /**
     * Test permission.MANAGE_GAME_MODE is checked
     */
    @Test
    public void testSetGameModePermissionDenied() {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        // Update the game mode so we can read back something valid.
        mockModifyGameModeGranted();
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        // Deny permission.MANAGE_GAME_MODE and verify the game mode is not updated.
        mockModifyGameModeDenied();
        try {
            gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE,
                    USER_ID_1);

            fail("GameManagerService failed to generate SecurityException when "
                    + "permission.MANAGE_GAME_MODE is denied.");
        } catch (SecurityException ignored) {
        }

        // The test should throw an exception, so the test is passing if we get here.
        mockModifyGameModeGranted();
        // Verify that the Game Mode value wasn't updated.
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Test game modes are user-specific.
     */
    @Test
    public void testGameModeMultipleUsers() {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.onUserStarting(USER_ID_2);

        mockModifyGameModeGranted();

        // Set User 1 to Standard
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        // Set User 2 to Performance and verify User 1 is still Standard
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE,
                USER_ID_2);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_2));
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        // Set User 1 to Battery and verify User 2 is still Performance
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY,
                USER_ID_1);
        assertEquals(GameManager.GAME_MODE_BATTERY,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_2));
    }

    /**
     * Phonesky device config exists, but is only propagating the default value.
     */
    @Test
    public void testDeviceConfigDefault() {
        mockDeviceConfigDefault();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.loadDeviceConfigLocked();

        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        assertEquals(modes.length, 1);
        assertEquals(modes[0], GameManager.GAME_MODE_UNSUPPORTED);
    }

    /**
     * Phonesky device config does not exists.
     */
    @Test
    public void testDeviceConfigNone() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.loadDeviceConfigLocked();

        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        assertEquals(modes.length, 1);
        assertEquals(modes[0], GameManager.GAME_MODE_UNSUPPORTED);
    }

    /**
     * Phonesky device config for performance mode exists and is valid.
     */
    @Test
    public void testDeviceConfigPerformance() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.loadDeviceConfigLocked();

        boolean perfModeExists = false;
        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        for (int mode : modes) {
            if (mode == GameManager.GAME_MODE_PERFORMANCE) {
                perfModeExists = true;
            }
        }
        assertEquals(modes.length, 1);
        assertTrue(perfModeExists);
    }

    /**
     * Phonesky device config for battery mode exists and is valid.
     */
    @Test
    public void testDeviceConfigBattery() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.loadDeviceConfigLocked();

        boolean batteryModeExists = false;
        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        for (int mode : modes) {
            if (mode == GameManager.GAME_MODE_BATTERY) {
                batteryModeExists = true;
            }
        }
        assertEquals(modes.length, 1);
        assertTrue(batteryModeExists);
    }

    /**
     * Phonesky device configs for both battery and performance modes exists and are valid.
     */
    @Test
    public void testDeviceConfigAll() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.loadDeviceConfigLocked();

        boolean batteryModeExists = false;
        boolean perfModeExists = false;
        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        for (int mode : modes) {
            if (mode == GameManager.GAME_MODE_BATTERY) {
                batteryModeExists = true;
            } else if (mode == GameManager.GAME_MODE_PERFORMANCE) {
                perfModeExists = true;
            }
        }
        assertTrue(batteryModeExists);
        assertTrue(perfModeExists);
    }

    /**
     * Phonesky device config contains values that parse correctly but are not valid in game mode.
     */
    @Test
    public void testDeviceConfigInvalid() {
        mockDeviceConfigInvalid();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.loadDeviceConfigLocked();

        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        assertEquals(modes.length, 1);
        assertEquals(modes[0], GameManager.GAME_MODE_UNSUPPORTED);
    }

    /**
     * Phonesky device config is garbage.
     */
    @Test
    public void testDeviceConfigMalformed() {
        mockDeviceConfigMalformed();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.loadDeviceConfigLocked();

        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        assertEquals(modes.length, 1);
        assertEquals(modes[0], GameManager.GAME_MODE_UNSUPPORTED);
    }
}
