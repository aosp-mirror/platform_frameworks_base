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

import android.app.role.RoleManager;
import android.content.Context;
import android.os.Binder;
import android.os.BugreportManager.BugreportCallback;
import android.os.IBinder;
import android.os.IDumpstateListener;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Pair;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class BugreportManagerServiceImplTest {

    private Context mContext;
    private BugreportManagerServiceImpl mService;
    private BugreportManagerServiceImpl.BugreportFileManager mBugreportFileManager;

    private int mCallingUid = 1234;
    private String mCallingPackage  = "test.package";

    private String mBugreportFile = "bugreport-file.zip";
    private String mBugreportFile2 = "bugreport-file2.zip";

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        ArraySet<String> mAllowlistedPackages = new ArraySet<>();
        mAllowlistedPackages.add(mContext.getPackageName());
        mService = new BugreportManagerServiceImpl(
                new BugreportManagerServiceImpl.Injector(mContext, mAllowlistedPackages));
        mBugreportFileManager = new BugreportManagerServiceImpl.BugreportFileManager();
    }

    @Test
    public void testBugreportFileManagerFileExists() {
        Pair<Integer, String> callingInfo = new Pair<>(mCallingUid, mCallingPackage);
        mBugreportFileManager.addBugreportFileForCaller(
                callingInfo, mBugreportFile);

        assertThrows(IllegalArgumentException.class, () ->
                mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                        callingInfo, "unknown-file.zip"));

        // No exception should be thrown.
        mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(callingInfo, mBugreportFile);
    }

    @Test
    public void testBugreportFileManagerMultipleFiles() {
        Pair<Integer, String> callingInfo = new Pair<>(mCallingUid, mCallingPackage);
        mBugreportFileManager.addBugreportFileForCaller(
                callingInfo, mBugreportFile);
        mBugreportFileManager.addBugreportFileForCaller(
                callingInfo, mBugreportFile2);

        // No exception should be thrown.
        mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(callingInfo, mBugreportFile);
        mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(callingInfo, mBugreportFile2);
    }

    @Test
    public void testBugreportFileManagerFileDoesNotExist() {
        Pair<Integer, String> callingInfo = new Pair<>(mCallingUid, mCallingPackage);
        assertThrows(IllegalArgumentException.class,
                () -> mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                        callingInfo, "test-file.zip"));
    }

    @Test
    public void testRetrieveBugreportWithoutFilesForCaller() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Listener listener = new Listener(latch);
        mService.retrieveBugreport(Binder.getCallingUid(), mContext.getPackageName(),
                new FileDescriptor(), mBugreportFile, listener);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.getErrorCode()).isEqualTo(
                BugreportCallback.BUGREPORT_ERROR_NO_BUGREPORT_TO_RETRIEVE);
    }

    @Test
    public void testCancelBugreportWithoutRole() throws Exception {
        // Clear out allowlisted packages.
        mService = new BugreportManagerServiceImpl(
                new BugreportManagerServiceImpl.Injector(mContext, new ArraySet<>()));

        assertThrows(SecurityException.class, () -> mService.cancelBugreport(
                Binder.getCallingUid(), mContext.getPackageName()));
    }

    @Test
    public void testCancelBugreportWithRole() throws Exception {
        // Clear out allowlisted packages.
        mService = new BugreportManagerServiceImpl(
                new BugreportManagerServiceImpl.Injector(mContext, new ArraySet<>()));
        RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        CallbackFuture future = new CallbackFuture();
        runWithShellPermissionIdentity(() -> roleManager.setBypassingRoleQualification(true));
        runWithShellPermissionIdentity(() -> roleManager.addRoleHolderAsUser(
                "android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION",
                mContext.getPackageName(),
                /* flags= */ 0,
                Process.myUserHandle(),
                mContext.getMainExecutor(),
                future));

        assertThat(future.get()).isEqualTo(true);
        mService.cancelBugreport(Binder.getCallingUid(), mContext.getPackageName());
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
}
