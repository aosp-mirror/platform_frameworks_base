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

package com.android.server.backup.transport;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.app.backup.BackupTransport;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.backup.ITransportStatusCallback;
import com.android.internal.infra.AndroidFuture;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CancellationException;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupTransportClientTest {

    private static class TestFuturesFakeTransportBinder extends FakeTransportBinderBase {
        public final Object mLock = new Object();

        public String mNameCompletedImmediately;

        @GuardedBy("mLock")
        public AndroidFuture<String> mNameCompletedInFuture;

        @Override public void name(AndroidFuture<String> name) throws RemoteException {
            name.complete(mNameCompletedImmediately);
        }
        @Override public void transportDirName(AndroidFuture<String> name) throws RemoteException {
            synchronized (mLock) {
                mNameCompletedInFuture = name;
                mLock.notifyAll();
            }
        }
    }

    @Test
    public void testName_completesImmediately_returnsName() throws Exception {
        TestFuturesFakeTransportBinder binder = new TestFuturesFakeTransportBinder();
        binder.mNameCompletedImmediately = "fake name";

        BackupTransportClient client = new BackupTransportClient(binder);
        String name = client.name();

        assertThat(name).isEqualTo("fake name");
    }

    @Test
    public void testTransportDirName_completesLater_returnsName() throws Exception {
        TestFuturesFakeTransportBinder binder = new TestFuturesFakeTransportBinder();
        BackupTransportClient client = new BackupTransportClient(binder);

        Thread thread = new Thread(() -> {
            try {
                String name = client.transportDirName();
                assertThat(name).isEqualTo("fake name");
            } catch (Exception ex) {
                fail("unexpected Exception: " + ex.getClass().getCanonicalName());
            }
        });

        thread.start();

        synchronized (binder.mLock) {
            while (binder.mNameCompletedInFuture == null) {
                binder.mLock.wait();
            }
            assertThat(binder.mNameCompletedInFuture.complete("fake name")).isTrue();
        }

        thread.join();
    }

    @Test
    public void testTransportDirName_canceledBeforeCompletion_throwsException() throws Exception {
        TestFuturesFakeTransportBinder binder = new TestFuturesFakeTransportBinder();
        BackupTransportClient client = new BackupTransportClient(binder);

        Thread thread = new Thread(() -> {
            try {
                assertThat(client.transportDirName()).isNull();
            } catch (Exception ex) {
                fail("unexpected Exception: " + ex.getClass().getCanonicalName());
            }
        });

        thread.start();

        synchronized (binder.mLock) {
            while (binder.mNameCompletedInFuture == null) {
                binder.mLock.wait();
            }
            client.onBecomingUnusable();
        }

        thread.join();
    }

    private static class TestCallbacksFakeTransportBinder extends FakeTransportBinderBase {
        public final Object mLock = new Object();

        public int mStatusCompletedImmediately;

        @GuardedBy("mLock")
        public ITransportStatusCallback mStatusCompletedInFuture;

        @Override public void initializeDevice(ITransportStatusCallback c) throws RemoteException {
            c.onOperationCompleteWithStatus(mStatusCompletedImmediately);
        }
        @Override public void finishBackup(ITransportStatusCallback c) throws RemoteException {
            synchronized (mLock) {
                mStatusCompletedInFuture = c;
                mLock.notifyAll();
            }
        }
    }

    @Test
    public void testInitializeDevice_completesImmediately_returnsStatus() throws Exception {
        TestCallbacksFakeTransportBinder binder = new TestCallbacksFakeTransportBinder();
        binder.mStatusCompletedImmediately = 123;

        BackupTransportClient client = new BackupTransportClient(binder);
        int status = client.initializeDevice();

        assertThat(status).isEqualTo(123);
    }


    @Test
    public void testFinishBackup_completesLater_returnsStatus() throws Exception {
        TestCallbacksFakeTransportBinder binder = new TestCallbacksFakeTransportBinder();
        BackupTransportClient client = new BackupTransportClient(binder);

        Thread thread = new Thread(() -> {
            try {
                int status = client.finishBackup();
                assertThat(status).isEqualTo(456);
            } catch (Exception ex) {
                fail("unexpected Exception: " + ex.getClass().getCanonicalName());
            }
        });

        thread.start();

        synchronized (binder.mLock) {
            while (binder.mStatusCompletedInFuture == null) {
                binder.mLock.wait();
            }
            binder.mStatusCompletedInFuture.onOperationCompleteWithStatus(456);
        }

        thread.join();
    }

    @Test
    public void testFinishBackup_canceledBeforeCompletion_returnsError() throws Exception {
        TestCallbacksFakeTransportBinder binder = new TestCallbacksFakeTransportBinder();
        BackupTransportClient client = new BackupTransportClient(binder);

        Thread thread = new Thread(() -> {
            try {
                int status = client.finishBackup();
                assertThat(status).isEqualTo(BackupTransport.TRANSPORT_ERROR);
            } catch (Exception ex) {
                fail("unexpected Exception: " + ex.getClass().getCanonicalName());
            }
        });

        thread.start();

        synchronized (binder.mLock) {
            while (binder.mStatusCompletedInFuture == null) {
                binder.mLock.wait();
            }
            client.onBecomingUnusable();
        }

        thread.join();
    }

    // Convenience layer so we only need to fake specific methods useful for each test case.
    private static class FakeTransportBinderBase implements IBackupTransport {
        @Override public void name(AndroidFuture<String> f) throws RemoteException {}
        @Override public void transportDirName(AndroidFuture<String> f) throws RemoteException {}
        @Override public void configurationIntent(AndroidFuture<Intent> f) throws RemoteException {}
        @Override public void currentDestinationString(AndroidFuture<String> f)
                throws RemoteException {}
        @Override public void dataManagementIntent(AndroidFuture<Intent> f)
                throws RemoteException {}
        @Override public void dataManagementIntentLabel(AndroidFuture<CharSequence> f)
                throws RemoteException {}
        @Override public void initializeDevice(ITransportStatusCallback c) throws RemoteException {}
        @Override public void clearBackupData(PackageInfo i, ITransportStatusCallback c)
                throws RemoteException {}
        @Override public void finishBackup(ITransportStatusCallback c) throws RemoteException {}
        @Override public void requestBackupTime(AndroidFuture<Long> f) throws RemoteException {}
        @Override public void performBackup(PackageInfo i, ParcelFileDescriptor fd, int f,
            ITransportStatusCallback c) throws RemoteException {}
        @Override public void getAvailableRestoreSets(AndroidFuture<List<RestoreSet>> f)
                throws RemoteException {}
        @Override public void getCurrentRestoreSet(AndroidFuture<Long> f) throws RemoteException {}
        @Override public void startRestore(long t, PackageInfo[] p, ITransportStatusCallback c)
                throws RemoteException {}
        @Override public void nextRestorePackage(AndroidFuture<RestoreDescription> f)
                throws RemoteException {}
        @Override public void getRestoreData(ParcelFileDescriptor fd, ITransportStatusCallback c)
                throws RemoteException {}
        @Override public void finishRestore(ITransportStatusCallback c) throws RemoteException {}
        @Override public void requestFullBackupTime(AndroidFuture<Long> f) throws RemoteException {}
        @Override public void performFullBackup(PackageInfo i, ParcelFileDescriptor fd, int f,
            ITransportStatusCallback c) throws RemoteException {}
        @Override public void checkFullBackupSize(long s, ITransportStatusCallback c)
                throws RemoteException {}
        @Override public void sendBackupData(int n, ITransportStatusCallback c)
                throws RemoteException {}
        @Override public void cancelFullBackup(ITransportStatusCallback c) throws RemoteException {}
        @Override public void isAppEligibleForBackup(PackageInfo p, boolean b,
            AndroidFuture<Boolean> f) throws RemoteException {}
        @Override public void getBackupQuota(String s, boolean b, AndroidFuture<Long> f)
                throws RemoteException {}
        @Override public void getNextFullRestoreDataChunk(ParcelFileDescriptor fd,
            ITransportStatusCallback c) throws RemoteException {}
        @Override public void abortFullRestore(ITransportStatusCallback c) throws RemoteException {}
        @Override public void getTransportFlags(AndroidFuture<Integer> f) throws RemoteException {}
        @Override public IBinder asBinder() {
            return null;
        }
    }
}
