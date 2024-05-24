/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm.test.app;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IBackgroundInstallControlService;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.compatibility.common.util.ThrowingRunnable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class BackgroundInstallControlServiceTest {
    private static final String TAG = "BackgroundInstallControlServiceTest";
    private static final String ACTION_INSTALL_COMMIT =
            "com.android.server.pm.test.app.BackgroundInstallControlServiceTest"
                    + ".ACTION_INSTALL_COMMIT";
    private static final String MOCK_PACKAGE_NAME = "com.android.servicestests.apps.bicmockapp3";

    private static final String TEST_DATA_DIR = "/data/local/tmp/";

    private static final String MOCK_APK_FILE = "BackgroundInstallControlMockApp3.apk";
    private IBackgroundInstallControlService mIBics;

    @Before
    public void setUp() {
        mIBics =
                IBackgroundInstallControlService.Stub.asInterface(
                        ServiceManager.getService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE));
        assertThat(mIBics).isNotNull();
    }

    @After
    public void tearDown() {
        runShellCommand("pm uninstall " + MOCK_PACKAGE_NAME);
    }

    @Test
    public void testGetMockBackgroundInstalledPackages() throws RemoteException {
        ParceledListSlice<PackageInfo> slice =
                ShellIdentityUtils.invokeMethodWithShellPermissions(
                        mIBics,
                        (bics) -> {
                            try {
                                return bics.getBackgroundInstalledPackages(
                                        PackageManager.MATCH_ALL, Process.myUserHandle()
                                                .getIdentifier());
                            } catch (RemoteException e) {
                                throw e.rethrowFromSystemServer();
                            }
                        });
        assertThat(slice).isNotNull();

        var packageList = slice.getList();
        assertThat(packageList).isNotNull();
        assertThat(packageList).hasSize(2);

        var expectedPackageNames =
                Set.of(
                        "com.android.servicestests.apps.bicmockapp1",
                        "com.android.servicestests.apps.bicmockapp2");
        var actualPackageNames =
                packageList.stream()
                        .map((packageInfo) -> packageInfo.packageName)
                        .collect(Collectors.toSet());
        assertThat(actualPackageNames).containsExactlyElementsIn(expectedPackageNames);
    }

    @Test
    public void testRegisterBackgroundInstallControlCallback()
            throws Exception {
        String testPackageName = "test";
        int testUserId = 1;
        ArrayList<Pair<String, Integer>> sharedResource = new ArrayList<>();
        IRemoteCallback testCallback =
                new IRemoteCallback.Stub() {
                    private final ArrayList<Pair<String, Integer>> mArray = sharedResource;

                    @Override
                    public void sendResult(Bundle data) throws RemoteException {
                        mArray.add(new Pair(testPackageName, testUserId));
                    }
                };
        ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(
                mIBics,
                (bics) -> {
                    try {
                        bics.registerBackgroundInstallCallback(testCallback);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                });
        installPackage(TEST_DATA_DIR + MOCK_APK_FILE, MOCK_PACKAGE_NAME);

        assertUntil(() -> sharedResource.size() == 1, 2000);
        assertThat(sharedResource.get(0).first).isEqualTo(testPackageName);
        assertThat(sharedResource.get(0).second).isEqualTo(testUserId);
    }

    @Test
    public void testUnregisterBackgroundInstallControlCallback() {
        String testValue = "test";
        ArrayList<String> sharedResource = new ArrayList<>();
        IRemoteCallback testCallback =
                new IRemoteCallback.Stub() {
                    private final ArrayList<String> mArray = sharedResource;

                    @Override
                    public void sendResult(Bundle data) throws RemoteException {
                        mArray.add(testValue);
                    }
                };
        ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(
                mIBics,
                (bics) -> {
                    try {
                        bics.registerBackgroundInstallCallback(testCallback);
                        bics.unregisterBackgroundInstallCallback(testCallback);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                });
        installPackage(TEST_DATA_DIR + MOCK_APK_FILE, MOCK_PACKAGE_NAME);

        assertUntil(sharedResource::isEmpty, 2000);
    }

    private static boolean installPackage(String apkPath, String packageName) {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final CountDownLatch installLatch = new CountDownLatch(1);
        final BroadcastReceiver installReceiver =
                new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        int packageInstallStatus =
                                intent.getIntExtra(
                                        PackageInstaller.EXTRA_STATUS,
                                        PackageInstaller.STATUS_FAILURE_INVALID);
                        if (packageInstallStatus == PackageInstaller.STATUS_SUCCESS) {
                            installLatch.countDown();
                        }
                    }
                };
        final IntentFilter intentFilter = new IntentFilter(ACTION_INSTALL_COMMIT);
        context.registerReceiver(installReceiver, intentFilter, Context.RECEIVER_EXPORTED);

        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        try {
            int sessionId = packageInstaller.createSession(params);
            PackageInstaller.Session session = packageInstaller.openSession(sessionId);
            OutputStream out = session.openWrite(packageName, 0, -1);
            FileInputStream fis = new FileInputStream(apkPath);
            byte[] buffer = new byte[65536];
            int size;
            while ((size = fis.read(buffer)) != -1) {
                out.write(buffer, 0, size);
            }
            session.fsync(out);
            fis.close();
            out.close();

            runWithShellPermissionIdentity(
                    () -> {
                        session.commit(createPendingIntent(context).getIntentSender());
                        installLatch.await(5, TimeUnit.SECONDS);
                    });
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static PendingIntent createPendingIntent(Context context) {
        PendingIntent pendingIntent =
                PendingIntent.getBroadcast(
                        context,
                        1,
                        new Intent(ACTION_INSTALL_COMMIT)
                                .setPackage(
                                        BackgroundInstallControlServiceTest.class.getPackageName()),
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
        return pendingIntent;
    }

    private static void runWithShellPermissionIdentity(@NonNull ThrowingRunnable command)
            throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();
        try {
            command.run();
        } finally {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    private static void assertUntil(Supplier<Boolean> condition, int timeoutMs) {
        long endTime = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <= endTime) {
            if (condition.get()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        assertThat(condition.get()).isTrue();
    }
}