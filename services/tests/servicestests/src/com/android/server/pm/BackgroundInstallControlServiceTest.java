/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.å
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import static android.Manifest.permission.GET_BACKGROUND_INSTALLED_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.UsageEventListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.FileUtils;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.util.AtomicFile;
import android.util.SparseSetArray;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.pm.permission.PermissionManagerServiceInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldSetter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link com.android.server.pm.BackgroundInstallControlService}
 */
@Presubmit
public final class BackgroundInstallControlServiceTest {
    private static final String INSTALLER_NAME_1 = "installer1";
    private static final String INSTALLER_NAME_2 = "installer2";
    private static final String PACKAGE_NAME_1 = "package1";
    private static final String PACKAGE_NAME_2 = "package2";
    private static final String PACKAGE_NAME_3 = "package3";
    private static final int USER_ID_1 = 1;
    private static final int USER_ID_2 = 2;
    private static final long USAGE_EVENT_TIMESTAMP_1 = 1000;
    private static final long USAGE_EVENT_TIMESTAMP_2 = 2000;
    private static final long USAGE_EVENT_TIMESTAMP_3 = 3000;
    private static final long PACKAGE_ADD_TIMESTAMP_1 = 1500;

    private BackgroundInstallControlService mBackgroundInstallControlService;
    private PackageManagerInternal.PackageListObserver mPackageListObserver;
    private UsageEventListener mUsageEventListener;
    private TestLooper mTestLooper;
    private Looper mLooper;
    private File mFile;

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    @Mock
    private PermissionManagerServiceInternal mPermissionManager;
    @Mock
    private BackgroundInstallControlCallbackHelper mCallbackHelper;

    @Captor
    private ArgumentCaptor<PackageManagerInternal.PackageListObserver> mPackageListObserverCaptor;

    @Captor
    private ArgumentCaptor<UsageEventListener> mUsageEventListenerCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();
        mLooper = mTestLooper.getLooper();
        mFile =
                new File(
                        InstrumentationRegistry.getInstrumentation().getContext().getCacheDir(),
                        "test");
        mBackgroundInstallControlService =
                new BackgroundInstallControlService(new MockInjector(mContext));

        verify(mUsageStatsManagerInternal).registerListener(mUsageEventListenerCaptor.capture());
        mUsageEventListener = mUsageEventListenerCaptor.getValue();

