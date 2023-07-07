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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Binder;
import android.os.BugreportManager.BugreportCallback;
import android.os.IBinder;
import android.os.IDumpstateListener;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Pair;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class BugreportManagerServiceImplTest {

    Context mContext;
    BugreportManagerServiceImpl mService;
    BugreportManagerServiceImpl.BugreportFileManager mBugreportFileManager;

    int mCallingUid = 1234;
    String mCallingPackage  = "test.package";

    String mBugreportFile = "bugreport-file.zip";
    String mBugreportFile2 = "bugreport-file2.zip";

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
}
