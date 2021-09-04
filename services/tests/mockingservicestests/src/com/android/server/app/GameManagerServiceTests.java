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
import static org.junit.Assert.assertNull;
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
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.util.ArraySet;

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
        pi.applicationInfo = applicationInfo;
        final List<PackageInfo> packages = new ArrayList<>();
        packages.add(pi);
        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), anyInt()))
                .thenReturn(packages);
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    @After
    public void tearDown() throws Exception {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.disableCompatScale(mPackageName);
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
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn("");
    }

    private void mockDeviceConfigNone() {
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(null);
    }

    private void mockDeviceConfigPerformance() {
        String configString = "mode=2,downscaleFactor=0.5,useAngle=false";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    // ANGLE will be disabled for most apps, so treat enabling ANGLE as a special case.
    private void mockDeviceConfigPerformanceEnableAngle() {
        String configString = "mode=2,downscaleFactor=0.5,useAngle=true";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigBattery() {
        String configString = "mode=3,downscaleFactor=0.7";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigAll() {
        String configString = "mode=3,downscaleFactor=0.7:mode=2,downscaleFactor=0.5";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigInvalid() {
        String configString = "";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigMalformed() {
        String configString = "adsljckv=nin3rn9hn1231245:8795tq=21ewuydg";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockGameModeOptInAll() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_PERFORMANCE_MODE_ENABLE, true);
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_BATTERY_MODE_ENABLE, true);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockGameModeOptInPerformance() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_PERFORMANCE_MODE_ENABLE, true);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockGameModeOptInBattery() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_BATTERY_MODE_ENABLE, true);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionAllowDownscaleTrue() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_WM_ALLOW_DOWNSCALE, true);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionAllowDownscaleFalse() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_WM_ALLOW_DOWNSCALE, false);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionAllowAngleTrue() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_ANGLE_ALLOW_ANGLE, true);
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockInterventionAllowAngleFalse() throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putBoolean(
                GameManagerService.GamePackageConfiguration.METADATA_ANGLE_ALLOW_ANGLE, false);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
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
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
        mockModifyGameModeGranted();
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        // We need to make sure the mode is supported before setting it.
        mockDeviceConfigAll();
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
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
        mockModifyGameModeGranted();
        mockDeviceConfigAll();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);

        // Update the game mode so we can read back something valid.
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
        mockModifyGameModeGranted();
        mockDeviceConfigAll();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.onUserStarting(USER_ID_2);
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
        gameManagerService.updateConfigsForUser(USER_ID_2, mPackageName);

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

    private void checkReportedModes(int ...requiredModes) {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
        ArraySet<Integer> reportedModes = new ArraySet<>();
        int[] modes = gameManagerService.getAvailableGameModes(mPackageName);
        for (int mode : modes) {
            reportedModes.add(mode);
        }
        assertEquals(requiredModes.length, reportedModes.size());
        for (int requiredMode : requiredModes) {
            assertTrue("Required game mode not supported: " + requiredMode,
                    reportedModes.contains(requiredMode));
        }
    }

    private void checkDownscaling(int gameMode, String scaling) {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertEquals(config.getGameModeConfiguration(gameMode).getScaling(), scaling);
    }

    private void checkAngleEnabled(GameManagerService gameManagerService, int gameMode,
            boolean angleEnabled) {
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);

        // Validate GamePackageConfiguration returns the correct value.
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertEquals(config.getGameModeConfiguration(gameMode).getUseAngle(), angleEnabled);

        // Validate GameManagerService.getAngleEnabled() returns the correct value.
        assertEquals(gameManagerService.getAngleEnabled(mPackageName, USER_ID_1), angleEnabled);
    }

    /**
     * Phenotype device config exists, but is only propagating the default value.
     */
    @Test
    public void testDeviceConfigDefault() {
        mockDeviceConfigDefault();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_UNSUPPORTED);
    }

    /**
     * Phenotype device config does not exists.
     */
    @Test
    public void testDeviceConfigNone() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_UNSUPPORTED);
    }

    /**
     * Phenotype device config for performance mode exists and is valid.
     */
    @Test
    public void testDeviceConfigPerformance() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_STANDARD);
    }

    /**
     * Phenotype device config for battery mode exists and is valid.
     */
    @Test
    public void testDeviceConfigBattery() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD);
    }

    /**
     * Phenotype device configs for both battery and performance modes exists and are valid.
     */
    @Test
    public void testDeviceConfigAll() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
    }

    /**
     * Phenotype device config contains values that parse correctly but are not valid in game mode.
     */
    @Test
    public void testDeviceConfigInvalid() {
        mockDeviceConfigInvalid();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_UNSUPPORTED);
    }

    /**
     * Phenotype device config is garbage.
     */
    @Test
    public void testDeviceConfigMalformed() {
        mockDeviceConfigMalformed();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_UNSUPPORTED);
    }

    /**
     * Game modes are made available only through app manifest opt-in.
     */
    @Test
    public void testGameModeOptInAll() throws Exception {
        mockGameModeOptInAll();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
    }

    /**
     * BATTERY game mode is available through the app manifest opt-in.
     */
    @Test
    public void testGameModeOptInBattery() throws Exception {
        mockGameModeOptInBattery();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD);
    }

    /**
     * PERFORMANCE game mode is available through the app manifest opt-in.
     */
    @Test
    public void testGameModeOptInPerformance() throws Exception {
        mockGameModeOptInPerformance();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_STANDARD);
    }

    /**
     * BATTERY game mode is available through the app manifest opt-in and PERFORMANCE game mode is
     * available through Phenotype.
     */
    @Test
    public void testGameModeOptInBatteryMixed() throws Exception {
        mockGameModeOptInBattery();
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
    }

    /**
     * PERFORMANCE game mode is available through the app manifest opt-in and BATTERY game mode is
     * available through Phenotype.
     */
    @Test
    public void testGameModeOptInPerformanceMixed() throws Exception {
        mockGameModeOptInPerformance();
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();
        checkReportedModes(GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app hasn't specified any metadata.
     */
    @Test
    public void testInterventionAllowScalingDefault() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkDownscaling(GameManager.GAME_MODE_PERFORMANCE, "0.5");
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has opted-out of scaling.
     */
    @Test
    public void testInterventionAllowDownscaleFalse() throws Exception {
        mockDeviceConfigPerformance();
        mockInterventionAllowDownscaleFalse();
        mockModifyGameModeGranted();
        checkDownscaling(GameManager.GAME_MODE_PERFORMANCE, "1.0");
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has redundantly specified
     * the downscaling metadata default value of "true".
     */
    @Test
    public void testInterventionAllowDownscaleTrue() throws Exception {
        mockDeviceConfigPerformance();
        mockInterventionAllowDownscaleTrue();
        mockModifyGameModeGranted();
        checkDownscaling(GameManager.GAME_MODE_PERFORMANCE, "0.5");
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app hasn't specified any metadata.
     */
    @Test
    public void testInterventionAllowAngleDefault() throws Exception {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkAngleEnabled(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, false);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has opted-out of ANGLE.
     */
    @Test
    public void testInterventionAllowAngleFalse() throws Exception {
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        mockDeviceConfigPerformanceEnableAngle();
        mockInterventionAllowAngleFalse();
        mockModifyGameModeGranted();
        checkAngleEnabled(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, false);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has redundantly specified
     * the ANGLE metadata default value of "true".
     */
    @Test
    public void testInterventionAllowAngleTrue() throws Exception {
        mockDeviceConfigPerformanceEnableAngle();
        mockInterventionAllowAngleTrue();

        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        mockModifyGameModeGranted();
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        checkAngleEnabled(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, true);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype, but the app has also opted into the
     * same mode. No interventions for this game mode should be available in this case.
     */
    @Test
    public void testDeviceConfigOptInOverlap() throws Exception {
        mockDeviceConfigPerformance();
        mockGameModeOptInPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName);
        assertNull(config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    /**
     * Ensure that, if a game no longer supports any game modes, we set the game mode to
     * UNSUPPORTED
     */
    @Test
    public void testUnsetInvalidGameMode() throws Exception {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Ensure that, if a game no longer supports a specific game mode, but supports STANDARD, we set
     * the game mode to STANDARD.
     */
    @Test
    public void testResetInvalidGameMode() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Ensure that if a game supports STANDARD, but is currently set to UNSUPPORTED, we set the game
     * mode to STANDARD
     */
    @Test
    public void testSetValidGameMode() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onUserStarting(USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_UNSUPPORTED, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, mPackageName);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }
}