        mBackgroundInstallControlService.onStart(true);
        verify(mPackageManagerInternal).getPackageList(mPackageListObserverCaptor.capture());
        mPackageListObserver = mPackageListObserverCaptor.getValue();
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mFile);
    }

    @Test
    public void testInitBackgroundInstalledPackages_empty() {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        mBackgroundInstallControlService.initBackgroundInstalledPackages();
        assertNotNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        assertEquals(0, mBackgroundInstallControlService.getBackgroundInstalledPackages().size());
    }

    @Test
    public void testInitBackgroundInstalledPackages_one() {
        AtomicFile atomicFile = new AtomicFile(mFile);
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = atomicFile.startWrite();
        } catch (IOException e) {
            fail("Failed to start write to states protobuf." + e);
            return;
        }

        // Write test data to the file on the disk.
        try {
            ProtoOutputStream protoOutputStream = new ProtoOutputStream(fileOutputStream);
            long token = protoOutputStream.start(BackgroundInstalledPackagesProto.BG_INSTALLED_PKG);
            protoOutputStream.write(BackgroundInstalledPackageProto.PACKAGE_NAME, PACKAGE_NAME_1);
            protoOutputStream.write(BackgroundInstalledPackageProto.USER_ID, USER_ID_1 + 1);
            protoOutputStream.end(token);
            protoOutputStream.flush();
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            fail("Failed to finish write to states protobuf. " + e);
            atomicFile.failWrite(fileOutputStream);
        }

        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        mBackgroundInstallControlService.initBackgroundInstalledPackages();
        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);
        assertEquals(1, packages.size());
        assertTrue(packages.contains(USER_ID_1, PACKAGE_NAME_1));
    }

    @Test
    public void testInitBackgroundInstalledPackages_two() {
        AtomicFile atomicFile = new AtomicFile(mFile);
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = atomicFile.startWrite();
        } catch (IOException e) {
            fail("Failed to start write to states protobuf." + e);
            return;
        }

        // Write test data to the file on the disk.
        try {
            ProtoOutputStream protoOutputStream = new ProtoOutputStream(fileOutputStream);

            long token = protoOutputStream.start(BackgroundInstalledPackagesProto.BG_INSTALLED_PKG);
            protoOutputStream.write(BackgroundInstalledPackageProto.PACKAGE_NAME, PACKAGE_NAME_1);
            protoOutputStream.write(BackgroundInstalledPackageProto.USER_ID, USER_ID_1 + 1);
            protoOutputStream.end(token);

            token = protoOutputStream.start(BackgroundInstalledPackagesProto.BG_INSTALLED_PKG);
            protoOutputStream.write(BackgroundInstalledPackageProto.PACKAGE_NAME, PACKAGE_NAME_2);
            protoOutputStream.write(BackgroundInstalledPackageProto.USER_ID, USER_ID_2 + 1);
            protoOutputStream.end(token);

            protoOutputStream.flush();
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            fail("Failed to finish write to states protobuf. " + e);
            atomicFile.failWrite(fileOutputStream);
        }

        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        mBackgroundInstallControlService.initBackgroundInstalledPackages();
        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);
        assertEquals(2, packages.size());
        assertTrue(packages.contains(USER_ID_1, PACKAGE_NAME_1));
        assertTrue(packages.contains(USER_ID_2, PACKAGE_NAME_2));
    }

    @Test
    public void testWriteBackgroundInstalledPackagesToDisk_empty() {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        mBackgroundInstallControlService.initBackgroundInstalledPackages();
        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);
        mBackgroundInstallControlService.writeBackgroundInstalledPackagesToDisk();

        // Read the file on the disk to verify
        var packagesInDisk = new SparseSetArray<>();
        AtomicFile atomicFile = new AtomicFile(mFile);
        try (FileInputStream fileInputStream = atomicFile.openRead()) {
            ProtoInputStream protoInputStream = new ProtoInputStream(fileInputStream);

            while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (protoInputStream.getFieldNumber()
                        != (int) BackgroundInstalledPackagesProto.BG_INSTALLED_PKG) {
                    continue;
                }
                long token =
                        protoInputStream.start(BackgroundInstalledPackagesProto.BG_INSTALLED_PKG);
                String packageName = null;
                int userId = UserHandle.USER_NULL;
                while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    switch (protoInputStream.getFieldNumber()) {
                        case (int) BackgroundInstalledPackageProto.PACKAGE_NAME:
                            packageName =
                                    protoInputStream.readString(
                                            BackgroundInstalledPackageProto.PACKAGE_NAME);
                            break;
                        case (int) BackgroundInstalledPackageProto.USER_ID:
                            userId =
                                    protoInputStream.readInt(
                                            BackgroundInstalledPackageProto.USER_ID)
                                            - 1;
                            break;
                        default:
                            fail("Undefined field in proto: " + protoInputStream.getFieldNumber());
                    }
                }
                protoInputStream.end(token);
                if (packageName != null && userId != UserHandle.USER_NULL) {
                    packagesInDisk.add(userId, packageName);
                } else {
                    fail("Fails to get packageName or UserId from proto file");
                }
            }
        } catch (IOException e) {
            fail("Error reading state from the disk. " + e);
        }

        assertEquals(0, packagesInDisk.size());
        assertEquals(packages.size(), packagesInDisk.size());
    }

    @Test
    public void testWriteBackgroundInstalledPackagesToDisk_one() {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        mBackgroundInstallControlService.initBackgroundInstalledPackages();
        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);

        packages.add(USER_ID_1, PACKAGE_NAME_1);
        mBackgroundInstallControlService.writeBackgroundInstalledPackagesToDisk();

        // Read the file on the disk to verify
        var packagesInDisk = new SparseSetArray<>();
        AtomicFile atomicFile = new AtomicFile(mFile);
        try (FileInputStream fileInputStream = atomicFile.openRead()) {
            ProtoInputStream protoInputStream = new ProtoInputStream(fileInputStream);

            while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (protoInputStream.getFieldNumber()
                        != (int) BackgroundInstalledPackagesProto.BG_INSTALLED_PKG) {
                    continue;
                }
                long token =
                        protoInputStream.start(BackgroundInstalledPackagesProto.BG_INSTALLED_PKG);
                String packageName = null;
                int userId = UserHandle.USER_NULL;
                while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    switch (protoInputStream.getFieldNumber()) {
                        case (int) BackgroundInstalledPackageProto.PACKAGE_NAME:
                            packageName =
                                    protoInputStream.readString(
                                            BackgroundInstalledPackageProto.PACKAGE_NAME);
                            break;
                        case (int) BackgroundInstalledPackageProto.USER_ID:
                            userId =
                                    protoInputStream.readInt(
                                            BackgroundInstalledPackageProto.USER_ID)
                                            - 1;
                            break;
                        default:
                            fail("Undefined field in proto: " + protoInputStream.getFieldNumber());
                    }
                }
                protoInputStream.end(token);
                if (packageName != null && userId != UserHandle.USER_NULL) {
                    packagesInDisk.add(userId, packageName);
                } else {
                    fail("Fails to get packageName or UserId from proto file");
                }
            }
        } catch (IOException e) {
            fail("Error reading state from the disk. " + e);
        }

        assertEquals(1, packagesInDisk.size());
        assertEquals(packages.size(), packagesInDisk.size());
        assertTrue(packages.contains(USER_ID_1, PACKAGE_NAME_1));
    }

    @Test
    public void testWriteBackgroundInstalledPackagesToDisk_two() {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        mBackgroundInstallControlService.initBackgroundInstalledPackages();
        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);

        packages.add(USER_ID_1, PACKAGE_NAME_1);
        packages.add(USER_ID_2, PACKAGE_NAME_2);
        mBackgroundInstallControlService.writeBackgroundInstalledPackagesToDisk();

        // Read the file on the disk to verify
        var packagesInDisk = new SparseSetArray<>();
        AtomicFile atomicFile = new AtomicFile(mFile);
        try (FileInputStream fileInputStream = atomicFile.openRead()) {
            ProtoInputStream protoInputStream = new ProtoInputStream(fileInputStream);

            while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (protoInputStream.getFieldNumber()
                        != (int) BackgroundInstalledPackagesProto.BG_INSTALLED_PKG) {
                    continue;
                }
                long token =
                        protoInputStream.start(BackgroundInstalledPackagesProto.BG_INSTALLED_PKG);
                String packageName = null;
                int userId = UserHandle.USER_NULL;
                while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    switch (protoInputStream.getFieldNumber()) {
                        case (int) BackgroundInstalledPackageProto.PACKAGE_NAME:
                            packageName =
                                    protoInputStream.readString(
                                            BackgroundInstalledPackageProto.PACKAGE_NAME);
                            break;
                        case (int) BackgroundInstalledPackageProto.USER_ID:
                            userId =
                                    protoInputStream.readInt(
                                            BackgroundInstalledPackageProto.USER_ID)
                                            - 1;
                            break;
                        default:
                            fail("Undefined field in proto: " + protoInputStream.getFieldNumber());
                    }
                }
                protoInputStream.end(token);
                if (packageName != null && userId != UserHandle.USER_NULL) {
                    packagesInDisk.add(userId, packageName);
                } else {
                    fail("Fails to get packageName or UserId from proto file");
                }
            }
        } catch (IOException e) {
            fail("Error reading state from the disk. " + e);
        }

        assertEquals(2, packagesInDisk.size());
        assertEquals(packages.size(), packagesInDisk.size());
        assertTrue(packages.contains(USER_ID_1, PACKAGE_NAME_1));
        assertTrue(packages.contains(USER_ID_2, PACKAGE_NAME_2));
    }

    @Test
    public void testHandleUsageEvent_permissionDenied() {
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, USER_ID_1, INSTALLER_NAME_1, 0);
        mTestLooper.dispatchAll();
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
    }

    @Test
    public void testHandleUsageEvent_permissionGranted() {
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, USER_ID_1, INSTALLER_NAME_1, 0);
        mTestLooper.dispatchAll();
        assertEquals(
                1, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
    }

    @Test
    public void testHandleUsageEvent_ignoredEvent() {
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(UsageEvents.Event.USER_INTERACTION, USER_ID_1, INSTALLER_NAME_1, 0);
        mTestLooper.dispatchAll();
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
    }

    @Test
    public void testHandleUsageEvent_firstActivityResumedHalfTimeFrame() {
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_1,
                INSTALLER_NAME_1,
                USAGE_EVENT_TIMESTAMP_1);
        mTestLooper.dispatchAll();

        var installerForegroundTimeFrames =
                mBackgroundInstallControlService.getInstallerForegroundTimeFrames();
        assertEquals(1, installerForegroundTimeFrames.numMaps());
        assertEquals(1, installerForegroundTimeFrames.numElementsForKey(USER_ID_1));

        var foregroundTimeFrames = installerForegroundTimeFrames.get(USER_ID_1, INSTALLER_NAME_1);
        assertEquals(1, foregroundTimeFrames.size());

        var foregroundTimeFrame = foregroundTimeFrames.first();
        assertEquals(USAGE_EVENT_TIMESTAMP_1, foregroundTimeFrame.startTimeStampMillis);
        assertFalse(foregroundTimeFrame.isDone());
    }

    @Test
    public void testHandleUsageEvent_firstActivityResumedOneTimeFrame() {
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_1,
                INSTALLER_NAME_1,
                USAGE_EVENT_TIMESTAMP_1);
        generateUsageEvent(
                Event.ACTIVITY_STOPPED, USER_ID_1, INSTALLER_NAME_1, USAGE_EVENT_TIMESTAMP_2);
        mTestLooper.dispatchAll();

        var installerForegroundTimeFrames =
                mBackgroundInstallControlService.getInstallerForegroundTimeFrames();
        assertEquals(1, installerForegroundTimeFrames.numMaps());
        assertEquals(1, installerForegroundTimeFrames.numElementsForKey(USER_ID_1));

        var foregroundTimeFrames = installerForegroundTimeFrames.get(USER_ID_1, INSTALLER_NAME_1);
        assertEquals(1, foregroundTimeFrames.size());

        var foregroundTimeFrame = foregroundTimeFrames.first();
        assertEquals(USAGE_EVENT_TIMESTAMP_1, foregroundTimeFrame.startTimeStampMillis);
        assertTrue(foregroundTimeFrame.isDone());
    }

    @Test
    public void testHandleUsageEvent_firstActivityResumedOneAndHalfTimeFrame() {
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_1,
                INSTALLER_NAME_1,
                USAGE_EVENT_TIMESTAMP_1);
        generateUsageEvent(
                Event.ACTIVITY_STOPPED, USER_ID_1, INSTALLER_NAME_1, USAGE_EVENT_TIMESTAMP_2);
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_1,
                INSTALLER_NAME_1,
                USAGE_EVENT_TIMESTAMP_3);
        mTestLooper.dispatchAll();

        var installerForegroundTimeFrames =
                mBackgroundInstallControlService.getInstallerForegroundTimeFrames();
        assertEquals(1, installerForegroundTimeFrames.numMaps());
        assertEquals(1, installerForegroundTimeFrames.numElementsForKey(USER_ID_1));

        var foregroundTimeFrames = installerForegroundTimeFrames.get(USER_ID_1, INSTALLER_NAME_1);
        assertEquals(2, foregroundTimeFrames.size());

        var foregroundTimeFrame1 = foregroundTimeFrames.first();
        assertEquals(USAGE_EVENT_TIMESTAMP_1, foregroundTimeFrame1.startTimeStampMillis);
        assertTrue(foregroundTimeFrame1.isDone());

        var foregroundTimeFrame2 = foregroundTimeFrames.last();
        assertEquals(USAGE_EVENT_TIMESTAMP_3, foregroundTimeFrame2.startTimeStampMillis);
        assertFalse(foregroundTimeFrame2.isDone());
    }

    @Test
    public void testHandleUsageEvent_firstNoneActivityResumed() {
        assertEquals(
                0, mBackgroundInstallControlService.getInstallerForegroundTimeFrames().numMaps());
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                Event.ACTIVITY_STOPPED, USER_ID_1, INSTALLER_NAME_1, USAGE_EVENT_TIMESTAMP_1);
        mTestLooper.dispatchAll();

        var installerForegroundTimeFrames =
                mBackgroundInstallControlService.getInstallerForegroundTimeFrames();
        assertEquals(1, installerForegroundTimeFrames.numMaps());
        assertEquals(1, installerForegroundTimeFrames.numElementsForKey(USER_ID_1));

        var foregroundTimeFrames = installerForegroundTimeFrames.get(USER_ID_1, INSTALLER_NAME_1);
        assertEquals(0, foregroundTimeFrames.size());
    }

    @Test
    public void testHandleUsageEvent_packageAddedNoUsageEvent()
            throws NoSuchFieldException, PackageManager.NameNotFoundException {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        InstallSourceInfo installSourceInfo =
                new InstallSourceInfo(
                        /* initiatingPackageName= */ INSTALLER_NAME_1,
                        /* initiatingPackageSigningInfo= */ null,
                        /* originatingPackageName= */ null,
                        /* installingPackageName= */ INSTALLER_NAME_1);
        assertEquals(installSourceInfo.getInstallingPackageName(), INSTALLER_NAME_1);
        when(mPackageManager.getInstallSourceInfo(anyString())).thenReturn(installSourceInfo);
        ApplicationInfo appInfo = mock(ApplicationInfo.class);

        when(mPackageManager.getApplicationInfoAsUser(eq(PACKAGE_NAME_1), any(), anyInt()))
                .thenReturn(appInfo);

        long createTimestamp =
                PACKAGE_ADD_TIMESTAMP_1 - (System.currentTimeMillis() - SystemClock.uptimeMillis());
        FieldSetter.setField(
                appInfo,
                ApplicationInfo.class.getDeclaredField("createTimestamp"),
                createTimestamp);

        int uid = USER_ID_1 * UserHandle.PER_USER_RANGE;
        assertEquals(USER_ID_1, UserHandle.getUserId(uid));

        mPackageListObserver.onPackageAdded(PACKAGE_NAME_1, uid);
        mTestLooper.dispatchAll();

        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);
        assertEquals(1, packages.size());
        assertTrue(packages.contains(USER_ID_1, PACKAGE_NAME_1));
    }

    @Test
    public void testHandleUsageEvent_packageAddedInsideTimeFrame()
            throws NoSuchFieldException, PackageManager.NameNotFoundException {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        InstallSourceInfo installSourceInfo =
                new InstallSourceInfo(
                        /* initiatingPackageName= */ INSTALLER_NAME_1,
                        /* initiatingPackageSigningInfo= */ null,
                        /* originatingPackageName= */ null,
                        /* installingPackageName= */ INSTALLER_NAME_1);
        assertEquals(installSourceInfo.getInstallingPackageName(), INSTALLER_NAME_1);
        when(mPackageManager.getInstallSourceInfo(anyString())).thenReturn(installSourceInfo);
        ApplicationInfo appInfo = mock(ApplicationInfo.class);

        when(mPackageManager.getApplicationInfoAsUser(eq(PACKAGE_NAME_1), any(), anyInt()))
                .thenReturn(appInfo);

        long createTimestamp =
                PACKAGE_ADD_TIMESTAMP_1 - (System.currentTimeMillis() - SystemClock.uptimeMillis());
        FieldSetter.setField(
                appInfo,
                ApplicationInfo.class.getDeclaredField("createTimestamp"),
                createTimestamp);

        int uid = USER_ID_1 * UserHandle.PER_USER_RANGE;
        assertEquals(USER_ID_1, UserHandle.getUserId(uid));

        // The following 2 usage events generation is the only difference from the
        // testHandleUsageEvent_packageAddedNoUsageEvent test.
        // The 2 usage events make the package adding inside a time frame.
        // So it's not a background install. Thus, it's null for the return of
        // mBackgroundInstallControlService.getBackgroundInstalledPackages()
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_1,
                INSTALLER_NAME_1,
                USAGE_EVENT_TIMESTAMP_1);
        generateUsageEvent(
                Event.ACTIVITY_STOPPED, USER_ID_1, INSTALLER_NAME_1, USAGE_EVENT_TIMESTAMP_2);

        mPackageListObserver.onPackageAdded(PACKAGE_NAME_1, uid);
        mTestLooper.dispatchAll();
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
    }

    @Test
    public void testHandleUsageEvent_packageAddedOutsideTimeFrame1()
            throws NoSuchFieldException, PackageManager.NameNotFoundException {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        InstallSourceInfo installSourceInfo =
                new InstallSourceInfo(
                        /* initiatingPackageName= */ INSTALLER_NAME_1,
                        /* initiatingPackageSigningInfo= */ null,
                        /* originatingPackageName= */ null,
                        /* installingPackageName= */ INSTALLER_NAME_1);
        assertEquals(installSourceInfo.getInstallingPackageName(), INSTALLER_NAME_1);
        when(mPackageManager.getInstallSourceInfo(anyString())).thenReturn(installSourceInfo);
        ApplicationInfo appInfo = mock(ApplicationInfo.class);

        when(mPackageManager.getApplicationInfoAsUser(eq(PACKAGE_NAME_1), any(), anyInt()))
                .thenReturn(appInfo);

        long createTimestamp =
                PACKAGE_ADD_TIMESTAMP_1 - (System.currentTimeMillis() - SystemClock.uptimeMillis());
        FieldSetter.setField(
                appInfo,
                ApplicationInfo.class.getDeclaredField("createTimestamp"),
                createTimestamp);

        int uid = USER_ID_1 * UserHandle.PER_USER_RANGE;
        assertEquals(USER_ID_1, UserHandle.getUserId(uid));

        // The following 2 usage events generation is the only difference from the
        // testHandleUsageEvent_packageAddedNoUsageEvent test.
        // The 2 usage events make the package adding outside a time frame.
        // Compared to testHandleUsageEvent_packageAddedInsideTimeFrame,
        // it's a background install. Thus, it's not null for the return of
        // mBackgroundInstallControlService.getBackgroundInstalledPackages()
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_1,
                INSTALLER_NAME_1,
                USAGE_EVENT_TIMESTAMP_2);
        generateUsageEvent(
                Event.ACTIVITY_STOPPED, USER_ID_1, INSTALLER_NAME_1, USAGE_EVENT_TIMESTAMP_3);

        mPackageListObserver.onPackageAdded(PACKAGE_NAME_1, uid);
        mTestLooper.dispatchAll();

        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);
        assertEquals(1, packages.size());
        assertTrue(packages.contains(USER_ID_1, PACKAGE_NAME_1));
    }

    @Test
    public void testHandleUsageEvent_packageAddedOutsideTimeFrame2()
            throws NoSuchFieldException, PackageManager.NameNotFoundException {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        InstallSourceInfo installSourceInfo =
                new InstallSourceInfo(
                        /* initiatingPackageName= */ INSTALLER_NAME_1,
                        /* initiatingPackageSigningInfo= */ null,
                        /* originatingPackageName= */ null,
                        /* installingPackageName= */ INSTALLER_NAME_1);
        assertEquals(installSourceInfo.getInstallingPackageName(), INSTALLER_NAME_1);
        when(mPackageManager.getInstallSourceInfo(anyString())).thenReturn(installSourceInfo);
        ApplicationInfo appInfo = mock(ApplicationInfo.class);

        when(mPackageManager.getApplicationInfoAsUser(eq(PACKAGE_NAME_1), any(), anyInt()))
                .thenReturn(appInfo);

        long createTimestamp =
                PACKAGE_ADD_TIMESTAMP_1 - (System.currentTimeMillis() - SystemClock.uptimeMillis());
        FieldSetter.setField(
                appInfo,
                ApplicationInfo.class.getDeclaredField("createTimestamp"),
                createTimestamp);

        int uid = USER_ID_1 * UserHandle.PER_USER_RANGE;
        assertEquals(USER_ID_1, UserHandle.getUserId(uid));

        // The following 2 usage events generation is the only difference from the
        // testHandleUsageEvent_packageAddedNoUsageEvent test.
        // These 2 usage events are triggered by INSTALLER_NAME_2.
        // The 2 usage events make the package adding outside a time frame.
        // Compared to testHandleUsageEvent_packageAddedInsideTimeFrame,
        // it's a background install. Thus, it's not null for the return of
        // mBackgroundInstallControlService.getBackgroundInstalledPackages()
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_2,
                INSTALLER_NAME_2,
                USAGE_EVENT_TIMESTAMP_2);
        generateUsageEvent(
                Event.ACTIVITY_STOPPED, USER_ID_2, INSTALLER_NAME_2, USAGE_EVENT_TIMESTAMP_3);

        mPackageListObserver.onPackageAdded(PACKAGE_NAME_1, uid);
        mTestLooper.dispatchAll();

        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);
        assertEquals(1, packages.size());
        assertTrue(packages.contains(USER_ID_1, PACKAGE_NAME_1));
    }

    @Test
    public void testHandleUsageEvent_packageAddedThroughAdb()
            throws NoSuchFieldException, PackageManager.NameNotFoundException {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        // This test is a duplicate of testHandleUsageEvent_packageAddedThroughAdb except the
        // initiatingPackageName used to be null but is now "com.android.shell". This test ensures
        // that the behavior is still the same for when the initiatingPackageName is null.
        InstallSourceInfo installSourceInfo =
                new InstallSourceInfo(
                        /* initiatingPackageName= */ null,
                        /* initiatingPackageSigningInfo= */ null,
                        /* originatingPackageName= */ null,
                        /* installingPackageName= */ INSTALLER_NAME_1);
        // b/265203007
        when(mPackageManager.getInstallSourceInfo(anyString())).thenReturn(installSourceInfo);
        ApplicationInfo appInfo = mock(ApplicationInfo.class);

        when(mPackageManager.getApplicationInfoAsUser(eq(PACKAGE_NAME_1), any(), anyInt()))
                .thenReturn(appInfo);

        long createTimestamp =
                PACKAGE_ADD_TIMESTAMP_1 - (System.currentTimeMillis() - SystemClock.uptimeMillis());
        FieldSetter.setField(
                appInfo,
                ApplicationInfo.class.getDeclaredField("createTimestamp"),
                createTimestamp);

        int uid = USER_ID_1 * UserHandle.PER_USER_RANGE;
        assertEquals(USER_ID_1, UserHandle.getUserId(uid));

        // The following usage events generation is the same as
        // testHandleUsageEvent_packageAddedOutsideTimeFrame2 test. The only difference is that
        // for ADB installs the initiatingPackageName used to be null, despite being detected
        // as a background install. Since we do not want to treat side-loaded apps as background
        // install getBackgroundInstalledPackages() is expected to return null
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_1,
                INSTALLER_NAME_1,
                USAGE_EVENT_TIMESTAMP_2);
        generateUsageEvent(
                Event.ACTIVITY_STOPPED, USER_ID_1, INSTALLER_NAME_1, USAGE_EVENT_TIMESTAMP_3);

        mPackageListObserver.onPackageAdded(PACKAGE_NAME_1, uid);
        mTestLooper.dispatchAll();

        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNull(packages);
    }

    @Test
    public void testHandleUsageEvent_packageAddedThroughAdb2()
            throws NoSuchFieldException, PackageManager.NameNotFoundException {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        // This test is a duplicate of testHandleUsageEvent_packageAddedThroughAdb except the
        // initiatingPackageName used to be null but is now "com.android.shell". This test ensures
        // that the behavior is still the same after this change.
        InstallSourceInfo installSourceInfo =
                new InstallSourceInfo(
                        /* initiatingPackageName= */ "com.android.shell",
                        /* initiatingPackageSigningInfo= */ null,
                        /* originatingPackageName= */ null,
                        /* installingPackageName= */ INSTALLER_NAME_1);
        // b/265203007
        when(mPackageManager.getInstallSourceInfo(anyString())).thenReturn(installSourceInfo);
        ApplicationInfo appInfo = mock(ApplicationInfo.class);

        when(mPackageManager.getApplicationInfoAsUser(eq(PACKAGE_NAME_1), any(), anyInt()))
                .thenReturn(appInfo);

        long createTimestamp =
                PACKAGE_ADD_TIMESTAMP_1 - (System.currentTimeMillis() - SystemClock.uptimeMillis());
        FieldSetter.setField(
                appInfo,
                ApplicationInfo.class.getDeclaredField("createTimestamp"),
                createTimestamp);

        int uid = USER_ID_1 * UserHandle.PER_USER_RANGE;
        assertEquals(USER_ID_1, UserHandle.getUserId(uid));

        // The following  usage events generation is the same as
        // testHandleUsageEvent_packageAddedOutsideTimeFrame2 test. The only difference is that
        // for ADB installs the initiatingPackageName is com.android.shell, despite being detected
        // as a background install. Since we do not want to treat side-loaded apps as background
        // install getBackgroundInstalledPackages() is expected to return null
        doReturn(PERMISSION_GRANTED)
                .when(mPermissionManager)
                .checkPermission(anyString(), anyString(), anyInt(), anyInt());
        generateUsageEvent(
                UsageEvents.Event.ACTIVITY_RESUMED,
                USER_ID_1,
                INSTALLER_NAME_1,
                USAGE_EVENT_TIMESTAMP_2);
        generateUsageEvent(
                Event.ACTIVITY_STOPPED, USER_ID_1, INSTALLER_NAME_1, USAGE_EVENT_TIMESTAMP_3);

        mPackageListObserver.onPackageAdded(PACKAGE_NAME_1, uid);
        mTestLooper.dispatchAll();

        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNull(packages);
    }

    @Test
    public void testPackageRemoved() {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        mBackgroundInstallControlService.initBackgroundInstalledPackages();
        var packages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(packages);

        packages.add(USER_ID_1, PACKAGE_NAME_1);
        packages.add(USER_ID_2, PACKAGE_NAME_2);

        assertEquals(2, packages.size());
        assertTrue(packages.contains(USER_ID_1, PACKAGE_NAME_1));
        assertTrue(packages.contains(USER_ID_2, PACKAGE_NAME_2));

        int uid = USER_ID_1 * UserHandle.PER_USER_RANGE;
        assertEquals(USER_ID_1, UserHandle.getUserId(uid));

        mPackageListObserver.onPackageRemoved(PACKAGE_NAME_1, uid);
        mTestLooper.dispatchAll();

        assertEquals(1, packages.size());
        assertFalse(packages.contains(USER_ID_1, PACKAGE_NAME_1));
        assertTrue(packages.contains(USER_ID_2, PACKAGE_NAME_2));
    }

    @Test
    public void testGetBackgroundInstalledPackages() throws RemoteException {
        assertNull(mBackgroundInstallControlService.getBackgroundInstalledPackages());
        mBackgroundInstallControlService.initBackgroundInstalledPackages();
        var bgPackages = mBackgroundInstallControlService.getBackgroundInstalledPackages();
        assertNotNull(bgPackages);

        bgPackages.add(USER_ID_1, PACKAGE_NAME_1);
        bgPackages.add(USER_ID_2, PACKAGE_NAME_2);

        assertEquals(2, bgPackages.size());
        assertTrue(bgPackages.contains(USER_ID_1, PACKAGE_NAME_1));
        assertTrue(bgPackages.contains(USER_ID_2, PACKAGE_NAME_2));

        List<PackageInfo> packages = new ArrayList<>();
        var packageInfo1 = makePackageInfo(PACKAGE_NAME_1);
        packages.add(packageInfo1);
        var packageInfo2 = makePackageInfo(PACKAGE_NAME_2);
        packages.add(packageInfo2);
        var packageInfo3 = makePackageInfo(PACKAGE_NAME_3);
        packages.add(packageInfo3);
        doReturn(packages).when(mPackageManager).getInstalledPackagesAsUser(any(), anyInt());

        var resultPackages =
                mBackgroundInstallControlService.getBackgroundInstalledPackages(0L, USER_ID_1);
        assertEquals(1, resultPackages.getList().size());
        assertTrue(resultPackages.getList().contains(packageInfo1));
        assertFalse(resultPackages.getList().contains(packageInfo2));
        assertFalse(resultPackages.getList().contains(packageInfo3));
    }

    @Test(expected = SecurityException.class)
    public void enforceCallerPermissionsThrowsSecurityException() {
        doThrow(new SecurityException("test")).when(mContext)
                .enforceCallingOrSelfPermission(eq(GET_BACKGROUND_INSTALLED_PACKAGES), anyString());

        mBackgroundInstallControlService.enforceCallerPermissions();
    }

    @Test
    public void enforceCallerPermissionsDoesNotThrowSecurityException() {
        //enforceCallerQueryPackagesPermissions do not throw

        mBackgroundInstallControlService.enforceCallerPermissions();
    }

    /**
     * Mock a usage event occurring.
     *
     * @param usageEventId id of a usage event
     * @param userId       user id of a usage event
     * @param pkgName      package name of a usage event
     * @param timestamp    timestamp of a usage event
     */
    private void generateUsageEvent(int usageEventId, int userId, String pkgName, long timestamp) {
        Event event = new Event(usageEventId, timestamp);
        event.mPackage = pkgName;
        mUsageEventListener.onUsageEvent(userId, event);
    }

    private PackageInfo makePackageInfo(String packageName) {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = packageName;
        pkg.applicationInfo = new ApplicationInfo();
        return pkg;
    }

    private class MockInjector implements BackgroundInstallControlService.Injector {
        private final Context mContext;

        MockInjector(Context context) {
            mContext = context;
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public PackageManagerInternal getPackageManagerInternal() {
            return mPackageManagerInternal;
        }

        @Override
        public UsageStatsManagerInternal getUsageStatsManagerInternal() {
            return mUsageStatsManagerInternal;
        }

        @Override
        public PermissionManagerServiceInternal getPermissionManager() {
            return mPermissionManager;
        }

        @Override
        public Looper getLooper() {
            return mLooper;
        }

        @Override
        public File getDiskFile() {
            return mFile;
        }


        @Override
        public BackgroundInstallControlCallbackHelper getBackgroundInstallControlCallbackHelper() {
            return mCallbackHelper;
        }
    }
}
