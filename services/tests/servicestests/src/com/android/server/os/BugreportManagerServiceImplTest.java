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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.os;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.admin.flags.Flags;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.BugreportManager.BugreportCallback;
import android.os.BugreportParams;
import android.os.IBinder;
import android.os.IDumpstateListener;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Pair;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class BugreportManagerServiceImplTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private Context mContext;
    private BugreportManagerServiceImpl mService;
    private BugreportManagerServiceImpl.BugreportFileManager mBugreportFileManager;

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private UserManager mMockUserManager;
    @Mock
    private DevicePolicyManager mMockDevicePolicyManager;

    private int mCallingUid = 1234;
    private String mCallingPackage  = "test.package";
    private AtomicFile mMappingFile;

    private String mBugreportFile = "bugreport-file.zip";
    private String mBugreportFile2 = "bugreport-file2.zip";

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mMappingFile = new AtomicFile(mContext.getFilesDir(), "bugreport-mapping.xml");
        ArraySet<String> mAllowlistedPackages = new ArraySet<>();
        mAllowlistedPackages.add(mContext.getPackageName());
        mService = new BugreportManagerServiceImpl(
                new TestInjector(mContext, mAllowlistedPackages, mMappingFile,
                        mMockUserManager, mMockDevicePolicyManager));
        mBugreportFileManager = new BugreportManagerServiceImpl.BugreportFileManager(mMappingFile);
        when(mPackageManager.getPackageUidAsUser(anyString(), anyInt())).thenReturn(mCallingUid);
        // The calling user is an admin user by default.
        when(mMockUserManager.isUserAdmin(anyInt())).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        // Changes to RoleManager persist between tests, so we need to clear out any funny
        // business we did in previous tests.
        mMappingFile.delete();
        RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        CallbackFuture future = new CallbackFuture();
        runWithShellPermissionIdentity(
                () -> {
                    roleManager.setBypassingRoleQualification(false);
                    roleManager.removeRoleHolderAsUser(
                            "android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION",
                            mContext.getPackageName(),
                            /* flags= */ 0,
                            Process.myUserHandle(),
                            mContext.getMainExecutor(),
                            future);
                });

        assertThat(future.get()).isEqualTo(true);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ONBOARDING_BUGREPORT_V2_ENABLED)
    public void testBugreportFileManagerFileExists() {
        Pair<Integer, String> callingInfo = new Pair<>(mCallingUid, mCallingPackage);
        mBugreportFileManager.addBugreportFileForCaller(
                callingInfo, mBugreportFile, /* keepOnRetrieval= */ false);

        assertThrows(IllegalArgumentException.class, () ->
                mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                        mContext, mPackageManager,  callingInfo,
                        Process.myUserHandle().getIdentifier(), "unknown-file.zip",
                        /* forceUpdateMapping= */ true));

        // No exception should be thrown.
        mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                mContext, mPackageManager, callingInfo, mContext.getUserId(), mBugreportFile,
                /* forceUpdateMapping= */ true);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ONBOARDING_BUGREPORT_V2_ENABLED)
    @Ignore
    public void testBugreportFileManagerKeepFilesOnRetrieval() {
        Pair<Integer, String> callingInfo = new Pair<>(mCallingUid, mCallingPackage);
        mBugreportFileManager.addBugreportFileForCaller(
                callingInfo, mBugreportFile, /* keepOnRetrieval= */ true);

        mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                mContext, mPackageManager, callingInfo, mContext.getUserId(), mBugreportFile,
                /* forceUpdateMapping= */ true);

        assertThat(mBugreportFileManager.mBugreportFilesToPersist).containsExactly(mBugreportFile);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ONBOARDING_BUGREPORT_V2_ENABLED)
    public void testBugreportFileManagerMultipleFiles() {
        Pair<Integer, String> callingInfo = new Pair<>(mCallingUid, mCallingPackage);
        mBugreportFileManager.addBugreportFileForCaller(
                callingInfo, mBugreportFile, /* keepOnRetrieval= */ false);
        mBugreportFileManager.addBugreportFileForCaller(
                callingInfo, mBugreportFile2, /* keepOnRetrieval= */ false);

        // No exception should be thrown.
        mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                mContext, mPackageManager, callingInfo, mContext.getUserId(), mBugreportFile,
                /* forceUpdateMapping= */ true);
        mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                mContext, mPackageManager, callingInfo, mContext.getUserId(), mBugreportFile2,
                /* forceUpdateMapping= */ true);
    }

    @Test
    public void testBugreportFileManagerFileDoesNotExist() {
        Pair<Integer, String> callingInfo = new Pair<>(mCallingUid, mCallingPackage);
        assertThrows(IllegalArgumentException.class,
                () -> mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                        mContext, mPackageManager, callingInfo,
                        Process.myUserHandle().getIdentifier(), "test-file.zip",
                        /* forceUpdateMapping= */ true));
    }

    @Test
    public void testStartBugreport_throwsForNonAdminUser() throws Exception {
        when(mMockUserManager.isUserAdmin(anyInt())).thenReturn(false);

        Exception thrown = assertThrows(IllegalArgumentException.class,
                () -> mService.startBugreport(mCallingUid, mContext.getPackageName(),
                        new FileDescriptor(), /* screenshotFd= */ null,
                        BugreportParams.BUGREPORT_MODE_FULL,
                        /* flags= */ 0, new Listener(new CountDownLatch(1)),
                        /* isScreenshotRequested= */ false));

        assertThat(thrown.getMessage()).contains("not an admin user");
    }

    @Test
    public void testStartBugreport_throwsForNotAffiliatedUser() throws Exception {
        when(mMockUserManager.isUserAdmin(anyInt())).thenReturn(false);
        when(mMockDevicePolicyManager.getDeviceOwnerUserId()).thenReturn(-1);
        when(mMockDevicePolicyManager.isAffiliatedUser(anyInt())).thenReturn(false);

        Exception thrown = assertThrows(IllegalArgumentException.class,
                () -> mService.startBugreport(mCallingUid, mContext.getPackageName(),
                        new FileDescriptor(), /* screenshotFd= */ null,
                        BugreportParams.BUGREPORT_MODE_REMOTE,
                        /* flags= */ 0, new Listener(new CountDownLatch(1)),
                        /* isScreenshotRequested= */ false));

        assertThat(thrown.getMessage()).contains("not affiliated to the device owner");
    }

    @Test
    public void testRetrieveBugreportWithoutFilesForCaller() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Listener listener = new Listener(latch);
        mService.retrieveBugreport(Binder.getCallingUid(), mContext.getPackageName(),
                mContext.getUserId(), new FileDescriptor(), mBugreportFile,
                /* keepOnRetrieval= */ false, listener);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.getErrorCode()).isEqualTo(
                BugreportCallback.BUGREPORT_ERROR_NO_BUGREPORT_TO_RETRIEVE);
    }

    @Test
    public void testCancelBugreportWithoutRole() {
        clearAllowlist();

        assertThrows(SecurityException.class, () -> mService.cancelBugreport(
                Binder.getCallingUid(), mContext.getPackageName()));
    }

    @Test
    public void testCancelBugreportWithRole() throws Exception {
        clearAllowlist();
        RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        CallbackFuture future = new CallbackFuture();
        runWithShellPermissionIdentity(
                () -> {
                    roleManager.setBypassingRoleQualification(true);
                    roleManager.addRoleHolderAsUser(
                            "android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION",
                            mContext.getPackageName(),
                            /* flags= */ 0,
                            Process.myUserHandle(),
                            mContext.getMainExecutor(),
                            future);
                });

        assertThat(future.get()).isEqualTo(true);
        mService.cancelBugreport(Binder.getCallingUid(), mContext.getPackageName());
    }

    private void clearAllowlist() {
        mService = new BugreportManagerServiceImpl(
                new TestInjector(mContext, new ArraySet<>(), mMappingFile,
                        mMockUserManager, mMockDevicePolicyManager));
    }

    private static class Listener implements IDumpstateListener {
        CountDownLatch mLatch;
        int mErrorCode;

        Listener(CountDownLatch latch) {
            mLatch = latch;
        }

        @Override
        public IBinder asBinder() {
            return null;
        }

        @Override
        public void onProgress(int progress) throws RemoteException {
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
            mErrorCode = errorCode;
            mLatch.countDown();
        }

        @Override
        public void onFinished(String bugreportFile) throws RemoteException {
            mLatch.countDown();
        }

        @Override
        public void onScreenshotTaken(boolean success) throws RemoteException {
        }

        @Override
        public void onUiIntensiveBugreportDumpsFinished() throws RemoteException {
        }

        int getErrorCode() {
            return mErrorCode;
        }
    }

    private static class CallbackFuture extends CompletableFuture<Boolean>
            implements Consumer<Boolean> {
        @Override
        public void accept(Boolean successful) {
            complete(successful);
        }
    }

    private static class TestInjector extends BugreportManagerServiceImpl.Injector {

        private final UserManager mUserManager;
        private final DevicePolicyManager mDevicePolicyManager;

        TestInjector(Context context, ArraySet<String> allowlistedPackages, AtomicFile mappingFile,
                UserManager um, DevicePolicyManager dpm) {
            super(context, allowlistedPackages, mappingFile);
            mUserManager = um;
            mDevicePolicyManager = dpm;
        }

        @Override
        public UserManager getUserManager() {
            return mUserManager;
        }

        @Override
        public DevicePolicyManager getDevicePolicyManager() {
            return mDevicePolicyManager;
        }
    }
}
