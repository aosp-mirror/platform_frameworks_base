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
import static com.android.server.app.GameManagerService.CANCEL_GAME_LOADING_MODE;
import static com.android.server.app.GameManagerService.Injector;
import static com.android.server.app.GameManagerService.LOADING_BOOST_MAX_DURATION;
import static com.android.server.app.GameManagerService.PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED;
import static com.android.server.app.GameManagerService.PROPERTY_RO_SURFACEFLINGER_GAME_DEFAULT_FRAME_RATE;
import static com.android.server.app.GameManagerService.SET_GAME_STATE;
import static com.android.server.app.GameManagerService.WRITE_DELAY_MILLIS;
import static com.android.server.app.GameManagerService.WRITE_GAME_MODE_INTERVENTION_LIST_FILE;
import static com.android.server.app.GameManagerService.WRITE_SETTINGS;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.GameManager;
import android.app.GameModeConfiguration;
import android.app.GameModeInfo;
import android.app.GameState;
import android.app.IGameModeListener;
import android.app.IGameStateListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.hardware.power.Mode;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.test.FakePermissionEnforcer;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;
import android.server.app.Flags;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class GameManagerServiceTests {
    @Mock MockContext mMockContext;
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private static final String TAG = "GameManagerServiceTests";
    private static final String PACKAGE_NAME_INVALID = "com.android.app";
    private static final int USER_ID_1 = 1001;
    private static final int USER_ID_2 = 1002;
    // to pass the valid package check in some of the server methods
    private static final int DEFAULT_PACKAGE_UID = Binder.getCallingUid();

    private MockitoSession mMockingSession;
    private String mPackageName;
    private TestLooper mTestLooper;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private PowerManagerInternal mMockPowerManager;
    @Mock
    private UserManager mMockUserManager;
    private BroadcastReceiver mShutDownActionReceiver;

    private FakePermissionEnforcer mFakePermissionEnforcer = new FakePermissionEnforcer();

    @Mock
    private GameManagerServiceSystemPropertiesWrapper mSysPropsMock;

    @Captor
    ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor;

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

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.USER_SERVICE:
                    return mMockUserManager;
                case Context.PERMISSION_ENFORCER_SERVICE:
                    return mFakePermissionEnforcer;
            }
            throw new UnsupportedOperationException("Couldn't find system service: " + name);
        }

        @Override
        public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
            mShutDownActionReceiver = receiver;
            return null;
        }
    }

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(DeviceConfig.class)
                .strictness(Strictness.WARN)
                .startMocking();
        mMockContext = new MockContext(InstrumentationRegistry.getContext());
        mPackageName = mMockContext.getPackageName();
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_GAME);
        final Resources resources =
                InstrumentationRegistry.getInstrumentation().getContext().getResources();
        when(mMockPackageManager.getResourcesForApplication(anyString()))
                .thenReturn(resources);
        when(mMockPackageManager.getPackageUidAsUser(mPackageName, USER_ID_1)).thenReturn(
                DEFAULT_PACKAGE_UID);
        LocalServices.addService(PowerManagerInternal.class, mMockPowerManager);

        mSetFlagsRule.enableFlags(Flags.FLAG_GAME_DEFAULT_FRAME_RATE);
    }

    private void mockAppCategory(String packageName, @ApplicationInfo.Category int category)
            throws Exception {
        reset(mMockPackageManager);
        final ApplicationInfo gameApplicationInfo = new ApplicationInfo();
        gameApplicationInfo.category = category;
        gameApplicationInfo.packageName = packageName;
        final PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.applicationInfo = gameApplicationInfo;
        final List<PackageInfo> packages = new ArrayList<>();
        packages.add(pi);
        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), anyInt()))
                .thenReturn(packages);
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(gameApplicationInfo);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
        deleteFolder(InstrumentationRegistry.getTargetContext().getFilesDir());
    }

    private void startUser(GameManagerService gameManagerService, int userId) {
        UserInfo userInfo = new UserInfo(userId, "name", 0);
        gameManagerService.onUserStarting(new SystemService.TargetUser(userInfo),
                InstrumentationRegistry.getContext().getFilesDir());
        mTestLooper.dispatchAll();
    }

    private void switchUser(GameManagerService gameManagerService, int from, int to) {
        UserInfo userInfoFrom = new UserInfo(from, "name", 0);
        UserInfo userInfoTo = new UserInfo(to, "name", 0);
        gameManagerService.onUserSwitching(/* from */ new SystemService.TargetUser(userInfoFrom),
                /* to */ new SystemService.TargetUser(userInfoTo));
        mTestLooper.dispatchAll();
    }

    private void mockQueryAllPackageGranted() {
        mMockContext.setPermission(Manifest.permission.QUERY_ALL_PACKAGES,
                PackageManager.PERMISSION_GRANTED);
    }

    private void mockQueryAllPackageDenied() {
        mMockContext.setPermission(Manifest.permission.QUERY_ALL_PACKAGES,
                PackageManager.PERMISSION_DENIED);
    }

    private void mockManageUsersGranted() {
        mMockContext.setPermission(Manifest.permission.MANAGE_USERS,
                PackageManager.PERMISSION_GRANTED);
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
        String configString = "mode=2,downscaleFactor=0.5,useAngle=false,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    // ANGLE will be disabled for most apps, so treat enabling ANGLE as a special case.
    private void mockDeviceConfigPerformanceEnableAngle() {
        String configString = "mode=2,downscaleFactor=0.5,useAngle=true";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    // Loading boost will be disabled for most apps, so treat enabling loading boost as a special
    // case.
    private void mockDeviceConfigPerformanceEnableLoadingBoost() {
        String configString = "mode=2,downscaleFactor=0.5,loadingBoost=0";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigBattery() {
        String configString = "mode=3,downscaleFactor=0.7,fps=30";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
    }

    private void mockDeviceConfigAll() {
        String configString = "mode=3,downscaleFactor=0.7,fps=30:mode=2,downscaleFactor=0.5,fps=90";
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

    private void mockInterventionsEnabledBatteryOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123, "res/xml/"
                + "game_manager_service_metadata_config_interventions_enabled_battery_opt_in.xml");
    }

    private void mockInterventionsEnabledPerformanceOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123, "res/xml/"
                + "game_manager_service_metadata_config_interventions_enabled_performance_opt_in"
                + ".xml");
    }

    private void mockInterventionsEnabledNoOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123,
                "res/xml/game_manager_service_metadata_config_interventions_enabled_no_opt_in.xml");
    }

    private void mockInterventionsEnabledAllOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123,
                "res/xml/game_manager_service_metadata_config_interventions_enabled_all_opt_in"
                        + ".xml");
    }

    private void mockInterventionsDisabledNoOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123,
                "res/xml/game_manager_service_metadata_config_interventions_disabled_no_opt_in"
                        + ".xml");
    }

    private void mockInterventionsDisabledAllOptInFromXml() throws Exception {
        seedGameManagerServiceMetaDataFromFile(mPackageName, 123,
                "res/xml/game_manager_service_metadata_config_interventions_disabled_all_opt_in"
                        + ".xml");
    }


    private void seedGameManagerServiceMetaDataFromFile(String packageName, int resId,
            String fileName)
            throws Exception {
        final ApplicationInfo applicationInfo = mMockPackageManager.getApplicationInfoAsUser(
                mPackageName, PackageManager.GET_META_DATA, USER_ID_1);
        Bundle metaDataBundle = new Bundle();
        metaDataBundle.putInt(
                GameManagerService.GamePackageConfiguration.METADATA_GAME_MODE_CONFIG, resId);
        applicationInfo.metaData = metaDataBundle;
        when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        AssetManager assetManager =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        XmlResourceParser xmlResourceParser =
                assetManager.openXmlResourceParser(fileName);
        when(mMockPackageManager.getXml(eq(packageName), eq(resId), any()))
                .thenReturn(xmlResourceParser);
    }

    /**
     * By default game mode is set to STANDARD
     */
    @Test
    public void testGetGameMode_defaultValue() {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        mockModifyGameModeGranted();
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    @Test
    public void testGetGameMode_nonGame() throws Exception {
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_AUDIO);
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        mockModifyGameModeGranted();
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Test the default behaviour for a nonexistent user.
     */
    @Test
    public void testDefaultValueForNonexistentUser() {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();

        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_2);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_2));
    }

    /**
     * Test getter and setter of game modes.
     */
    @Test
    public void testGameMode() {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());


        startUser(gameManagerService, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        mockModifyGameModeGranted();
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        // We need to make sure the mode is supported before setting it.
        mockDeviceConfigAll();
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_STANDARD, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE,
                USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    /**
     * Test invalid package name is queried
     */
    @Test
    public void testGetGameModeInvalidPackageName() {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        try {
            when(mMockPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                    .thenThrow(new PackageManager.NameNotFoundException());
            assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                    gameManagerService.getGameMode(PACKAGE_NAME_INVALID,
                            USER_ID_1));
        } catch (PackageManager.NameNotFoundException e) {
            // should never get here as isPackageGame() catches this exception
            // fail this test if we ever get here
            fail("Unexpected NameNotFoundException caught.");
        }
    }

    /**
     * Test permission.MANAGE_GAME_MODE is checked
     */
    @Test
    public void testSetGameModePermissionDenied() {
        mockModifyGameModeGranted();
        mockDeviceConfigAll();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

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
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());

        startUser(gameManagerService, USER_ID_1);
        startUser(gameManagerService, USER_ID_2);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        gameManagerService.updateConfigsForUser(USER_ID_2, true, mPackageName);

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

    private GameManagerService createServiceAndStartUser(int userId) {
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, userId);
        return gameManagerService;
    }

    private void checkReportedAvailableGameModes(GameManagerService gameManagerService,
            int... requiredAvailableModes) {
        Arrays.sort(requiredAvailableModes);
        // check getAvailableGameModes
        int[] reportedAvailableModes = gameManagerService.getAvailableGameModes(mPackageName,
                USER_ID_1);
        Arrays.sort(reportedAvailableModes);
        assertArrayEquals(requiredAvailableModes, reportedAvailableModes);

        // check GetModeInfo.getAvailableGameModes
        GameModeInfo info = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);
        if (requiredAvailableModes.length == 0) {
            assertNull(info);
        } else {
            assertNotNull(info);
            reportedAvailableModes = info.getAvailableGameModes();
            Arrays.sort(reportedAvailableModes);
            assertArrayEquals(requiredAvailableModes, reportedAvailableModes);
        }
    }

    private void checkReportedOverriddenGameModes(GameManagerService gameManagerService,
            int... requiredOverriddenModes) {
        Arrays.sort(requiredOverriddenModes);
        // check GetModeInfo.getOverriddenGameModes
        GameModeInfo info = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);
        assertNotNull(info);
        int[] overriddenModes = info.getOverriddenGameModes();
        Arrays.sort(overriddenModes);
        assertArrayEquals(requiredOverriddenModes, overriddenModes);
    }

    private void checkDownscaling(GameManagerService gameManagerService,
            int gameMode, float scaling) {
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertEquals(scaling, config.getGameModeConfiguration(gameMode).getScaling(), 0.01f);
    }

    private void checkAngleEnabled(GameManagerService gameManagerService, int gameMode,
            boolean angleEnabled) {
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);

        // Validate GamePackageConfiguration returns the correct value.
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertEquals(config.getGameModeConfiguration(gameMode).getUseAngle(), angleEnabled);

        // Validate GameManagerService.isAngleEnabled() returns the correct value.
        assertEquals(gameManagerService.isAngleEnabled(mPackageName, USER_ID_1), angleEnabled);
    }

    private void checkLoadingBoost(GameManagerService gameManagerService, int gameMode,
            int loadingBoost) {
        // Validate GamePackageConfiguration returns the correct value.
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertEquals(
                loadingBoost, config.getGameModeConfiguration(gameMode).getLoadingBoostDuration());

        // Validate GameManagerService.getLoadingBoostDuration() returns the correct value.
        assertEquals(
                loadingBoost, gameManagerService.getLoadingBoostDuration(mPackageName, USER_ID_1));
    }

    private void checkFps(GameManagerService gameManagerService, int gameMode, int fps) {
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertEquals(fps, config.getGameModeConfiguration(gameMode).getFps());
    }

    private boolean checkOverridden(GameManagerService gameManagerService, int gameMode) {
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName, USER_ID_1);
        return config.willGamePerformOptimizations(gameMode);
    }

    /**
     * Phenotype device config exists, but is only propagating the default value.
     */
    @Test
    public void testDeviceConfigDefault() {
        mockDeviceConfigDefault();
        mockModifyGameModeGranted();
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
    }

    /**
     * Phenotype device config does not exists.
     */
    @Test
    public void testDeviceConfigNone() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
    }

    /**
     * Phenotype device config contains values that parse correctly but are not valid in game mode.
     */
    @Test
    public void testDeviceConfigInvalid() {
        mockDeviceConfigInvalid();
        mockModifyGameModeGranted();
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
    }

    /**
     * Phenotype device config is garbage.
     */
    @Test
    public void testDeviceConfigMalformed() {
        mockDeviceConfigMalformed();
        mockModifyGameModeGranted();
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
    }

    @Test
    public void testDeviceConfig_nonGame() throws Exception {
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_AUDIO);
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1));
    }

    /**
     * Override device config for performance mode exists and is valid.
     */
    @Test
    public void testSetDeviceConfigOverridePerformance() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0.3f);
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 120);
    }

    /**
     * Override device config for battery mode exists and is valid.
     */
    @Test
    public void testSetDeviceConfigOverrideBattery() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.5f);
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 60);
    }

    /**
     * Override device configs for both battery and performance modes exists and are valid.
     */
    @Test
    public void testSetDeviceConfigOverrideAll() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0.3f);
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 120);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.5f);
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 60);
    }

    @Test
    public void testSetBatteryModeConfigOverride_thenUpdateAllDeviceConfig() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90:mode=3,downscaleFactor=0.1,fps=30";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 1.0f);
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.1f);
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 30);

        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1, 3, "40",
                "0.2");

        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 40);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.2f);

        String configStringAfter =
                "mode=2,downscaleFactor=0.9,fps=60:mode=3,downscaleFactor=0.3,fps=50";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringAfter);
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);

        // performance mode was not overridden thus it should be updated
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0.9f);
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 60);

        // battery mode was overridden thus it should be the same as the override
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.2f);
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 40);
    }

    @Test
    public void testSetBatteryModeConfigOverride_thenOptInBatteryMode() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90:mode=3,downscaleFactor=0.1,fps=30";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsDisabledNoOptInFromXml();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        assertFalse(checkOverridden(gameManagerService, GameManager.GAME_MODE_PERFORMANCE));
        assertFalse(checkOverridden(gameManagerService, GameManager.GAME_MODE_BATTERY));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0);

        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1, 3, "40",
                "0.2");
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0);
        // override will enable the interventions
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.2f);
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 40);

        mockInterventionsDisabledAllOptInFromXml();
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);

        assertTrue(checkOverridden(gameManagerService, GameManager.GAME_MODE_PERFORMANCE));
        // opt-in is still false for battery mode as override exists
        assertFalse(checkOverridden(gameManagerService, GameManager.GAME_MODE_BATTERY));
    }

    /**
     * Override device config for performance mode exists and is valid.
     */
    @Test
    public void testResetDeviceConfigOverridePerformance() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");

        gameManagerService.resetGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE);

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0.5f);
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);
    }

    /**
     * Override device config for battery mode exists and is valid.
     */
    @Test
    public void testResetDeviceConfigOverrideBattery() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        gameManagerService.resetGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY);

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.7f);
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 30);
    }

    /**
     * Override device configs for both battery and performance modes exists and are valid.
     */
    @Test
    public void testResetDeviceOverrideConfigAll() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        gameManagerService.resetGameModeConfigOverride(mPackageName, USER_ID_1, -1);

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0.5f);
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.7f);
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 30);
    }

    /**
     * Override device configs for both battery and performance modes exists and are valid.
     * Only one mode is reset, and the other mode still has overridden config
     */
    @Test
    public void testResetDeviceOverrideConfigPartial() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();

        GameManagerService gameManagerService = new GameManagerService(
                mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.3");
        gameManagerService.setGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");

        gameManagerService.resetGameModeConfigOverride(mPackageName, USER_ID_1,
                GameManager.GAME_MODE_BATTERY);

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0.3f);
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 120);
        checkDownscaling(gameManagerService, GameManager.GAME_MODE_BATTERY, 0.7f);
        checkFps(gameManagerService, GameManager.GAME_MODE_BATTERY, 30);
    }

    /**
     * Game modes are made available only through app manifest opt-in.
     */
    @Test
    public void testGameModeOptInAll() throws Exception {
        mockGameModeOptInAll();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
    }


    /**
     * BATTERY game mode is available through the app manifest opt-in.
     */
    @Test
    public void testGameModeOptInBattery() throws Exception {
        mockGameModeOptInBattery();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
    }

    /**
     * PERFORMANCE game mode is available through the app manifest opt-in.
     */
    @Test
    public void testGameModeOptInPerformance() throws Exception {
        mockGameModeOptInPerformance();
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
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
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
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
        checkReportedAvailableGameModes(createServiceAndStartUser(USER_ID_1),
                GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app hasn't specified any metadata.
     */
    @Test
    public void testInterventionAllowScalingDefault() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        checkDownscaling(createServiceAndStartUser(USER_ID_1), GameManager.GAME_MODE_PERFORMANCE,
                0.5f);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has opted-out of scaling.
     */
    @Test
    public void testInterventionAllowDownscaleFalse() throws Exception {
        mockDeviceConfigPerformance();
        mockInterventionAllowDownscaleFalse();
        mockModifyGameModeGranted();
        checkDownscaling(createServiceAndStartUser(USER_ID_1), GameManager.GAME_MODE_PERFORMANCE,
                -1.0f);
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
        checkDownscaling(createServiceAndStartUser(USER_ID_1), GameManager.GAME_MODE_PERFORMANCE,
                0.5f);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app hasn't specified any metadata.
     */
    @Test
    public void testInterventionAllowAngleDefault() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        checkAngleEnabled(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, false);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app hasn't specified any
     * metadata.
     */
    @Test
    public void testInterventionAllowLoadingBoostDefault() throws Exception {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        checkLoadingBoost(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, -1);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has opted-out of ANGLE.
     */
    @Test
    public void testInterventionAllowAngleFalse() throws Exception {
        mockDeviceConfigPerformanceEnableAngle();
        mockInterventionAllowAngleFalse();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
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

        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));

        checkAngleEnabled(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, true);
    }

    /**
     * PERFORMANCE game mode is configured through Phenotype. The app has redundantly specified the
     * Loading Boost metadata default value of "true".
     */
    @Test
    public void testInterventionAllowLoadingBoost() throws Exception {
        mockDeviceConfigPerformanceEnableLoadingBoost();

        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        mockInterventionsEnabledNoOptInFromXml();
        checkLoadingBoost(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0);
    }

    @Test
    public void testGameModeConfigAllowFpsTrue() throws Exception {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertEquals(90,
                config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE).getFps());
        assertEquals(30, config.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY).getFps());
    }

    @Test
    public void testGameModeConfigAllowFpsFalse() throws Exception {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        mockInterventionsDisabledNoOptInFromXml();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertEquals(0,
                config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE).getFps());
        assertEquals(0, config.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY).getFps());
    }

    @Test
    public void testInterventionFps() throws Exception {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        GameManagerService service = createServiceAndStartUser(USER_ID_1);
        checkFps(service, GameManager.GAME_MODE_PERFORMANCE, 90);
        checkFps(service, GameManager.GAME_MODE_BATTERY, 30);
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
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        GameManagerService.GamePackageConfiguration config =
                gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertNull(config.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    /**
     * Ensure that, if a game no longer supports any game modes, we set the game mode to
     * STANDARD
     */
    @Test
    public void testUnsetInvalidGameMode() throws Exception {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        assertEquals(GameManager.GAME_MODE_STANDARD,
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
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
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
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_UNSUPPORTED, USER_ID_1);
        gameManagerService.updateConfigsForUser(USER_ID_1, true, mPackageName);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
    }

    static {
        System.loadLibrary("mockingservicestestjni");
    }

    @Test
    public void testGetGameModeInfoPermissionDenied() {
        mockDeviceConfigAll();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);

        // Deny permission.MANAGE_GAME_MODE and verify the game mode is not updated.
        mockModifyGameModeDenied();
        assertThrows(SecurityException.class,
                () -> gameManagerService.getGameModeInfo(mPackageName, USER_ID_1));
    }

    @Test
    public void testGetGameModeInfoWithAllGameModes() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD, gameModeInfo.getActiveGameMode());
        assertTrue(gameModeInfo.isDownscalingAllowed());
        assertTrue(gameModeInfo.isFpsOverrideAllowed());

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
        checkReportedOverriddenGameModes(gameManagerService);

        assertEquals(new GameModeConfiguration.Builder()
                .setFpsOverride(30)
                .setScalingFactor(0.7f)
                .build(), gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
        assertEquals(new GameModeConfiguration.Builder()
                .setFpsOverride(90)
                .setScalingFactor(0.5f)
                .build(), gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    @Test
    public void testGetGameModeInfoWithBatteryMode() {
        mockDeviceConfigBattery();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);

        assertEquals(GameManager.GAME_MODE_BATTERY, gameModeInfo.getActiveGameMode());

        checkReportedAvailableGameModes(gameManagerService,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
        checkReportedOverriddenGameModes(gameManagerService);

        assertNotNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    @Test
    public void testGetGameModeInfoWithPerformanceMode() {
        mockDeviceConfigPerformance();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);

        assertEquals(GameManager.GAME_MODE_PERFORMANCE, gameModeInfo.getActiveGameMode());
        checkReportedAvailableGameModes(gameManagerService,
                GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
        checkReportedOverriddenGameModes(gameManagerService);

        assertNotNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
    }

    @Test
    public void testGetGameModeInfoWithDefaultGameModes() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);

        assertEquals(GameManager.GAME_MODE_STANDARD, gameModeInfo.getActiveGameMode());
        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_CUSTOM));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_STANDARD));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    @Test
    public void testGetGameModeInfoWithAllGameModesOverridden_noDeviceConfig()
            throws Exception {
        mockModifyGameModeGranted();
        mockInterventionsEnabledAllOptInFromXml();
        mockDeviceConfigNone();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD, gameModeInfo.getActiveGameMode());
        verifyAllModesOverriddenAndInterventionsAvailable(gameManagerService, gameModeInfo);

        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    @Test
    public void testGetGameModeInfoWithAllGameModesOverridden_allDeviceConfig()
            throws Exception {
        mockModifyGameModeGranted();
        mockInterventionsEnabledAllOptInFromXml();
        mockDeviceConfigAll();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD, gameModeInfo.getActiveGameMode());
        verifyAllModesOverriddenAndInterventionsAvailable(gameManagerService, gameModeInfo);

        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    private void verifyAllModesOverriddenAndInterventionsAvailable(
            GameManagerService gameManagerService,
            GameModeInfo gameModeInfo) {
        checkReportedAvailableGameModes(gameManagerService,
                GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
        checkReportedOverriddenGameModes(gameManagerService,
                GameManager.GAME_MODE_PERFORMANCE, GameManager.GAME_MODE_BATTERY);
        assertTrue(gameModeInfo.isFpsOverrideAllowed());
        assertTrue(gameModeInfo.isDownscalingAllowed());
    }

    @Test
    public void testGetGameModeInfoWithBatteryModeOverridden_withBatteryDeviceConfig()
            throws Exception {
        mockModifyGameModeGranted();
        mockInterventionsEnabledBatteryOptInFromXml();
        mockDeviceConfigBattery();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD, gameModeInfo.getActiveGameMode());

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_BATTERY,
                GameManager.GAME_MODE_STANDARD, GameManager.GAME_MODE_CUSTOM);
        checkReportedOverriddenGameModes(gameManagerService, GameManager.GAME_MODE_BATTERY);

        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    @Test
    public void testGetGameModeInfoWithPerformanceModeOverridden_withAllDeviceConfig()
            throws Exception {
        mockModifyGameModeGranted();
        mockInterventionsEnabledPerformanceOptInFromXml();
        mockDeviceConfigAll();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD, gameModeInfo.getActiveGameMode());

        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_CUSTOM);
        checkReportedOverriddenGameModes(gameManagerService, GameManager.GAME_MODE_PERFORMANCE);

        assertNotNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY));
        assertNull(gameModeInfo.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE));
    }

    @Test
    public void testGetGameModeInfoWithInterventionsDisabled() throws Exception {
        mockModifyGameModeGranted();
        mockInterventionsDisabledAllOptInFromXml();
        mockDeviceConfigAll();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        GameModeInfo gameModeInfo = gameManagerService.getGameModeInfo(mPackageName, USER_ID_1);
        assertFalse(gameModeInfo.isFpsOverrideAllowed());
        assertFalse(gameModeInfo.isDownscalingAllowed());
    }

    @Test
    public void testSetGameState_loadingRequiresPerformanceMode() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        GameState gameState = new GameState(true, GameState.MODE_NONE);
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, never()).setPowerMode(anyInt(), anyBoolean());
    }

    @Test
    public void testSetGameStateLoading_withNoDeviceConfig() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.setGameMode(
                mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        assertEquals(gameManagerService.getGameMode(mPackageName, USER_ID_1),
                GameManager.GAME_MODE_PERFORMANCE);
        int testMode = GameState.MODE_GAMEPLAY_INTERRUPTIBLE;
        int testLabel = 99;
        int testQuality = 123;
        GameState gameState = new GameState(true, testMode, testLabel, testQuality);
        assertEquals(testMode, gameState.getMode());
        assertEquals(testLabel, gameState.getLabel());
        assertEquals(testQuality, gameState.getQuality());
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, true);
        reset(mMockPowerManager);
        assertTrue(
                gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
        verify(mMockPowerManager, never()).setPowerMode(Mode.GAME_LOADING, false);
        mTestLooper.moveTimeForward(GameManagerService.LOADING_BOOST_MAX_DURATION);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, false);
    }

    @Test
    public void testSetGameStateLoading_withDeviceConfig() {
        String configString = "mode=2,loadingBoost=2000";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.setGameMode(
                mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        GameState gameState = new GameState(true, GameState.MODE_GAMEPLAY_INTERRUPTIBLE, 99, 123);
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, true);
        verify(mMockPowerManager, never()).setPowerMode(Mode.GAME_LOADING, false);
        reset(mMockPowerManager);
        assertTrue(
                gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
        mTestLooper.moveTimeForward(2000);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, false);
    }

    @Test
    public void testSetGameStateNotLoading() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.setGameMode(
                mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        int testMode = GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE;
        int testLabel = 99;
        int testQuality = 123;
        GameState gameState = new GameState(false, testMode, testLabel, testQuality);
        assertFalse(gameState.isLoading());
        assertEquals(testMode, gameState.getMode());
        assertEquals(testLabel, gameState.getLabel());
        assertEquals(testQuality, gameState.getQuality());
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        assertTrue(gameManagerService.mHandler.hasEqualMessages(SET_GAME_STATE, gameState));
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, false);
        assertFalse(
                gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
    }

    @Test
    public void testSetGameState_nonGame() throws Exception {
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_AUDIO);
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        GameState gameState = new GameState(true, GameState.MODE_NONE);
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        assertFalse(gameManagerService.mHandler.hasMessages(SET_GAME_STATE));
    }

    @Test
    public void testAddGameStateListener() throws Exception {
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        mockDeviceConfigAll();
        startUser(gameManagerService, USER_ID_1);

        IGameStateListener mockListener = Mockito.mock(IGameStateListener.class);
        IBinder binder = Mockito.mock(IBinder.class);
        when(mockListener.asBinder()).thenReturn(binder);
        gameManagerService.addGameStateListener(mockListener);
        verify(binder).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());

        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_AUDIO);
        GameState gameState = new GameState(true, GameState.MODE_NONE);
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        assertFalse(gameManagerService.mHandler.hasMessages(SET_GAME_STATE));
        mTestLooper.dispatchAll();
        verify(mockListener, never()).onGameStateChanged(anyString(), any(), anyInt());

        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_GAME);
        gameState = new GameState(true, GameState.MODE_NONE);
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        assertTrue(gameManagerService.mHandler.hasMessages(SET_GAME_STATE));
        mTestLooper.dispatchAll();
        verify(mockListener).onGameStateChanged(mPackageName, gameState, USER_ID_1);
        reset(mockListener);

        gameState = new GameState(false, GameState.MODE_CONTENT);
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        mTestLooper.dispatchAll();
        verify(mockListener).onGameStateChanged(mPackageName, gameState, USER_ID_1);
        reset(mockListener);

        mDeathRecipientCaptor.getValue().binderDied();
        verify(binder).unlinkToDeath(eq(mDeathRecipientCaptor.getValue()), anyInt());
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        assertTrue(gameManagerService.mHandler.hasMessages(SET_GAME_STATE));
        mTestLooper.dispatchAll();
        verify(mockListener, never()).onGameStateChanged(anyString(), any(), anyInt());
    }

    @Test
    public void testRemoveGameStateListener() throws Exception {
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        mockDeviceConfigAll();
        startUser(gameManagerService, USER_ID_1);

        IGameStateListener mockListener = Mockito.mock(IGameStateListener.class);
        IBinder binder = Mockito.mock(IBinder.class);
        when(mockListener.asBinder()).thenReturn(binder);

        gameManagerService.addGameStateListener(mockListener);
        gameManagerService.removeGameStateListener(mockListener);
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_GAME);
        GameState gameState = new GameState(false, GameState.MODE_CONTENT);
        gameManagerService.setGameState(mPackageName, gameState, USER_ID_1);
        assertTrue(gameManagerService.mHandler.hasMessages(SET_GAME_STATE));
        mTestLooper.dispatchAll();
        verify(mockListener, never()).onGameStateChanged(anyString(), any(), anyInt());
    }

    private List<String> readGameModeInterventionList() throws Exception {
        final File interventionFile = new File(InstrumentationRegistry.getContext().getFilesDir(),
                "system/game_mode_intervention.list");
        assertNotNull(interventionFile);
        List<String> output = Files.readAllLines(interventionFile.toPath());
        return output;
    }

    private void mockInterventionListForMultipleUsers() {
        final String[] packageNames = new String[]{"com.android.app0",
                "com.android.app1", "com.android.app2"};

        final ApplicationInfo[] applicationInfos = new ApplicationInfo[3];
        final PackageInfo[] pis = new PackageInfo[3];
        for (int i = 0; i < 3; ++i) {
            applicationInfos[i] = new ApplicationInfo();
            applicationInfos[i].category = ApplicationInfo.CATEGORY_GAME;
            applicationInfos[i].packageName = packageNames[i];

            pis[i] = new PackageInfo();
            pis[i].packageName = packageNames[i];
            pis[i].applicationInfo = applicationInfos[i];
        }

        final List<PackageInfo> userOnePackages = new ArrayList<>();
        final List<PackageInfo> userTwoPackages = new ArrayList<>();
        userOnePackages.add(pis[1]);
        userTwoPackages.add(pis[0]);
        userTwoPackages.add(pis[2]);

        final List<UserInfo> userInfos = new ArrayList<>(2);
        userInfos.add(new UserInfo());
        userInfos.add(new UserInfo());
        userInfos.get(0).id = USER_ID_1;
        userInfos.get(1).id = USER_ID_2;

        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), eq(USER_ID_1)))
                .thenReturn(userOnePackages);
        when(mMockPackageManager.getInstalledPackagesAsUser(anyInt(), eq(USER_ID_2)))
                .thenReturn(userTwoPackages);
        when(mMockUserManager.getUsers()).thenReturn(userInfos);
    }

    @Test
    public void testVerifyInterventionList() throws Exception {
        mockDeviceConfigAll();
        mockInterventionListForMultipleUsers();
        mockManageUsersGranted();
        mockModifyGameModeGranted();
        final Context context = InstrumentationRegistry.getContext();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper(), context.getFilesDir(),
                        new Injector());
        startUser(gameManagerService, USER_ID_1);
        startUser(gameManagerService, USER_ID_2);

        gameManagerService.setGameModeConfigOverride("com.android.app0", USER_ID_2,
                GameManager.GAME_MODE_PERFORMANCE, "120", "0.6");
        gameManagerService.setGameModeConfigOverride("com.android.app2", USER_ID_2,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");
        mTestLooper.dispatchAll();

        /* Expected fileOutput (order may vary)
         # user 1001:
         com.android.app2 <UID>   1   2   angle=0,scaling=0.5,fps=90  3   angle=0,scaling=0.5,fps=60
         com.android.app1 <UID>   1   2   angle=0,scaling=0.5,fps=90  3   angle=0,scaling=0.7,fps=30
         com.android.app0 <UID>   1   2   angle=0,scaling=0.6,fps=120 3   angle=0,scaling=0.7,fps=30

         # user 1002:
         com.android.app2 <UID>   1   2   angle=0,scaling=0.5,fps=90  3   angle=0,scaling=0.7,fps=30
         com.android.app1 <UID>   1   2   angle=0,scaling=0.5,fps=90  3   angle=0,scaling=0.7,fps=30
         com.android.app0 <UID>   1   2   angle=0,scaling=0.5,fps=90  3   angle=0,scaling=0.7,fps=30
         The current game mode would only be set to non-zero if the current user have that game
         installed.
        */

        List<String> fileOutput = readGameModeInterventionList();
        assertEquals(fileOutput.size(), 3);

        String[] splitLine = fileOutput.get(0).split("\\s+");
        assertEquals(splitLine[0], "com.android.app2");
        assertEquals(splitLine[2], "3");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.5,fps=60");
        splitLine = fileOutput.get(1).split("\\s+");
        assertEquals(splitLine[0], "com.android.app1");
        assertEquals(splitLine[2], "1");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");
        splitLine = fileOutput.get(2).split("\\s+");
        assertEquals(splitLine[0], "com.android.app0");
        assertEquals(splitLine[2], "2");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.6,fps=120");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");

        switchUser(gameManagerService, USER_ID_2, USER_ID_1);
        gameManagerService.setGameMode("com.android.app1",
                GameManager.GAME_MODE_BATTERY, USER_ID_1);
        mTestLooper.dispatchAll();

        fileOutput = readGameModeInterventionList();
        assertEquals(fileOutput.size(), 3);

        splitLine = fileOutput.get(0).split("\\s+");
        assertEquals(splitLine[0], "com.android.app2");
        assertEquals(splitLine[2], "1");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");
        splitLine = fileOutput.get(1).split("\\s+");
        assertEquals(splitLine[0], "com.android.app1");
        assertEquals(splitLine[2], "3");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");
        splitLine = fileOutput.get(2).split("\\s+");
        assertEquals(splitLine[0], "com.android.app0");
        assertEquals(splitLine[2], "1");
        assertEquals(splitLine[3], "2");
        assertEquals(splitLine[4], "angle=0,scaling=0.5,fps=90");
        assertEquals(splitLine[5], "3");
        assertEquals(splitLine[6], "angle=0,scaling=0.7,fps=30");

    }

    @Test
    public void testSwitchUser() {
        mockManageUsersGranted();
        mockModifyGameModeGranted();

        mockDeviceConfigBattery();
        final Context context = InstrumentationRegistry.getContext();
        GameManagerService gameManagerService = new GameManagerService(mMockContext,
                mTestLooper.getLooper(), context.getFilesDir(), new Injector());
        startUser(gameManagerService, USER_ID_1);
        startUser(gameManagerService, USER_ID_2);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_CUSTOM);
        assertEquals(gameManagerService.getGameMode(mPackageName, USER_ID_1),
                GameManager.GAME_MODE_BATTERY);

        mockDeviceConfigAll();
        switchUser(gameManagerService, USER_ID_1, USER_ID_2);
        assertEquals(gameManagerService.getGameMode(mPackageName, USER_ID_2),
                GameManager.GAME_MODE_STANDARD);
        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_CUSTOM);

        switchUser(gameManagerService, USER_ID_2, USER_ID_1);
        assertEquals(gameManagerService.getGameMode(mPackageName, USER_ID_1),
                GameManager.GAME_MODE_BATTERY);
        checkReportedAvailableGameModes(gameManagerService, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_BATTERY, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_CUSTOM);
    }

    @Test
    public void testUpdateResolutionScalingFactor() {
        mockModifyGameModeGranted();
        mockDeviceConfigBattery();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        float scalingFactor = 0.123f;
        gameManagerService.updateResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY, scalingFactor,
                USER_ID_1);
        assertEquals(scalingFactor, gameManagerService.getResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY, USER_ID_1), 0.001f);
        scalingFactor = 0.321f;
        gameManagerService.updateResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY, scalingFactor,
                USER_ID_1);
        assertEquals(scalingFactor, gameManagerService.getResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY, USER_ID_1), 0.001f);
    }

    @Test
    public void testUpdateResolutionScalingFactor_noDeviceConfig() {
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        float scalingFactor = 0.123f;
        gameManagerService.updateResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY, scalingFactor,
                USER_ID_1);
        assertEquals(scalingFactor, gameManagerService.getResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY, USER_ID_1), 0.001f);
        scalingFactor = 0.321f;
        gameManagerService.updateResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY, scalingFactor,
                USER_ID_1);
        assertEquals(scalingFactor, gameManagerService.getResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY,
                USER_ID_1), 0.001f);
    }

    @Test
    public void testUpdateResolutionScalingFactor_permissionDenied() {
        mockModifyGameModeDenied();
        mockDeviceConfigAll();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        float scalingFactor = 0.123f;
        assertThrows(SecurityException.class, () -> {
            gameManagerService.updateResolutionScalingFactor(mPackageName,
                    GameManager.GAME_MODE_BATTERY, scalingFactor,
                    USER_ID_1);
        });
        mockModifyGameModeGranted();
        assertEquals(0.7f, gameManagerService.getResolutionScalingFactor(mPackageName,
                GameManager.GAME_MODE_BATTERY, USER_ID_1), 0.001f);
    }

    @Test
    public void testUpdateResolutionScalingFactor_noUserId() {
        mockModifyGameModeGranted();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_2);
        final float scalingFactor = 0.123f;
        assertThrows(IllegalArgumentException.class, () -> {
            gameManagerService.updateResolutionScalingFactor(mPackageName,
                    GameManager.GAME_MODE_BATTERY, scalingFactor,
                    USER_ID_1);
        });
    }

    @Test
    public void testGetResolutionScalingFactor_permissionDenied() {
        mockModifyGameModeDenied();
        mockDeviceConfigAll();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_1);
        assertThrows(SecurityException.class, () -> {
            gameManagerService.getResolutionScalingFactor(mPackageName,
                    GameManager.GAME_MODE_BATTERY, USER_ID_1);
        });
    }

    @Test
    public void testGetResolutionScalingFactor_noUserId() {
        mockModifyGameModeGranted();
        mockDeviceConfigAll();
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        startUser(gameManagerService, USER_ID_2);
        assertThrows(IllegalArgumentException.class, () -> {
            gameManagerService.getResolutionScalingFactor(mPackageName,
                    GameManager.GAME_MODE_BATTERY, USER_ID_1);
        });
    }

    @Test
    public void testUpdateCustomGameModeConfiguration_permissionDenied() {
        mockModifyGameModeDenied();
        mockDeviceConfigAll();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        assertThrows(SecurityException.class, () -> {
            gameManagerService.updateCustomGameModeConfiguration(mPackageName,
                    new GameModeConfiguration.Builder().setScalingFactor(0.5f).build(),
                    USER_ID_1);
        });
    }

    @Test
    public void testUpdateCustomGameModeConfiguration_noUserId() {
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_2);
        assertThrows(IllegalArgumentException.class, () -> {
            gameManagerService.updateCustomGameModeConfiguration(mPackageName,
                    new GameModeConfiguration.Builder().setScalingFactor(0.5f).build(),
                    USER_ID_1);
        });
    }

    @Test
    public void testUpdateCustomGameModeConfiguration_nonGame() throws Exception {
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_IMAGE);
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.updateCustomGameModeConfiguration(mPackageName,
                new GameModeConfiguration.Builder().setScalingFactor(0.35f).setFpsOverride(
                        60).build(),
                USER_ID_1);
        assertFalse(gameManagerService.mHandler.hasMessages(WRITE_SETTINGS));
        GameManagerService.GamePackageConfiguration pkgConfig = gameManagerService.getConfig(
                mPackageName, USER_ID_1);
        assertNull(pkgConfig);
    }

    @Test
    public void testUpdateCustomGameModeConfiguration() throws InterruptedException {
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = Mockito.spy(createServiceAndStartUser(USER_ID_1));
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_CUSTOM, USER_ID_1);
        gameManagerService.updateCustomGameModeConfiguration(mPackageName,
                new GameModeConfiguration.Builder().setScalingFactor(0.35f).setFpsOverride(
                        60).build(),
                USER_ID_1);
        assertTrue(gameManagerService.mHandler.hasEqualMessages(WRITE_SETTINGS, USER_ID_1));
        assertTrue(
                gameManagerService.mHandler.hasEqualMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                        USER_ID_1));
        Mockito.verify(gameManagerService).setGameModeFrameRateOverride(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(60.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_CUSTOM, 60);

        GameManagerService.GamePackageConfiguration pkgConfig = gameManagerService.getConfig(
                mPackageName, USER_ID_1);
        assertNotNull(pkgConfig);
        GameManagerService.GamePackageConfiguration.GameModeConfiguration modeConfig =
                pkgConfig.getGameModeConfiguration(GameManager.GAME_MODE_CUSTOM);
        assertNotNull(modeConfig);
        assertEquals(modeConfig.getScaling(), 0.35f, 0.01f);
        assertEquals(modeConfig.getFps(), 60);
        // creates a new service to check that no data has been stored
        mTestLooper.dispatchAll();
        gameManagerService = createServiceAndStartUser(USER_ID_1);
        pkgConfig = gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertNull(pkgConfig);

        mTestLooper.moveTimeForward(WRITE_DELAY_MILLIS + 500);
        mTestLooper.dispatchAll();
        // creates a new service to check that data is persisted after delay
        gameManagerService = createServiceAndStartUser(USER_ID_1);
        assertEquals(GameManager.GAME_MODE_STANDARD,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        pkgConfig = gameManagerService.getConfig(mPackageName, USER_ID_1);
        assertNotNull(pkgConfig);
        modeConfig = pkgConfig.getGameModeConfiguration(GameManager.GAME_MODE_CUSTOM);
        assertNotNull(modeConfig);
        assertEquals(modeConfig.getScaling(), 0.35f, 0.01f);
        assertEquals(modeConfig.getFps(), 60);
    }

    @Test
    public void testWritingSettingFile_onShutdown() throws InterruptedException {
        mockModifyGameModeGranted();
        mockDeviceConfigAll();
        GameManagerService gameManagerService = new GameManagerService(mMockContext);
        gameManagerService.onBootCompleted();
        startUser(gameManagerService, USER_ID_1);
        Thread.sleep(500);
        gameManagerService.setGameModeConfigOverride("com.android.app1", USER_ID_1,
                GameManager.GAME_MODE_BATTERY, "60", "0.5");
        gameManagerService.setGameMode("com.android.app1", USER_ID_1,
                GameManager.GAME_MODE_PERFORMANCE);
        GameManagerSettings settings = new GameManagerSettings(
                InstrumentationRegistry.getContext().getFilesDir());
        Thread.sleep(500);
        // no data written as delayed messages are queued
        assertFalse(settings.readPersistentDataLocked());
        assertTrue(gameManagerService.mHandler.hasEqualMessages(WRITE_SETTINGS, USER_ID_1));
        Intent shutdown = new Intent();
        shutdown.setAction(Intent.ACTION_SHUTDOWN);
        mShutDownActionReceiver.onReceive(mMockContext, shutdown);
        Thread.sleep(500);
        // data is written on processing new message with no delay on shutdown,
        // and all queued messages should be removed
        assertTrue(settings.readPersistentDataLocked());
        assertFalse(gameManagerService.mHandler.hasEqualMessages(WRITE_SETTINGS, USER_ID_1));
    }

    @Test
    public void testResetInterventions_onDeviceConfigReset() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = Mockito.spy(new GameManagerService(mMockContext,
                mTestLooper.getLooper()));
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        Mockito.verify(gameManagerService).setGameModeFrameRateOverride(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(90.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);

        String configStringAfter = "";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringAfter);
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);
        Mockito.verify(gameManagerService).setGameModeFrameRateOverride(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(0.0f));
    }

    @Test
    public void testResetInterventions_onInterventionsDisabled() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = Mockito.spy(new GameManagerService(mMockContext,
                mTestLooper.getLooper()));
        startUser(gameManagerService, USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        Mockito.verify(gameManagerService).setGameModeFrameRateOverride(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(90.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);

        mockInterventionsDisabledNoOptInFromXml();
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);
        Mockito.verify(gameManagerService).setGameModeFrameRateOverride(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(0.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 0);
    }

    @Test
    public void testResetInterventions_onGameModeOverridden() throws Exception {
        mockModifyGameModeGranted();
        String configStringBefore =
                "mode=2,downscaleFactor=1.0,fps=90";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configStringBefore);
        mockInterventionsEnabledNoOptInFromXml();
        GameManagerService gameManagerService = Mockito.spy(new GameManagerService(mMockContext,
                mTestLooper.getLooper()));
        startUser(gameManagerService, USER_ID_1);

        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        Mockito.verify(gameManagerService).setGameModeFrameRateOverride(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(90.0f));
        checkFps(gameManagerService, GameManager.GAME_MODE_PERFORMANCE, 90);

        mockInterventionsEnabledAllOptInFromXml();
        gameManagerService.updateConfigsForUser(USER_ID_1, false, mPackageName);
        Mockito.verify(gameManagerService).setGameModeFrameRateOverride(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                ArgumentMatchers.eq(0.0f));
    }

    @Test
    public void testAddGameModeListener() throws RemoteException {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        mockDeviceConfigAll();
        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();

        IGameModeListener mockListener = Mockito.mock(IGameModeListener.class);
        IBinder binder = Mockito.mock(IBinder.class);
        when(mockListener.asBinder()).thenReturn(binder);
        gameManagerService.addGameModeListener(mockListener);
        verify(binder).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());

        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        verify(mockListener).onGameModeChanged(mPackageName, GameManager.GAME_MODE_STANDARD,
                GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        reset(mockListener);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        verify(mockListener).onGameModeChanged(mPackageName, GameManager.GAME_MODE_PERFORMANCE,
                GameManager.GAME_MODE_BATTERY, USER_ID_1);
        reset(mockListener);

        mDeathRecipientCaptor.getValue().binderDied();
        verify(binder).unlinkToDeath(eq(mDeathRecipientCaptor.getValue()), anyInt());
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_CUSTOM, USER_ID_1);
        verify(mockListener, never()).onGameModeChanged(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testRemoveGameModeListener() throws RemoteException {
        GameManagerService gameManagerService =
                new GameManagerService(mMockContext, mTestLooper.getLooper());
        mockDeviceConfigAll();
        startUser(gameManagerService, USER_ID_1);
        mockModifyGameModeGranted();

        IGameModeListener mockListener = Mockito.mock(IGameModeListener.class);
        IBinder binder = Mockito.mock(IBinder.class);
        when(mockListener.asBinder()).thenReturn(binder);

        gameManagerService.addGameModeListener(mockListener);
        gameManagerService.removeGameModeListener(mockListener);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        verify(mockListener, never()).onGameModeChanged(anyString(), anyInt(), anyInt(), anyInt());
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

    @Test
    public void testResetGamePowerMode() {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.onBootCompleted();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, false);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, false);
    }

    @Test
    public void testNotifyGraphicsEnvironmentSetup() {
        String configString = "mode=2,loadingBoost=2000";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        gameManagerService.notifyGraphicsEnvironmentSetup(mPackageName, USER_ID_1);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, true);
        reset(mMockPowerManager);
        assertTrue(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
        mTestLooper.moveTimeForward(2000);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, false);
    }

    @Test
    public void testNotifyGraphicsEnvironmentSetup_outOfBoundBoostValue() {
        String configString = "mode=2,loadingBoost=0:mode=3,loadingBoost=7000";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        gameManagerService.notifyGraphicsEnvironmentSetup(mPackageName, USER_ID_1);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, true);
        reset(mMockPowerManager);
        assertTrue(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
        mTestLooper.moveTimeForward(100);
        mTestLooper.dispatchAll();
        // 0 loading boost value should still trigger max timeout
        verify(mMockPowerManager, never()).setPowerMode(anyInt(), anyBoolean());
        assertTrue(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
        mTestLooper.moveTimeForward(LOADING_BOOST_MAX_DURATION);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, false);
        reset(mMockPowerManager);
        assertFalse(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));

        // 7000 loading boost value should exceed the max timeout of 5s and be bounded
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_BATTERY, USER_ID_1);
        gameManagerService.notifyGraphicsEnvironmentSetup(mPackageName, USER_ID_1);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, true);
        reset(mMockPowerManager);
        assertTrue(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
        mTestLooper.moveTimeForward(LOADING_BOOST_MAX_DURATION);
        mTestLooper.dispatchAll();
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME_LOADING, false);
        assertFalse(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
    }

    @Test
    public void testNotifyGraphicsEnvironmentSetup_noDeviceConfig() {
        mockDeviceConfigNone();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.notifyGraphicsEnvironmentSetup(mPackageName, USER_ID_1);
        verify(mMockPowerManager, never()).setPowerMode(anyInt(), anyBoolean());
        assertFalse(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
    }

    @Test
    public void testNotifyGraphicsEnvironmentSetup_noLoadingBoostValue() {
        mockDeviceConfigAll();
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.notifyGraphicsEnvironmentSetup(mPackageName, USER_ID_1);
        verify(mMockPowerManager, never()).setPowerMode(anyInt(), anyBoolean());
        assertFalse(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
    }

    @Test
    public void testNotifyGraphicsEnvironmentSetup_nonGame() throws Exception {
        String configString = "mode=2,loadingBoost=2000";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
        mockModifyGameModeGranted();
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_IMAGE);
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        gameManagerService.setGameMode(mPackageName, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_UNSUPPORTED,
                gameManagerService.getGameMode(mPackageName, USER_ID_1));
        gameManagerService.notifyGraphicsEnvironmentSetup(mPackageName, USER_ID_1);
        verify(mMockPowerManager, never()).setPowerMode(anyInt(), anyBoolean());
        assertFalse(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
    }

    @Test
    public void testNotifyGraphicsEnvironmentSetup_differentApp() throws Exception {
        String configString = "mode=2,loadingBoost=2000";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
        mockModifyGameModeGranted();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String someGamePkg = "some.game";
        mockAppCategory(someGamePkg, ApplicationInfo.CATEGORY_GAME);
        when(mMockPackageManager.getPackageUidAsUser(someGamePkg, USER_ID_1)).thenReturn(
                DEFAULT_PACKAGE_UID + 1);
        gameManagerService.setGameMode(someGamePkg, GameManager.GAME_MODE_PERFORMANCE, USER_ID_1);
        assertEquals(GameManager.GAME_MODE_PERFORMANCE,
                gameManagerService.getGameMode(someGamePkg, USER_ID_1));
        gameManagerService.notifyGraphicsEnvironmentSetup(someGamePkg, USER_ID_1);
        verify(mMockPowerManager, never()).setPowerMode(anyInt(), anyBoolean());
        assertFalse(gameManagerService.mHandler.hasMessages(CANCEL_GAME_LOADING_MODE));
    }

    @Test
    public void testGetInterventionList_permissionDenied() throws Exception {
        String configString = "mode=2,downscaleFactor=0.5";
        when(DeviceConfig.getProperty(anyString(), anyString()))
                .thenReturn(configString);
        mockQueryAllPackageDenied();
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        assertThrows(SecurityException.class,
                () -> gameManagerService.getInterventionList(mPackageName, USER_ID_1));

        mockQueryAllPackageGranted();
        String expectedInterventionListOutput = "\n[Name:" + mPackageName
                 + " Modes: {2=[Game Mode:2,Scaling:0.5,Use Angle:false,"
                 + "Fps:,Loading Boost Duration:-1]}]";
        assertEquals(expectedInterventionListOutput,
                gameManagerService.getInterventionList(mPackageName, USER_ID_1));
    }

    @Test
    public void testGamePowerMode_gamePackage() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TOP, 0, 0);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, true);
    }

    @Test
    public void testGamePowerMode_twoGames() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages1 = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages1);
        String someGamePkg = "some.game";
        String[] packages2 = {someGamePkg};
        int somePackageId = DEFAULT_PACKAGE_UID + 1;
        when(mMockPackageManager.getPackagesForUid(somePackageId)).thenReturn(packages2);
        HashMap<Integer, Boolean> powerState = new HashMap<>();
        doAnswer(inv -> powerState.put(inv.getArgument(0), inv.getArgument(1)))
                .when(mMockPowerManager).setPowerMode(anyInt(), anyBoolean());
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TOP, 0, 0);
        assertTrue(powerState.get(Mode.GAME));
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_TOP, 0, 0);
        assertTrue(powerState.get(Mode.GAME));
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        assertFalse(powerState.get(Mode.GAME));
    }

    @Test
    public void testGamePowerMode_twoGamesOverlap() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages1 = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages1);
        String someGamePkg = "some.game";
        String[] packages2 = {someGamePkg};
        int somePackageId = DEFAULT_PACKAGE_UID + 1;
        when(mMockPackageManager.getPackagesForUid(somePackageId)).thenReturn(packages2);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TOP, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_TOP, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, true);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, false);
    }

    @Test
    public void testGamePowerMode_released() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TOP, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        verify(mMockPowerManager, times(1)).setPowerMode(Mode.GAME, false);
    }

    @Test
    public void testGamePowerMode_noPackage() throws Exception {
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        verify(mMockPowerManager, times(0)).setPowerMode(Mode.GAME, true);
    }

    @Test
    public void testGamePowerMode_notAGamePackage() throws Exception {
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_IMAGE);
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {"someapp"};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        verify(mMockPowerManager, times(0)).setPowerMode(Mode.GAME, true);
    }

    @Test
    public void testGamePowerMode_notAGamePackageNotReleased() throws Exception {
        mockAppCategory(mPackageName, ApplicationInfo.CATEGORY_IMAGE);
        GameManagerService gameManagerService = createServiceAndStartUser(USER_ID_1);
        String[] packages = {"someapp"};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND, 0, 0);
        verify(mMockPowerManager, times(0)).setPowerMode(Mode.GAME, false);
    }

    @Test
    public void testGameDefaultFrameRate_FlagOn() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_GAME_MODE);

        GameManagerService gameManagerService = Mockito.spy(
                new GameManagerService(mMockContext, mTestLooper.getLooper(),
                        InstrumentationRegistry.getContext().getFilesDir(),
                        new Injector(){
                            @Override
                            public GameManagerServiceSystemPropertiesWrapper
                                    createSystemPropertiesWrapper() {
                                return mSysPropsMock;
                            }
                        }));

        when(mSysPropsMock.getInt(
                ArgumentMatchers.eq(PROPERTY_RO_SURFACEFLINGER_GAME_DEFAULT_FRAME_RATE),
                anyInt())).thenReturn(60);
        when(mSysPropsMock.getBoolean(
                ArgumentMatchers.eq(PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED),
                ArgumentMatchers.eq(true))).thenReturn(true);
        gameManagerService.onBootCompleted();

        // Set up a game in the foreground.
        String[] packages = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TOP, 0, 0);

        // Toggle game default frame rate on.
        gameManagerService.toggleGameDefaultFrameRate(true);

        // Verify that:
        // 1) The system property is set correctly
        // 2) setDefaultFrameRateOverride is called with correct arguments
        Mockito.verify(mSysPropsMock).set(
                ArgumentMatchers.eq(PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED),
                ArgumentMatchers.eq("true"));
        Mockito.verify(gameManagerService, times(1))
                .setGameDefaultFrameRateOverride(ArgumentMatchers.eq(DEFAULT_PACKAGE_UID),
                                                 ArgumentMatchers.eq(60.0f));

        // Adding another game to the foreground.
        String anotherGamePkg = "another.game";
        String[] packages2 = {anotherGamePkg};
        mockAppCategory(anotherGamePkg, ApplicationInfo.CATEGORY_GAME);
        int somePackageId = DEFAULT_PACKAGE_UID + 1;
        when(mMockPackageManager.getPackagesForUid(somePackageId)).thenReturn(packages2);

        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_TOP, 0, 0);

        // Toggle game default frame rate off.
        when(mSysPropsMock.getBoolean(
                ArgumentMatchers.eq(PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED),
                ArgumentMatchers.eq(true))).thenReturn(false);
        gameManagerService.toggleGameDefaultFrameRate(false);

        // Verify that:
        // 1) The system property is set correctly
        // 2) setDefaultFrameRateOverride is called with correct arguments
        Mockito.verify(mSysPropsMock).set(
                ArgumentMatchers.eq(PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED),
                ArgumentMatchers.eq("false"));
        Mockito.verify(gameManagerService).setGameDefaultFrameRateOverride(
                ArgumentMatchers.eq(DEFAULT_PACKAGE_UID), ArgumentMatchers.eq(0.0f));
        Mockito.verify(gameManagerService).setGameDefaultFrameRateOverride(
                ArgumentMatchers.eq(somePackageId), ArgumentMatchers.eq(0.0f));
    }

    @Test
    public void testGameDefaultFrameRate_FlagOff() throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_GAME_DEFAULT_FRAME_RATE);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_GAME_MODE);

        GameManagerService gameManagerService = Mockito.spy(
                new GameManagerService(mMockContext, mTestLooper.getLooper(),
                        InstrumentationRegistry.getContext().getFilesDir(),
                        new Injector(){
                            @Override
                            public GameManagerServiceSystemPropertiesWrapper
                                    createSystemPropertiesWrapper() {
                                return mSysPropsMock;
                            }
                        }));

        // Set up a game in the foreground.
        String[] packages = {mPackageName};
        when(mMockPackageManager.getPackagesForUid(DEFAULT_PACKAGE_UID)).thenReturn(packages);
        gameManagerService.mUidObserver.onUidStateChanged(
                DEFAULT_PACKAGE_UID, ActivityManager.PROCESS_STATE_TOP, 0, 0);

        // Toggle game default frame rate on.
        when(mSysPropsMock.getInt(
                ArgumentMatchers.eq(PROPERTY_RO_SURFACEFLINGER_GAME_DEFAULT_FRAME_RATE),
                anyInt())).thenReturn(60);
        when(mSysPropsMock.getBoolean(
                ArgumentMatchers.eq(PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED),
                ArgumentMatchers.eq(true))).thenReturn(true);

        gameManagerService.toggleGameDefaultFrameRate(true);

        // Verify that:
        // 1) System property is never set
        // 2) setGameDefaultFrameRateOverride() should never be called if the flag is disabled.
        Mockito.verify(mSysPropsMock, never()).set(
                ArgumentMatchers.eq(PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED),
                anyString());
        Mockito.verify(gameManagerService, never())
                .setGameDefaultFrameRateOverride(anyInt(), anyFloat());

        // Toggle game default frame rate off.
        String anotherGamePkg = "another.game";
        String[] packages2 = {anotherGamePkg};
        mockAppCategory(anotherGamePkg, ApplicationInfo.CATEGORY_GAME);
        int somePackageId = DEFAULT_PACKAGE_UID + 1;
        when(mMockPackageManager.getPackagesForUid(somePackageId)).thenReturn(packages2);
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_TOP, 0, 0);
        gameManagerService.mUidObserver.onUidStateChanged(
                somePackageId, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0, 0);
        when(mSysPropsMock.getBoolean(
                ArgumentMatchers.eq(PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED),
                ArgumentMatchers.eq(true))).thenReturn(false);

        gameManagerService.toggleGameDefaultFrameRate(false);
        // Verify that:
        // 1) System property is never set
        // 2) setGameDefaultFrameRateOverride() should never be called if the flag is disabled.
        Mockito.verify(mSysPropsMock, never()).set(
                ArgumentMatchers.eq(PROPERTY_PERSISTENT_GFX_GAME_DEFAULT_FRAME_RATE_ENABLED),
                anyString());
        Mockito.verify(gameManagerService, never())
                .setGameDefaultFrameRateOverride(anyInt(), anyFloat());
    }
}
