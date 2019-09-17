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

import static android.app.WallpaperManager.FLAG_SYSTEM;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;

import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.WallpaperService;
import android.testing.TestableContext;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.wallpaper.WallpaperManagerService.WallpaperData;
import com.android.server.wm.WindowManagerInternal;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;

/**
 * Tests for the {@link WallpaperManagerService} class.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:WallpaperManagerServiceTests
 */
@Presubmit
@FlakyTest(bugId = 129797242)
@RunWith(AndroidJUnit4.class)
public class WallpaperManagerServiceTests {
    private static final int DISPLAY_SIZE_DIMENSION = 100;
    private static StaticMockitoSession sMockitoSession;

    @ClassRule
    public static final TestableContext sContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    private static ComponentName sImageWallpaperComponentName;
    private static ComponentName sDefaultWallpaperComponent;

    private IPackageManager mIpm = AppGlobals.getPackageManager();

    @Mock
    private DisplayManager mDisplayManager;

    @Rule
    public final TemporaryFolder mFolder = new TemporaryFolder();
    private final SparseArray<File> mTempDirs = new SparseArray<>();
    private WallpaperManagerService mService;

    @BeforeClass
    public static void setUpClass() {
        sMockitoSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(LocalServices.class)
                .spyStatic(WallpaperManager.class)
                .startMocking();

        final WindowManagerInternal dmi = mock(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, dmi);

        sContext.addMockSystemService(Context.APP_OPS_SERVICE, mock(AppOpsManager.class));

        spyOn(sContext);
        sContext.getTestablePermissions().setPermission(
                android.Manifest.permission.SET_WALLPAPER_COMPONENT,
                PackageManager.PERMISSION_GRANTED);
        sContext.getTestablePermissions().setPermission(
                android.Manifest.permission.SET_WALLPAPER,
                PackageManager.PERMISSION_GRANTED);
        doNothing().when(sContext).sendBroadcastAsUser(any(), any());

        //Wallpaper components
        final IWallpaperConnection.Stub wallpaperService = mock(IWallpaperConnection.Stub.class);
        sImageWallpaperComponentName = ComponentName.unflattenFromString(
                sContext.getResources().getString(R.string.image_wallpaper_component));
        // Mock default wallpaper as image wallpaper if there is no pre-defined default wallpaper.
        sDefaultWallpaperComponent = WallpaperManager.getDefaultWallpaperComponent(sContext);

        if (sDefaultWallpaperComponent == null) {
            sDefaultWallpaperComponent = sImageWallpaperComponentName;
            doReturn(sImageWallpaperComponentName).when(() ->
                    WallpaperManager.getDefaultWallpaperComponent(any()));
        } else {
            sContext.addMockService(sDefaultWallpaperComponent, wallpaperService);
        }

        sContext.addMockService(sImageWallpaperComponentName, wallpaperService);
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
        reset(sContext);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        sContext.addMockSystemService(DisplayManager.class, mDisplayManager);

        final Display mockDisplay = mock(Display.class);
        doReturn(DISPLAY_SIZE_DIMENSION).when(mockDisplay).getMaximumSizeDimension();
        doReturn(mockDisplay).when(mDisplayManager).getDisplay(anyInt());

        final Display[] displays = new Display[]{mockDisplay};
        doReturn(displays).when(mDisplayManager).getDisplays();

        spyOn(mIpm);
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

    protected class TestWallpaperManagerService extends WallpaperManagerService {
        private static final String TAG = "TestWallpaperManagerService";

        TestWallpaperManagerService(Context context) {
            super(context);
        }

        @Override
        File getWallpaperDir(int userId) {
            File tempDir = mTempDirs.get(userId);
            if (tempDir == null) {
                try {
                    tempDir = mFolder.newFolder(String.valueOf(userId));
                    mTempDirs.append(userId, tempDir);
                } catch (IOException e) {
                    Log.e(TAG, "getWallpaperDir failed at userId= " + userId);
                }
            }
            return tempDir;
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
     * Tests that internal basic data should be correct after boot up.
     */
    @Test
    public void testDataCorrectAfterBoot() {
        mService.switchUser(UserHandle.USER_SYSTEM, null);

        final WallpaperData fallbackData = mService.mFallbackWallpaper;
        assertEquals("Fallback wallpaper component should be ImageWallpaper.",
                sImageWallpaperComponentName, fallbackData.wallpaperComponent);

        verifyLastWallpaperData(UserHandle.USER_SYSTEM, sDefaultWallpaperComponent);
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

        final int testUserId = UserHandle.USER_SYSTEM;
        mService.switchUser(testUserId, null);
        verifyLastWallpaperData(testUserId, sDefaultWallpaperComponent);
        verifyCurrentSystemData(testUserId);

        mService.setWallpaperComponent(sImageWallpaperComponentName);
        verifyLastWallpaperData(testUserId, sImageWallpaperComponentName);
        verifyCurrentSystemData(testUserId);

        mService.clearWallpaper(null, FLAG_SYSTEM, testUserId);
        verifyLastWallpaperData(testUserId, sDefaultWallpaperComponent);
        verifyCurrentSystemData(testUserId);
    }

    /**
     * Tests internal data should be correct and no crash after switch user continuously.
     */
    @Test
    public void testSwitchMultipleUsers() throws Exception {
        final int lastUserId = 5;
        final ServiceInfo pi = mIpm.getServiceInfo(sDefaultWallpaperComponent,
                PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, 0);
        doReturn(pi).when(mIpm).getServiceInfo(any(), anyInt(), anyInt());

        final Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
        final ParceledListSlice ris =
                mIpm.queryIntentServices(intent,
                        intent.resolveTypeIfNeeded(sContext.getContentResolver()),
                        PackageManager.GET_META_DATA, 0);
        doReturn(ris).when(mIpm).queryIntentServices(any(), any(), anyInt(), anyInt());
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
        doReturn(pi).when(mIpm).getServiceInfo(any(), anyInt(), anyInt());

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
            doReturn(ris).when(mIpm).queryIntentServices(any(), any(), anyInt(), eq(userId));
            mService.onUnlockUser(userId);
            verifyLastWallpaperData(userId, sDefaultWallpaperComponent);
            verifyCurrentSystemData(userId);
        }
        verifyNoConnectionBeforeLastUser(lastUserId);
        verifyDisplayData();
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
                lastData.wallpaperComponent);
        assertEquals("The user id in last wallpaper should be the last switched user",
                lastUserId, lastData.userId);
        assertNotNull("Must exist user data connection on last wallpaper data",
                lastData.connection);
    }

    private void verifyCurrentSystemData(int userId) {
        final WallpaperData lastData = mService.mLastWallpaper;
        final WallpaperData wallpaper = mService.getCurrentWallpaperData(FLAG_SYSTEM, userId);
        assertEquals("Last wallpaper should be equals to current system wallpaper",
                lastData, wallpaper);
    }

    private void verifyDisplayData() {
        mService.forEachDisplayData(data -> {
            assertTrue("Display width must larger than maximum screen size",
                    data.mWidth >= DISPLAY_SIZE_DIMENSION);
            assertTrue("Display height must larger than maximum screen size",
                    data.mHeight >= DISPLAY_SIZE_DIMENSION);
        });
    }
}
