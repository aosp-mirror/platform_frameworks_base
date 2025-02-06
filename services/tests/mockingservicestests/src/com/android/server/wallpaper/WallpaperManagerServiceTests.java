/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wallpaper;

import static android.app.WallpaperManager.COMMAND_REAPPLY;
import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.os.FileObserver.CLOSE_WRITE;
import static android.os.UserHandle.MIN_SECONDARY_USER_ID;
import static android.os.UserHandle.USER_SYSTEM;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.Flags;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.app.wallpaper.WallpaperDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.IWallpaperEngine;
import android.service.wallpaper.IWallpaperService;
import android.service.wallpaper.WallpaperService;
import android.testing.TestableContext;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Display;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.R;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Tests for the {@link WallpaperManagerService} class.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:WallpaperManagerServiceTests
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WallpaperManagerServiceTests {

    private static final String TAG = "WallpaperManagerServiceTests";
    private static final int DISPLAY_SIZE_DIMENSION = 100;
    private static StaticMockitoSession sMockitoSession;

    @ClassRule
    public static final TestableContext sContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);

    private static ComponentName sImageWallpaperComponentName;
    private static ComponentName sDefaultWallpaperComponent;

    private static ComponentName sFallbackWallpaperComponentName;

    private IPackageManager mIpm = AppGlobals.getPackageManager();

    private Resources mResources = sContext.getResources();

    @Mock
    private DisplayManager mDisplayManager;

    private final TemporaryFolder mFolder = new TemporaryFolder();
    private final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule
    public RuleChain rules = RuleChain.outerRule(mSetFlagsRule).around(mFolder);

    private final SparseArray<File> mTempDirs = new SparseArray<>();
    private WallpaperManagerService mService;
    private static IWallpaperConnection.Stub sWallpaperService;

    private static WindowManagerInternal sWindowManagerInternal;

    @BeforeClass
    public static void setUpClass() {
        sMockitoSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(WallpaperUtils.class)
                .spyStatic(LocalServices.class)
                .spyStatic(WallpaperManager.class)
                .startMocking();

        sWindowManagerInternal = mock(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, sWindowManagerInternal);

        sContext.addMockSystemService(Context.APP_OPS_SERVICE, mock(AppOpsManager.class));

        spyOn(sContext);
        sContext.getTestablePermissions().setPermission(
                android.Manifest.permission.SET_WALLPAPER_COMPONENT,
                PackageManager.PERMISSION_GRANTED);
        sContext.getTestablePermissions().setPermission(
                android.Manifest.permission.SET_WALLPAPER,
                PackageManager.PERMISSION_GRANTED);
        sContext.getTestablePermissions().setPermission(
                android.Manifest.permission.SET_WALLPAPER_DIM_AMOUNT,
                PackageManager.PERMISSION_GRANTED);
        sContext.getTestablePermissions().setPermission(
                android.Manifest.permission.READ_WALLPAPER_INTERNAL,
                PackageManager.PERMISSION_GRANTED);
        doNothing().when(sContext).sendBroadcastAsUser(any(), any());

        //Wallpaper components
        sWallpaperService = mock(IWallpaperConnection.Stub.class);
        sImageWallpaperComponentName = ComponentName.unflattenFromString(
                sContext.getResources().getString(R.string.image_wallpaper_component));
        // Mock default wallpaper as image wallpaper if there is no pre-defined default wallpaper.
        sDefaultWallpaperComponent = WallpaperManager.getCmfDefaultWallpaperComponent(sContext);
        sFallbackWallpaperComponentName = ComponentName.unflattenFromString(
                sContext.getResources().getString(R.string.fallback_wallpaper_component));

        if (sDefaultWallpaperComponent == null) {
            sDefaultWallpaperComponent = sImageWallpaperComponentName;
            doReturn(sImageWallpaperComponentName).when(() ->
                    WallpaperManager.getCmfDefaultWallpaperComponent(any()));
        } else {
            sContext.addMockService(sDefaultWallpaperComponent, sWallpaperService);
        }

        sContext.addMockService(sImageWallpaperComponentName, sWallpaperService);
        if (sFallbackWallpaperComponentName != null) {
            sContext.addMockService(sFallbackWallpaperComponentName, sWallpaperService);
        }
    }

    @AfterClass
    public static void tearDownClass() {
        if (sMockitoSession != null) {
            sMockitoSession.finishMocking();
            sMockitoSession = null;
        }
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        sImageWallpaperComponentName = null;
        sDefaultWallpaperComponent = null;
        sFallbackWallpaperComponentName = null;
        reset(sContext);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        ExtendedMockito.doAnswer(invocation -> {
            int userId = (invocation.getArgument(0));
            return getWallpaperTestDir(userId);
        }).when(() -> WallpaperUtils.getWallpaperDir(anyInt()));

        sContext.addMockSystemService(DisplayManager.class, mDisplayManager);

        final Display mockDisplay = mock(Display.class);
        doReturn(DISPLAY_SIZE_DIMENSION).when(mockDisplay).getMaximumSizeDimension();
        doReturn(mockDisplay).when(mDisplayManager).getDisplay(anyInt());

        final Display[] displays = new Display[]{mockDisplay};
        doReturn(displays).when(mDisplayManager).getDisplays();

        spyOn(mIpm);
        spyOn(mResources);
        doReturn(true).when(mResources).getBoolean(eq(R.bool.config_isDesktopModeSupported));
        mService = new TestWallpaperManagerService(sContext);
        spyOn(mService);
        mService.systemReady();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(WallpaperManagerInternal.class);

        mTempDirs.clear();
        reset(mIpm);
        mService = null;
    }

    private File getWallpaperTestDir(int userId) {
        File tempDir = mTempDirs.get(userId);
        if (tempDir == null) {
            try {
                tempDir = mFolder.newFolder(String.valueOf(userId));
                mTempDirs.append(userId, tempDir);
            } catch (IOException e) {
                Log.e(TAG, "getWallpaperTestDir failed at userId= " + userId);
            }
        }
        return tempDir;
    }

    protected class TestWallpaperManagerService extends WallpaperManagerService {

        TestWallpaperManagerService(Context context) {
            super(context);
        }

        // Always return true for test
        @Override
        public boolean isWallpaperSupported(String callingPackage) {
            return true;
        }

        // Always return true for test
        @Override
        public boolean isSetWallpaperAllowed(String callingPackage) {
            return true;
        }
    }

    /**
     * Tests that the fundamental fields are set by the main WallpaperData constructor
     */
    @Test
    public void testWallpaperDataConstructor() {
        final int testUserId = MIN_SECONDARY_USER_ID;
        for (int which: List.of(FLAG_LOCK, FLAG_SYSTEM)) {
            WallpaperData newWallpaperData = new WallpaperData(testUserId, which);
            assertEquals(which, newWallpaperData.mWhich);
            assertEquals(testUserId, newWallpaperData.userId);

            WallpaperData wallpaperData = mService.getWallpaperSafeLocked(testUserId, which);
            assertEquals(wallpaperData.getCropFile().getAbsolutePath(),
                    newWallpaperData.getCropFile().getAbsolutePath());
            assertEquals(wallpaperData.getWallpaperFile().getAbsolutePath(),
                    newWallpaperData.getWallpaperFile().getAbsolutePath());
        }
    }

    /**
     * Tests that internal basic data should be correct after boot up.
     */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void testDataCorrectAfterBoot() {
        mService.switchUser(USER_SYSTEM, null);

        final WallpaperData fallbackData = mService.mFallbackWallpaper;
        assertEquals("Fallback wallpaper component should be ImageWallpaper.",
                sImageWallpaperComponentName, fallbackData.getComponent());

        verifyLastWallpaperData(USER_SYSTEM, sDefaultWallpaperComponent);
        verifyDisplayData();
    }

    /**
     * Tests setWallpaperComponent and clearWallpaper should work as expected.
     */
    @Test
    public void testSetThenClearComponent() {
        // Skip if there is no pre-defined default wallpaper component.
        assumeThat(sDefaultWallpaperComponent,
                not(CoreMatchers.equalTo(sImageWallpaperComponentName)));

        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        verifyLastWallpaperData(testUserId, sDefaultWallpaperComponent);
        verifyCurrentSystemData(testUserId);

        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_SYSTEM, testUserId);
        verifyLastWallpaperData(testUserId, sImageWallpaperComponentName);
        verifyCurrentSystemData(testUserId);

        mService.clearWallpaper(null, FLAG_SYSTEM, testUserId);
        verifyLastWallpaperData(testUserId, sDefaultWallpaperComponent);
        verifyCurrentSystemData(testUserId);
    }

    /**
     * Test setWallpaperComponent with FLAG_LOCK should update the mLastLockWallpaper.
     */
    @Test
    public void testSetLockWallpaper() {
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        verifyLastWallpaperData(testUserId, sDefaultWallpaperComponent);
        verifyCurrentSystemData(testUserId);

        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_LOCK, testUserId);
        verifyLastLockWallpaperData(testUserId, sImageWallpaperComponentName);
        verifyCurrentSystemData(testUserId);
    }

    /**
     * Test setWallpaperComponent with FLAG_LOCK and then setWallpaperComponent with
     * FLAG_LOCK | FLAG_SYSTEM should set mLastLockWallpaper to null.
     */
    @Test
    public void testSetLockWallpaperThenSetLockAndSystemWallpaper_setLastLockWallpaperToNull() {
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        verifyLastWallpaperData(testUserId, sDefaultWallpaperComponent);
        verifyCurrentSystemData(testUserId);

        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_LOCK, testUserId);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_LOCK | FLAG_SYSTEM, testUserId);

        verifyLastWallpaperData(testUserId, sImageWallpaperComponentName);
        assertThat(mService.mLastLockWallpaper).isNull();
        verifyCurrentSystemData(testUserId);
    }

    /**
     * Tests that when setWallpaperComponent is called with the currently set component, a command
     * is issued to the wallpaper.
     */
    @Test
    @Ignore("b/368345733")
    public void testSetCurrentComponent() throws Exception {
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        verifyLastWallpaperData(testUserId, sDefaultWallpaperComponent);
        verifyCurrentSystemData(testUserId);

        spyOn(mService.mWallpaperDisplayHelper);
        doReturn(true).when(mService.mWallpaperDisplayHelper)
                .isUsableDisplay(any(Display.class),
                        eq(mService.mLastWallpaper.connection.mClientUid));
        mService.mLastWallpaper.connection.attachEngine(mock(IWallpaperEngine.class),
                DEFAULT_DISPLAY);

        WallpaperManagerService.DisplayConnector connector =
                mService.mLastWallpaper.connection.getDisplayConnectorOrCreate(DEFAULT_DISPLAY);
        mService.setWallpaperComponent(sDefaultWallpaperComponent, sContext.getOpPackageName(),
                FLAG_SYSTEM, testUserId);

        verify(connector.mEngine).dispatchWallpaperCommand(
                eq(COMMAND_REAPPLY), anyInt(), anyInt(), anyInt(), any());
    }

    /**
     * Tests internal data should be correct and no crash after switch user continuously.
     */
    @Test
    public void testSwitchMultipleUsers() throws Exception {
        final int lastUserId = 5;
        final ServiceInfo pi = mIpm.getServiceInfo(sDefaultWallpaperComponent,
                PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, 0);
        doReturn(pi).when(mIpm).getServiceInfo(any(), anyLong(), anyInt());

        final Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
        final ParceledListSlice ris =
                mIpm.queryIntentServices(intent,
                        intent.resolveTypeIfNeeded(sContext.getContentResolver()),
                        PackageManager.GET_META_DATA, 0);
        doReturn(ris).when(mIpm).queryIntentServices(any(), any(), anyLong(), anyInt());
        doReturn(PackageManager.PERMISSION_GRANTED).when(mIpm).checkPermission(
                eq(android.Manifest.permission.AMBIENT_WALLPAPER), any(), anyInt());

        for (int userId = 0; userId <= lastUserId; userId++) {
            mService.switchUser(userId, null);
            verifyLastWallpaperData(userId, sDefaultWallpaperComponent);
            verifyCurrentSystemData(userId);
        }
        verifyNoConnectionBeforeLastUser(lastUserId);
    }

    /**
     * Tests internal data should be correct and no crash after switch user + unlock user
     * continuously.
     * Simulating that the selected WallpaperService is not built-in. After switching users, the
     * service should not be bound, but bound to the image wallpaper. After receiving the user
     * unlock callback and can find the selected service for the user, the selected service should
     * be bound.
     */
    @Test
    public void testSwitchThenUnlockMultipleUsers() throws Exception {
        final int lastUserId = 5;
        final ServiceInfo pi = mIpm.getServiceInfo(sDefaultWallpaperComponent,
                PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, 0);
        doReturn(pi).when(mIpm).getServiceInfo(any(), anyLong(), anyInt());

        final Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
        final ParceledListSlice ris =
                mIpm.queryIntentServices(intent,
                        intent.resolveTypeIfNeeded(sContext.getContentResolver()),
                        PackageManager.GET_META_DATA, 0);
        doReturn(PackageManager.PERMISSION_GRANTED).when(mIpm).checkPermission(
                eq(android.Manifest.permission.AMBIENT_WALLPAPER), any(), anyInt());

        for (int userId = 1; userId <= lastUserId; userId++) {
            mService.switchUser(userId, null);
            verifyLastWallpaperData(userId, sImageWallpaperComponentName);
            // Simulate user unlocked
            doReturn(ris).when(mIpm).queryIntentServices(any(), any(), anyLong(), eq(userId));
            mService.onUnlockUser(userId);
            verifyLastWallpaperData(userId, sDefaultWallpaperComponent);
            verifyCurrentSystemData(userId);
        }
        verifyNoConnectionBeforeLastUser(lastUserId);
        verifyDisplayData();
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_NEXT_WALLPAPER_COMPONENT)
    public void testSaveLoadSettings_withoutWallpaperDescription()
            throws IOException, XmlPullParserException {
        WallpaperData expectedData = mService.getCurrentWallpaperData(FLAG_SYSTEM, 0);
        expectedData.setComponent(sDefaultWallpaperComponent);
        expectedData.primaryColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        expectedData.mWallpaperDimAmount = 0.5f;
        expectedData.mUidToDimAmount.put(0, 0.5f);
        expectedData.mUidToDimAmount.put(1, 0.4f);

        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newBinarySerializer();
        serializer.setOutput(ostream, StandardCharsets.UTF_8.name());
        mService.mWallpaperDataParser.saveSettingsToSerializer(serializer, expectedData, null);
        ostream.close();

        WallpaperData actualData = new WallpaperData(0, FLAG_SYSTEM);
        ByteArrayInputStream istream = new ByteArrayInputStream(ostream.toByteArray());
        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        parser.setInput(istream, StandardCharsets.UTF_8.name());
        mService.mWallpaperDataParser.loadSettingsFromSerializer(parser,
                actualData, /* userId= */0, /* loadSystem= */ true, /* loadLock= */
                false, /* keepDimensionHints= */ true,
                new WallpaperDisplayHelper.DisplayData(0));

        assertThat(actualData.getComponent()).isEqualTo(expectedData.getComponent());
        assertThat(actualData.primaryColors).isEqualTo(expectedData.primaryColors);
        assertThat(actualData.mWallpaperDimAmount).isEqualTo(expectedData.mWallpaperDimAmount);
        assertThat(actualData.mUidToDimAmount).isNotNull();
        assertThat(actualData.mUidToDimAmount.size()).isEqualTo(
                expectedData.mUidToDimAmount.size());
        for (int i = 0; i < actualData.mUidToDimAmount.size(); i++) {
            int key = actualData.mUidToDimAmount.keyAt(0);
            assertThat(actualData.mUidToDimAmount.get(key)).isEqualTo(
                    expectedData.mUidToDimAmount.get(key));
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_NEXT_WALLPAPER_COMPONENT)
    public void testSaveLoadSettings_withWallpaperDescription()
            throws IOException, XmlPullParserException {
        WallpaperData expectedData = mService.getCurrentWallpaperData(FLAG_SYSTEM, 0);
        expectedData.setComponent(sDefaultWallpaperComponent);
        PersistableBundle content = new PersistableBundle();
        content.putString("ckey", "cvalue");
        WallpaperDescription description = new WallpaperDescription.Builder()
                .setComponent(sDefaultWallpaperComponent).setId("testId").setTitle("fake one")
                .setContent(content).build();
        expectedData.setDescription(description);

        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newBinarySerializer();
        serializer.setOutput(ostream, StandardCharsets.UTF_8.name());
        mService.mWallpaperDataParser.saveSettingsToSerializer(serializer, expectedData, null);
        ostream.close();

        WallpaperData actualData = new WallpaperData(0, FLAG_SYSTEM);
        ByteArrayInputStream istream = new ByteArrayInputStream(ostream.toByteArray());
        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        parser.setInput(istream, StandardCharsets.UTF_8.name());
        mService.mWallpaperDataParser.loadSettingsFromSerializer(parser,
                actualData, /* userId= */0, /* loadSystem= */ true, /* loadLock= */
                false, /* keepDimensionHints= */ true,
                new WallpaperDisplayHelper.DisplayData(0));

        assertThat(actualData.getComponent()).isEqualTo(expectedData.getComponent());
        assertThat(actualData.getDescription()).isEqualTo(expectedData.getDescription());
    }

    @Test
    @DisableFlags(Flags.FLAG_REMOVE_NEXT_WALLPAPER_COMPONENT)
    public void testSaveLoadSettings_legacyNextComponent()
            throws IOException, XmlPullParserException {
        WallpaperData systemWallpaperData = mService.getCurrentWallpaperData(FLAG_SYSTEM, 0);
        systemWallpaperData.setComponent(sDefaultWallpaperComponent);
        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newBinarySerializer();
        serializer.setOutput(ostream, StandardCharsets.UTF_8.name());
        mService.mWallpaperDataParser.saveSettingsToSerializer(serializer, systemWallpaperData,
                null);
        ostream.close();

        WallpaperData shouldMatchSystem = new WallpaperData(0, FLAG_SYSTEM);
        ByteArrayInputStream istream = new ByteArrayInputStream(ostream.toByteArray());
        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        parser.setInput(istream, StandardCharsets.UTF_8.name());
        mService.mWallpaperDataParser.loadSettingsFromSerializer(parser,
                shouldMatchSystem, /* userId= */0, /* loadSystem= */ true, /* loadLock= */
                false, /* keepDimensionHints= */ true,
                new WallpaperDisplayHelper.DisplayData(0));

        assertThat(shouldMatchSystem.nextWallpaperComponent).isEqualTo(
                systemWallpaperData.getComponent());
        assertThat(shouldMatchSystem.primaryColors).isEqualTo(systemWallpaperData.primaryColors);
    }

    @Test
    @Ignore("b/372942682")
    public void testWallpaperManagerCallbackInRightOrder() throws RemoteException {
        WallpaperData wallpaper = new WallpaperData(USER_SYSTEM, FLAG_SYSTEM);
        wallpaper.primaryColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        spyOn(wallpaper);
        doReturn(wallpaper).when(mService).getWallpaperSafeLocked(wallpaper.userId, FLAG_SYSTEM);
        doNothing().when(mService).switchWallpaper(any(), any());
        doReturn(true).when(mService)
                .bindWallpaperComponentLocked(isA(ComponentName.class), anyBoolean(), anyBoolean(),
                        any(), any());
        doNothing().when(mService).saveSettingsLocked(wallpaper.userId);
        spyOn(mService.mWallpaperCropper);
        doNothing().when(mService.mWallpaperCropper).generateCrop(wallpaper);

        // timestamps of {ACTION_WALLPAPER_CHANGED, onWallpaperColorsChanged}
        final long[] timestamps = new long[2];
        doAnswer(invocation -> timestamps[0] = SystemClock.elapsedRealtime())
                .when(sContext).sendBroadcastAsUser(any(), any());
        doAnswer(invocation -> timestamps[1] = SystemClock.elapsedRealtime())
                .when(mService).notifyWallpaperColorsChanged(wallpaper);

        assertNull(wallpaper.wallpaperObserver);
        mService.switchUser(wallpaper.userId, null);
        assertNotNull(wallpaper.wallpaperObserver);
        // We will call onEvent directly, so stop watching the file.
        wallpaper.wallpaperObserver.stopWatching();

        spyOn(wallpaper.wallpaperObserver);
        doReturn(wallpaper).when(wallpaper.wallpaperObserver).dataForEvent(false);
        wallpaper.wallpaperObserver.onEvent(CLOSE_WRITE, WALLPAPER);

        // ACTION_WALLPAPER_CHANGED should be invoked before onWallpaperColorsChanged.
        assertTrue(timestamps[1] > timestamps[0]);
    }

    @Test
    public void testSetWallpaperDimAmount() throws RemoteException {
        mService.switchUser(USER_SYSTEM, null);
        float dimAmount = 0.7f;
        mService.setWallpaperDimAmount(dimAmount);
        assertEquals("Getting dim amount should match after setting the dim amount",
                mService.getWallpaperDimAmount(), dimAmount, 0.0);
    }

    @Test
    public void testGetAdjustedWallpaperColorsOnDimming() throws RemoteException {
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        mService.setWallpaperComponent(sDefaultWallpaperComponent, sContext.getOpPackageName(),
                FLAG_SYSTEM, testUserId);
        WallpaperData wallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM, testUserId);

        // Mock a wallpaper data with color hints that support dark text and dark theme
        // but not HINT_FROM_BITMAP
        wallpaper.primaryColors = new WallpaperColors(Color.valueOf(Color.WHITE), null, null,
                WallpaperColors.HINT_SUPPORTS_DARK_TEXT | WallpaperColors.HINT_SUPPORTS_DARK_THEME);
        mService.setWallpaperDimAmount(0.6f);
        int colorHints = mService.getAdjustedWallpaperColorsOnDimming(wallpaper).getColorHints();
        // Dimmed wallpaper not extracted from bitmap does not support dark text and dark theme
        assertNotEquals(WallpaperColors.HINT_SUPPORTS_DARK_TEXT,
                colorHints & WallpaperColors.HINT_SUPPORTS_DARK_TEXT);
        assertNotEquals(WallpaperColors.HINT_SUPPORTS_DARK_THEME,
                colorHints & WallpaperColors.HINT_SUPPORTS_DARK_THEME);

        // Remove dimming
        mService.setWallpaperDimAmount(0f);
        colorHints = mService.getAdjustedWallpaperColorsOnDimming(wallpaper).getColorHints();
        // Undimmed wallpaper not extracted from bitmap does support dark text and dark theme
        assertEquals(WallpaperColors.HINT_SUPPORTS_DARK_TEXT,
                colorHints & WallpaperColors.HINT_SUPPORTS_DARK_TEXT);
        assertEquals(WallpaperColors.HINT_SUPPORTS_DARK_THEME,
                colorHints & WallpaperColors.HINT_SUPPORTS_DARK_THEME);

        // Mock a wallpaper data with color hints that support dark text and dark theme
        // and was extracted from bitmap
        wallpaper.primaryColors = new WallpaperColors(Color.valueOf(Color.WHITE), null, null,
                WallpaperColors.HINT_SUPPORTS_DARK_TEXT | WallpaperColors.HINT_SUPPORTS_DARK_THEME
                        | WallpaperColors.HINT_FROM_BITMAP);
        mService.setWallpaperDimAmount(0.6f);
        colorHints = mService.getAdjustedWallpaperColorsOnDimming(wallpaper).getColorHints();
        // Dimmed wallpaper should still support dark text and dark theme
        assertEquals(WallpaperColors.HINT_SUPPORTS_DARK_TEXT,
                colorHints & WallpaperColors.HINT_SUPPORTS_DARK_TEXT);
        assertEquals(WallpaperColors.HINT_SUPPORTS_DARK_THEME,
                colorHints & WallpaperColors.HINT_SUPPORTS_DARK_THEME);
    }

    @Test
    public void getWallpaperWithFeature_getCropped_returnsCropFile() throws Exception {
        File cropSystemWallpaperFile =
                new WallpaperData(USER_SYSTEM, FLAG_SYSTEM).getCropFile();
        cropSystemWallpaperFile.getParentFile().mkdirs();
        cropSystemWallpaperFile.createNewFile();
        try (FileOutputStream outputStream = new FileOutputStream(cropSystemWallpaperFile)) {
            outputStream.write("Crop system wallpaper".getBytes());
        }

        ParcelFileDescriptor pfd =
                mService.getWallpaperWithFeature(
                        sContext.getPackageName(),
                        sContext.getAttributionTag(),
                        /* cb= */ null,
                        FLAG_SYSTEM,
                        /* outParams= */ null,
                        USER_SYSTEM,
                        /* getCropped= */ true);

        assertPfdAndFileContentsEqual(pfd, cropSystemWallpaperFile);
    }

    @Test
    public void getWallpaperWithFeature_notGetCropped_returnsOriginalFile() throws Exception {
        File originalSystemWallpaperFile =
                new WallpaperData(USER_SYSTEM, FLAG_SYSTEM).getWallpaperFile();
        originalSystemWallpaperFile.getParentFile().mkdirs();
        originalSystemWallpaperFile.createNewFile();
        try (FileOutputStream outputStream = new FileOutputStream(originalSystemWallpaperFile)) {
            outputStream.write("Original system wallpaper".getBytes());
        }

        ParcelFileDescriptor pfd =
                mService.getWallpaperWithFeature(
                        sContext.getPackageName(),
                        sContext.getAttributionTag(),
                        /* cb= */ null,
                        FLAG_SYSTEM,
                        /* outParams= */ null,
                        USER_SYSTEM,
                        /* getCropped= */ false);

        assertPfdAndFileContentsEqual(pfd, originalSystemWallpaperFile);
    }

    // Verify a secondary display added started
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void displayAdded_sameSystemAndLockWallpaper_shouldAttachWallpaperServiceOnce()
            throws Exception {
        // GIVEN the same wallpaper used for the lock and system.
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        final WallpaperData wallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM, testUserId);
        IWallpaperService mockIWallpaperService = mock(IWallpaperService.class);
        wallpaper.connection.mService = mockIWallpaperService;
        // GIVEN there are two displays: DEFAULT_DISPLAY, 2
        final int testDisplayId = 2;
        setUpDisplays(List.of(DEFAULT_DISPLAY, testDisplayId));

        // WHEN display ID, 2, is ready.
        WallpaperManagerInternal wallpaperManagerInternal = LocalServices.getService(
                WallpaperManagerInternal.class);
        wallpaperManagerInternal.onDisplayAddSystemDecorations(testDisplayId);

        // Then there is a connection established for the system & lock wallpaper for display ID, 2.
        verify(mockIWallpaperService).attach(
                /* connection= */ eq(wallpaper.connection),
                /* windowToken= */ any(),
                /* windowType= */ anyInt(),
                /* isPreview= */ anyBoolean(),
                /* reqWidth= */ anyInt(),
                /* reqHeight= */ anyInt(),
                /* padding= */ any(),
                /* displayId= */ eq(testDisplayId),
                /* which= */ eq(FLAG_SYSTEM | FLAG_LOCK),
                /* info= */ any(),
                /* description= */ any());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void displayAdded_differentSystemAndLockWallpapers_shouldAttachWallpaperServiceTwice()
            throws Exception {
        // GIVEN different wallpapers used for the lock and system.
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_LOCK, testUserId);
        final WallpaperData systemWallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM,
                testUserId);
        final WallpaperData lockWallpaper = mService.getCurrentWallpaperData(FLAG_LOCK,
                testUserId);
        IWallpaperService mockIWallpaperService = mock(IWallpaperService.class);
        systemWallpaper.connection.mService = mockIWallpaperService;
        lockWallpaper.connection.mService = mockIWallpaperService;
        // GIVEN there are two displays: DEFAULT_DISPLAY, 2
        final int testDisplayId = 2;
        setUpDisplays(List.of(DEFAULT_DISPLAY, testDisplayId));

        // WHEN display ID, 2, is ready.
        WallpaperManagerInternal wallpaperManagerInternal = LocalServices.getService(
                WallpaperManagerInternal.class);
        wallpaperManagerInternal.onDisplayAddSystemDecorations(testDisplayId);

        // Then there is a connection established for the system wallpaper for display ID, 2.
        verify(mockIWallpaperService).attach(
                /* connection= */ eq(systemWallpaper.connection),
                /* windowToken= */ any(),
                /* windowType= */ anyInt(),
                /* isPreview= */ anyBoolean(),
                /* reqWidth= */ anyInt(),
                /* reqHeight= */ anyInt(),
                /* padding= */ any(),
                /* displayId= */ eq(testDisplayId),
                /* which= */ eq(FLAG_SYSTEM),
                /* info= */ any(),
                /* description= */ any());
        // Then there is a connection established for the lock wallpaper for display ID, 2.
        verify(mockIWallpaperService).attach(
                /* connection= */ eq(lockWallpaper.connection),
                /* windowToken= */ any(),
                /* windowType= */ anyInt(),
                /* isPreview= */ anyBoolean(),
                /* reqWidth= */ anyInt(),
                /* reqHeight= */ anyInt(),
                /* padding= */ any(),
                /* displayId= */ eq(testDisplayId),
                /* which= */ eq(FLAG_LOCK),
                /* info= */ any(),
                /* description= */ any());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void displayAdded_wallpaperIncompatibleForDisplay_shouldAttachFallbackWallpaperService()
            throws Exception {
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        IWallpaperService mockIWallpaperService = mock(IWallpaperService.class);
        mService.mFallbackWallpaper.connection.mService = mockIWallpaperService;
        // GIVEN there are two displays: DEFAULT_DISPLAY, 2
        final int testDisplayId = 2;
        setUpDisplays(List.of(DEFAULT_DISPLAY, testDisplayId));
        // GIVEN the wallpaper isn't compatible with display ID, 2
        mService.removeWallpaperCompatibleDisplayForTest(testDisplayId);

        // WHEN display ID, 2, is ready.
        WallpaperManagerInternal wallpaperManagerInternal = LocalServices.getService(
                WallpaperManagerInternal.class);
        wallpaperManagerInternal.onDisplayAddSystemDecorations(testDisplayId);

        // Then there is a connection established for the fallback wallpaper for display ID, 2.
        verify(mockIWallpaperService).attach(
                /* connection= */ eq(mService.mFallbackWallpaper.connection),
                /* windowToken= */ any(),
                /* windowType= */ anyInt(),
                /* isPreview= */ anyBoolean(),
                /* reqWidth= */ anyInt(),
                /* reqHeight= */ anyInt(),
                /* padding= */ any(),
                /* displayId= */ eq(testDisplayId),
                /* which= */ eq(FLAG_SYSTEM | FLAG_LOCK),
                /* info= */ any(),
                /* description= */ any());
    }
    // Verify a secondary display added end

    // Verify a secondary display removed started
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void displayRemoved_sameSystemAndLockWallpaper_shouldDetachWallpaperServiceOnce()
            throws Exception {
        ArgumentCaptor<DisplayListener> displayListenerCaptor = ArgumentCaptor.forClass(
                DisplayListener.class);
        verify(mDisplayManager).registerDisplayListener(displayListenerCaptor.capture(), eq(null));
        // GIVEN the same wallpaper used for the lock and system.
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        final WallpaperData wallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM, testUserId);
        IWallpaperService mockIWallpaperService = mock(IWallpaperService.class);
        wallpaper.connection.mService = mockIWallpaperService;
        // GIVEN there are two displays: DEFAULT_DISPLAY, 2
        final int testDisplayId = 2;
        setUpDisplays(List.of(DEFAULT_DISPLAY, testDisplayId));
        // GIVEN wallpaper connections have been established for display ID, 2.
        WallpaperManagerInternal wallpaperManagerInternal = LocalServices.getService(
                WallpaperManagerInternal.class);
        wallpaperManagerInternal.onDisplayAddSystemDecorations(testDisplayId);
        // Save displayConnector for displayId 2 before display removal.
        WallpaperManagerService.DisplayConnector displayConnector =
                wallpaper.connection.getDisplayConnectorOrCreate(testDisplayId);

        // WHEN display ID, 2, is removed.
        DisplayListener displayListener = displayListenerCaptor.getValue();
        displayListener.onDisplayRemoved(testDisplayId);

        // Then the wallpaper connection for display ID, 2, is detached.
        verify(mockIWallpaperService).detach(eq(displayConnector.mToken));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void displayRemoved_differentSystemAndLockWallpapers_shouldDetachWallpaperServiceTwice()
            throws Exception {
        ArgumentCaptor<DisplayListener> displayListenerCaptor = ArgumentCaptor.forClass(
                DisplayListener.class);
        verify(mDisplayManager).registerDisplayListener(displayListenerCaptor.capture(), eq(null));
        // GIVEN different wallpapers used for the lock and system.
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_LOCK, testUserId);
        final WallpaperData systemWallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM,
                testUserId);
        final WallpaperData lockWallpaper = mService.getCurrentWallpaperData(FLAG_LOCK,
                testUserId);
        IWallpaperService mockIWallpaperService = mock(IWallpaperService.class);
        systemWallpaper.connection.mService = mockIWallpaperService;
        lockWallpaper.connection.mService = mockIWallpaperService;
        // GIVEN there are two displays: DEFAULT_DISPLAY, 2
        final int testDisplayId = 2;
        setUpDisplays(List.of(DEFAULT_DISPLAY, testDisplayId));
        // GIVEN wallpaper connections have been established for display ID, 2.
        WallpaperManagerInternal wallpaperManagerInternal = LocalServices.getService(
                WallpaperManagerInternal.class);
        wallpaperManagerInternal.onDisplayAddSystemDecorations(testDisplayId);
        // Save displayConnectors for display ID, 2, before display removal.
        WallpaperManagerService.DisplayConnector systemWallpaperDisplayConnector =
                systemWallpaper.connection.getDisplayConnectorOrCreate(testDisplayId);
        WallpaperManagerService.DisplayConnector lockWallpaperDisplayConnector =
                lockWallpaper.connection.getDisplayConnectorOrCreate(testDisplayId);

        // WHEN display ID, 2, is removed.
        DisplayListener displayListener = displayListenerCaptor.getValue();
        displayListener.onDisplayRemoved(testDisplayId);

        // Then the system wallpaper connection for display ID, 2, is detached.
        verify(mockIWallpaperService).detach(eq(systemWallpaperDisplayConnector.mToken));
        // Then the lock wallpaper connection for display ID, 2, is detached.
        verify(mockIWallpaperService).detach(eq(lockWallpaperDisplayConnector.mToken));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void displayRemoved_fallbackWallpaper_shouldDetachFallbackWallpaperService()
            throws Exception {
        ArgumentCaptor<DisplayListener> displayListenerCaptor = ArgumentCaptor.forClass(
                DisplayListener.class);
        verify(mDisplayManager).registerDisplayListener(displayListenerCaptor.capture(), eq(null));
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        IWallpaperService mockIWallpaperService = mock(IWallpaperService.class);
        mService.mFallbackWallpaper.connection.mService = mockIWallpaperService;
        // GIVEN there are two displays: DEFAULT_DISPLAY, 2
        final int testDisplayId = 2;
        setUpDisplays(List.of(DEFAULT_DISPLAY, testDisplayId));
        // GIVEN display ID, 2, is incompatible with the wallpaper.
        mService.removeWallpaperCompatibleDisplayForTest(testDisplayId);
        // GIVEN wallpaper connections have been established for display ID, 2.
        WallpaperManagerInternal wallpaperManagerInternal = LocalServices.getService(
                WallpaperManagerInternal.class);
        wallpaperManagerInternal.onDisplayAddSystemDecorations(testDisplayId);
        // Save fallback wallpaper displayConnector for display ID, 2, before display removal.
        WallpaperManagerService.DisplayConnector fallbackWallpaperConnector =
                mService.mFallbackWallpaper.connection.getDisplayConnectorOrCreate(testDisplayId);

        // WHEN displayId, 2, is removed.
        DisplayListener displayListener = displayListenerCaptor.getValue();
        displayListener.onDisplayRemoved(testDisplayId);

        // Then the fallback wallpaper connection for display ID, 2, is detached.
        verify(mockIWallpaperService).detach(eq(fallbackWallpaperConnector.mToken));
    }
    // Verify a secondary display removed ended

     // Test fallback wallpaper after enabling connected display supports.
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void testFallbackWallpaperForConnectedDisplays() {
        final WallpaperData fallbackData = mService.mFallbackWallpaper;

        assertWithMessage(
                "The connected wallpaper component should be fallback wallpaper component from "
                        + "config file.")
                .that(fallbackData.connection.mWallpaper.getComponent())
                .isEqualTo(sFallbackWallpaperComponentName);
        assertWithMessage("Fallback wallpaper should support both lock & system.")
                .that(fallbackData.mWhich)
                .isEqualTo(FLAG_LOCK | FLAG_SYSTEM);
    }

    // Verify a secondary display removes system decorations started
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void displayRemoveSystemDecorations_sameSystemAndLockWallpaper_shouldDetachWallpaperServiceOnce()
            throws Exception {
        // GIVEN the same wallpaper used for the lock and system.
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        final WallpaperData wallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM, testUserId);
        IWallpaperService mockIWallpaperService = mock(IWallpaperService.class);
        wallpaper.connection.mService = mockIWallpaperService;
        // GIVEN there are two displays: DEFAULT_DISPLAY, 2
        final int testDisplayId = 2;
        setUpDisplays(List.of(DEFAULT_DISPLAY, testDisplayId));
        // GIVEN wallpaper connections have been established for displayID, 2.
        WallpaperManagerInternal wallpaperManagerInternal = LocalServices.getService(
                WallpaperManagerInternal.class);
        wallpaperManagerInternal.onDisplayAddSystemDecorations(testDisplayId);
        // Save displayConnector for displayId 2 before display removal.
        WallpaperManagerService.DisplayConnector displayConnector =
                wallpaper.connection.getDisplayConnectorOrCreate(testDisplayId);

        // WHEN displayId, 2, is removed.
        wallpaperManagerInternal.onDisplayRemoveSystemDecorations(testDisplayId);

        // Then the wallpaper connection for displayId, 2, is detached.
        verify(mockIWallpaperService).detach(eq(displayConnector.mToken));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void displayRemoveSystemDecorations_differentSystemAndLockWallpapers_shouldDetachWallpaperServiceTwice()
            throws Exception {
        // GIVEN different wallpapers used for the lock and system.
        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_LOCK, testUserId);
        final WallpaperData systemWallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM,
                testUserId);
        final WallpaperData lockWallpaper = mService.getCurrentWallpaperData(FLAG_LOCK,
                testUserId);
        IWallpaperService mockIWallpaperService = mock(IWallpaperService.class);
        systemWallpaper.connection.mService = mockIWallpaperService;
        lockWallpaper.connection.mService = mockIWallpaperService;
        // GIVEN there are two displays: DEFAULT_DISPLAY, 2
        final int testDisplayId = 2;
        setUpDisplays(List.of(DEFAULT_DISPLAY, testDisplayId));
        // GIVEN wallpaper connections have been established for displayID, 2.
        WallpaperManagerInternal wallpaperManagerInternal = LocalServices.getService(
                WallpaperManagerInternal.class);
        wallpaperManagerInternal.onDisplayAddSystemDecorations(testDisplayId);
        // Save displayConnectors for displayId 2 before display removal.
        WallpaperManagerService.DisplayConnector systemWallpaperDisplayConnector =
                systemWallpaper.connection.getDisplayConnectorOrCreate(testDisplayId);
        WallpaperManagerService.DisplayConnector lockWallpaperDisplayConnector =
                lockWallpaper.connection.getDisplayConnectorOrCreate(testDisplayId);

        // WHEN displayId, 2, is removed.
        wallpaperManagerInternal.onDisplayRemoveSystemDecorations(testDisplayId);

        // Then the system wallpaper connection for displayId, 2, is detached.
        verify(mockIWallpaperService).detach(eq(systemWallpaperDisplayConnector.mToken));
        // Then the lock wallpaper connection for displayId, 2, is detached.
        verify(mockIWallpaperService).detach(eq(lockWallpaperDisplayConnector.mToken));
    }
    // Verify a secondary display removes system decorations ended

    // Test setWallpaperComponent on multiple displays.
    // GIVEN 3 displays, 0, 2, 3, the new wallpaper is only compatible for display 0 and 3 but not
    // 2.
    // WHEN the new wallpaper is set for system and lock via setWallpaperComponent.
    // THEN there are 2 connections in mLastWallpaper and 1 connection in mFallbackWallpaper.
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void setWallpaperComponent_multiDisplays_shouldHaveExpectedConnections() {
        // Skip if there is no pre-defined default wallpaper component.
        assumeThat(sDefaultWallpaperComponent,
                not(CoreMatchers.equalTo(sImageWallpaperComponentName)));

        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        final int incompatibleDisplayId = 2;
        final int compatibleDisplayId = 3;
        setUpDisplays(List.of(DEFAULT_DISPLAY, incompatibleDisplayId, compatibleDisplayId));
        mService.removeWallpaperCompatibleDisplayForTest(incompatibleDisplayId);

        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_SYSTEM | FLAG_LOCK, testUserId);

        verifyLastWallpaperData(testUserId, sImageWallpaperComponentName);
        verifyCurrentSystemData(testUserId);
        assertThat(mService.mLastWallpaper.connection.getConnectedEngineSize()).isEqualTo(2);
        assertThat(mService.mLastWallpaper.connection.containsDisplay(DEFAULT_DISPLAY)).isTrue();
        assertThat(mService.mLastWallpaper.connection.containsDisplay(compatibleDisplayId))
                .isTrue();
        assertThat(mService.mFallbackWallpaper.connection.getConnectedEngineSize()).isEqualTo(1);
        assertThat(mService.mFallbackWallpaper.connection.containsDisplay(incompatibleDisplayId))
                .isTrue();
        assertThat(mService.mLastLockWallpaper).isNull();
    }

    // Test setWallpaperComponent on multiple displays.
    // GIVEN 3 displays, 0, 2, 3, the existing wallpaper is only compatible for display 0 and 3 but
    // not 2.
    // GIVEN the new wallpaper is compatible to 2.
    // WHEN the new wallpaper is set for system and lock via setWallpaperComponent.
    // THEN there are 3 connections in mLastWallpaper and 0 connection in mFallbackWallpaper.
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void setWallpaperComponent_multiDisplays_displayBecomeCompatible_shouldHaveExpectedConnections() {
        // Skip if there is no pre-defined default wallpaper component.
        assumeThat(sDefaultWallpaperComponent,
                not(CoreMatchers.equalTo(sImageWallpaperComponentName)));

        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        final int display2 = 2;
        final int display3 = 3;
        setUpDisplays(List.of(DEFAULT_DISPLAY, display2, display3));
        mService.removeWallpaperCompatibleDisplayForTest(display2);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_SYSTEM | FLAG_LOCK, testUserId);

        mService.addWallpaperCompatibleDisplayForTest(display2);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_SYSTEM | FLAG_LOCK, testUserId);

        verifyLastWallpaperData(testUserId, sImageWallpaperComponentName);
        verifyCurrentSystemData(testUserId);
        assertThat(mService.mLastWallpaper.connection.getConnectedEngineSize()).isEqualTo(3);
        assertThat(mService.mLastWallpaper.connection.containsDisplay(DEFAULT_DISPLAY)).isTrue();
        assertThat(mService.mLastWallpaper.connection.containsDisplay(display2)).isTrue();
        assertThat(mService.mLastWallpaper.connection.containsDisplay(display3)).isTrue();
        assertThat(mService.mFallbackWallpaper.connection.getConnectedEngineSize()).isEqualTo(0);
        assertThat(mService.mLastLockWallpaper).isNull();
    }

    // Test setWallpaperComponent on multiple displays.
    // GIVEN 3 displays, 0, 2, 3, the existing wallpaper is only compatible for display 0 and 3 but
    // not 2.
    // GIVEN the new wallpaper is incompatible to 2 and 3.
    // WHEN the new wallpaper is set for system and lock via setWallpaperComponent.
    // THEN there are 1 connections in mLastWallpaper and 2 connection in mFallbackWallpaper.
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void setWallpaperComponent_multiDisplays_displayBecomeIncompatible_shouldHaveExpectedConnections() {
        // Skip if there is no pre-defined default wallpaper component.
        assumeThat(sDefaultWallpaperComponent,
                not(CoreMatchers.equalTo(sImageWallpaperComponentName)));

        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        final int display2 = 2;
        final int display3 = 3;
        setUpDisplays(List.of(DEFAULT_DISPLAY, display2, display3));
        mService.removeWallpaperCompatibleDisplayForTest(display2);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_SYSTEM | FLAG_LOCK, testUserId);

        mService.removeWallpaperCompatibleDisplayForTest(display3);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_SYSTEM | FLAG_LOCK, testUserId);

        verifyLastWallpaperData(testUserId, sImageWallpaperComponentName);
        verifyCurrentSystemData(testUserId);
        assertThat(mService.mLastWallpaper.connection.getConnectedEngineSize()).isEqualTo(1);
        assertThat(mService.mLastWallpaper.connection.containsDisplay(DEFAULT_DISPLAY)).isTrue();
        assertThat(mService.mFallbackWallpaper.connection.getConnectedEngineSize()).isEqualTo(2);
        assertThat(mService.mFallbackWallpaper.connection.containsDisplay(display2)).isTrue();
        assertThat(mService.mFallbackWallpaper.connection.containsDisplay(display3)).isTrue();
        assertThat(mService.mLastLockWallpaper).isNull();
    }

    // Test setWallpaperComponent on multiple displays.
    // GIVEN 3 displays, 0, 2, 3, the new wallpaper is only compatible for display 0 and 3 but not
    // 2.
    // WHEN two different wallpapers set for system and lock via setWallpaperComponent.
    // THEN there are two connections in mLastWallpaper, two connection in mLastLockWallpaper and
    // one connection in mFallbackWallpaper.
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_WALLPAPER)
    public void setWallpaperComponent_systemAndLockWallpapers_multiDisplays_shouldHaveExpectedConnections() {
        // Skip if there is no pre-defined default wallpaper component.
        assumeThat(sDefaultWallpaperComponent,
                not(CoreMatchers.equalTo(sImageWallpaperComponentName)));

        final int testUserId = USER_SYSTEM;
        mService.switchUser(testUserId, null);
        final int incompatibleDisplayId = 2;
        final int compatibleDisplayId = 3;
        setUpDisplays(List.of(DEFAULT_DISPLAY, incompatibleDisplayId, compatibleDisplayId));
        mService.removeWallpaperCompatibleDisplayForTest(incompatibleDisplayId);

        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_SYSTEM, testUserId);
        mService.setWallpaperComponent(sImageWallpaperComponentName, sContext.getOpPackageName(),
                FLAG_LOCK, testUserId);

        verifyLastWallpaperData(testUserId, sImageWallpaperComponentName);
        verifyLastLockWallpaperData(testUserId, sImageWallpaperComponentName);
        verifyCurrentSystemData(testUserId);
        assertThat(mService.mLastWallpaper.connection.getConnectedEngineSize()).isEqualTo(2);
        assertThat(mService.mLastWallpaper.connection.containsDisplay(DEFAULT_DISPLAY)).isTrue();
        assertThat(mService.mLastWallpaper.connection.containsDisplay(compatibleDisplayId))
                .isTrue();
        assertThat(mService.mLastLockWallpaper.connection.getConnectedEngineSize()).isEqualTo(2);
        assertThat(mService.mLastLockWallpaper.connection.containsDisplay(DEFAULT_DISPLAY))
                .isTrue();
        assertThat(mService.mLastLockWallpaper.connection.containsDisplay(compatibleDisplayId))
                .isTrue();
        assertThat(mService.mFallbackWallpaper.connection.getConnectedEngineSize()).isEqualTo(1);
        assertThat(mService.mFallbackWallpaper.connection.containsDisplay(incompatibleDisplayId))
                .isTrue();
    }

    // Verify that after continue switch user from userId 0 to lastUserId, the wallpaper data for
    // non-current user must not bind to wallpaper service.
    private void verifyNoConnectionBeforeLastUser(int lastUserId) {
        for (int i = 0; i < lastUserId; i++) {
            final WallpaperData userData = mService.getCurrentWallpaperData(FLAG_SYSTEM, i);
            assertNull("No user data connection left", userData.connection);
        }
    }

    private void verifyLastWallpaperData(int lastUserId, ComponentName expectedComponent) {
        final WallpaperData lastData = mService.mLastWallpaper;
        assertNotNull("Last wallpaper must not be null", lastData);
        assertEquals("Last wallpaper component must be equals.", expectedComponent,
                lastData.getComponent());
        assertEquals("The user id in last wallpaper should be the last switched user",
                lastUserId, lastData.userId);
        assertNotNull("Must exist user data connection on last wallpaper data",
                lastData.connection);
    }

    private void verifyLastLockWallpaperData(int lastUserId, ComponentName expectedComponent) {
        final WallpaperData lastLockData = mService.mLastLockWallpaper;
        assertWithMessage("Last wallpaper must not be null").that(lastLockData).isNotNull();
        assertWithMessage("Last wallpaper component must be equals.")
                .that(lastLockData.getComponent())
                .isEqualTo(expectedComponent);
        assertWithMessage("The user id in last wallpaper should be the last switched user")
                .that(lastLockData.userId)
                .isEqualTo(lastUserId);
        assertWithMessage("Must exist user data connection on last wallpaper data")
                .that(lastLockData.connection)
                .isNotNull();
    }

    private void verifyCurrentSystemData(int userId) {
        final WallpaperData lastData = mService.mLastWallpaper;
        final WallpaperData wallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM, userId);
        assertEquals("Last wallpaper should be equals to current system wallpaper",
                lastData, wallpaper);

        final WallpaperData lastLockData = mService.mLastLockWallpaper;
        final WallpaperData lockWallpaper = mService.getCurrentWallpaperData(FLAG_LOCK, userId);
        assertWithMessage("Last lock wallpaper should be equals to current lock wallpaper")
                .that(lastLockData)
                .isEqualTo(lockWallpaper);
    }

    private void verifyDisplayData() {
        mService.mWallpaperDisplayHelper.forEachDisplayData(data -> {
            assertTrue("Display width must larger than maximum screen size",
                    data.mWidth >= DISPLAY_SIZE_DIMENSION);
            assertTrue("Display height must larger than maximum screen size",
                    data.mHeight >= DISPLAY_SIZE_DIMENSION);
        });
    }

    /**
     * Sets up mock displays for testing.
     *
     * <p>This method creates mock {@link Display} objects and configures the {@link DisplayManager}
     * to return them. It also sets up the {@link WindowManagerInternal} to indicate that all
     * displays support home.
     *
     * @param displayIds A list of display IDs to create mock displays for.
     */
    private void setUpDisplays(List<Integer> displayIds) {
        doReturn(true).when(sWindowManagerInternal).isHomeSupportedOnDisplay(anyInt());

        Display[] mockDisplays = new Display[displayIds.size()];
        for (int i = 0; i < displayIds.size(); i++) {
            final int displayId = displayIds.get(i);
            final Display mockDisplay = mock(Display.class);
            mockDisplays[i] = mockDisplay;
            doReturn(DISPLAY_SIZE_DIMENSION).when(mockDisplay).getMaximumSizeDimension();
            doReturn(mockDisplay).when(mDisplayManager).getDisplay(eq(displayId));
            doReturn(displayId).when(mockDisplay).getDisplayId();
            doReturn(true).when(mockDisplay).hasAccess(anyInt());
            mService.addWallpaperCompatibleDisplayForTest(displayId);
        }

        doReturn(mockDisplays).when(mDisplayManager).getDisplays();
    }

    /**
     * Asserts that the contents of the given {@link ParcelFileDescriptor} and {@link File} contain
     * exactly the same bytes.
     *
     * Both the PFD and File contents will be loaded to memory. The PFD will be closed at the end.
     */
    private static void assertPfdAndFileContentsEqual(ParcelFileDescriptor pfd, File file)
            throws IOException {
        try (ParcelFileDescriptor.AutoCloseInputStream pfdInputStream =
                     new ParcelFileDescriptor.AutoCloseInputStream(pfd);
             FileInputStream fileInputStream = new FileInputStream(file)
        ) {
            String pfdContents = new String(pfdInputStream.readAllBytes());
            String fileContents = new String(fileInputStream.readAllBytes());
            assertEquals(pfdContents, fileContents);
        }
    }
}
