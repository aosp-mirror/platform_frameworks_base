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

package com.android.internal.content;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * A unit test for PackageMonitor implementation.
 */
@RunWith(AndroidJUnit4.class)
public class PackageMonitorTest {
    private static final String FAKE_PACKAGE_NAME = "com.android.internal.content.fakeapp";
    private static final String FAKE_EXTRA_REASON = "android.intent.extra.fakereason";
    private static final int FAKE_PACKAGE_UID = 123;
    private static final int FAKE_USER_ID = 0;
    private static final int WAIT_CALLBACK_CALLED_IN_MS = 300;

    @Mock
    Context mMockContext;
    @Mock
    Handler mMockHandler;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testPackageMonitorMultipleRegisterThrowsException() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        spyPackageMonitor.register(mMockContext, UserHandle.ALL, mMockHandler);
        assertThat(spyPackageMonitor.getRegisteredHandler()).isEqualTo(mMockHandler);
        verify(mMockContext, never()).registerReceiverAsUser(any(), eq(UserHandle.ALL), any(),
                eq(null), eq(mMockHandler));

        assertThrows(IllegalStateException.class,
                () -> spyPackageMonitor.register(mMockContext, UserHandle.ALL, mMockHandler));
    }

    @Test
    public void testPackageMonitorRegisterMultipleUnRegisterThrowsException() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        spyPackageMonitor.register(mMockContext, UserHandle.ALL, mMockHandler);
        spyPackageMonitor.unregister();

        assertThrows(IllegalStateException.class, spyPackageMonitor::unregister);
    }

    @Test
    public void testPackageMonitorNotRegisterUnRegisterThrowsException() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        assertThrows(IllegalStateException.class, spyPackageMonitor::unregister);
    }

    @Test
    public void testPackageMonitorNotRegisterWithoutSupportPackageRestartQuery() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        spyPackageMonitor.register(mMockContext, UserHandle.ALL, mMockHandler);

        verify(mMockContext, never()).registerReceiverAsUser(any(), eq(UserHandle.ALL), any(),
                eq(null), eq(mMockHandler));
    }

    @Test
    public void testPackageMonitorRegisterWithSupportPackageRestartQuery() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor(true));

        spyPackageMonitor.register(mMockContext, UserHandle.ALL, mMockHandler);

        verify(mMockContext, times(1)).registerReceiverAsUser(any(), eq(UserHandle.ALL), any(),
                eq(null), eq(mMockHandler));
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventUidRemoved() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_UID_REMOVED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1)).onUidRemoved(eq(FAKE_PACKAGE_UID));
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageSuspended() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGES_SUSPENDED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        String [] packageList = new String[]{FAKE_PACKAGE_NAME};
        intent.putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST, packageList);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1)).onPackagesSuspended(eq(packageList));
        verify(spyPackageMonitor, times(1)).onSomePackagesChanged();
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageUnSuspended() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGES_UNSUSPENDED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        String [] packageList = new String[]{FAKE_PACKAGE_NAME};
        intent.putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST, packageList);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1)).onPackagesUnsuspended(eq(packageList));
        verify(spyPackageMonitor, times(1)).onSomePackagesChanged();
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventExternalApplicationAvailable()
            throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        String [] packageList = new String[]{FAKE_PACKAGE_NAME};
        intent.putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST, packageList);
        intent.putExtra(Intent.EXTRA_REPLACING, true);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1)).onPackagesAvailable(eq(packageList));
        verify(spyPackageMonitor, times(1)).onPackageAppeared(eq(FAKE_PACKAGE_NAME),
                eq(PackageMonitor.PACKAGE_UPDATING));
        verify(spyPackageMonitor, times(1)).onSomePackagesChanged();
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventExternalApplicationUnavailable()
            throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        String [] packageList = new String[]{FAKE_PACKAGE_NAME};
        intent.putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST, packageList);
        intent.putExtra(Intent.EXTRA_REPLACING, true);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1)).onPackagesUnavailable(eq(packageList));
        verify(spyPackageMonitor, times(1)).onPackageDisappeared(eq(FAKE_PACKAGE_NAME),
                eq(PackageMonitor.PACKAGE_UPDATING));
        verify(spyPackageMonitor, times(1)).onSomePackagesChanged();
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageRestarted() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_RESTARTED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        final long elapsedRealtimeMs = SystemClock.elapsedRealtime();
        intent.putExtra(Intent.EXTRA_TIME, elapsedRealtimeMs);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1)).onHandleForceStop(eq(intent),
                eq(new String[]{FAKE_PACKAGE_NAME}), eq(FAKE_PACKAGE_UID), eq(true),
                eqTimestamp(elapsedRealtimeMs));
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageUnstopped() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_UNSTOPPED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        final long elapsedRealtimeMs = SystemClock.elapsedRealtime();
        intent.putExtra(Intent.EXTRA_TIME, elapsedRealtimeMs);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1)).onPackageUnstopped(
                eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID), eqTimestamp(elapsedRealtimeMs));
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    private static Bundle eqTimestamp(long expectedRealtimeMs) {
        return ArgumentMatchers.argThat(actualExtras -> {
            final long actualRealtimeMs = actualExtras.getLong(Intent.EXTRA_TIME);
            return expectedRealtimeMs == actualRealtimeMs;
        });
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageQueryRestarted() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_QUERY_PACKAGE_RESTART);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        String [] packageList = new String[]{FAKE_PACKAGE_NAME};
        intent.putExtra(Intent.EXTRA_PACKAGES, packageList);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1)).onHandleForceStop(eq(intent),
                eq(packageList), eq(FAKE_PACKAGE_UID), eq(false));
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageDataClear() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1))
                .onPackageDataCleared(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID));
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageChanged() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        intent.putExtra(Intent.EXTRA_REASON, FAKE_EXTRA_REASON);
        String [] packageList = new String[]{FAKE_PACKAGE_NAME};
        intent.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, packageList);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1))
                .onPackageChanged(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID), eq(packageList));
        verify(spyPackageMonitor, times(1)).onPackageModified(eq(FAKE_PACKAGE_NAME));

        ArgumentCaptor<Bundle> argumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(spyPackageMonitor, times(1)).onPackageChangedWithExtras(eq(FAKE_PACKAGE_NAME),
                argumentCaptor.capture());

        Bundle capturedExtras = argumentCaptor.getValue();
        Bundle expectedExtras = intent.getExtras();
        assertThat(capturedExtras.getInt(Intent.EXTRA_USER_HANDLE))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_USER_HANDLE));
        assertThat(capturedExtras.getInt(Intent.EXTRA_UID))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_UID));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REASON))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REASON));

        verify(spyPackageMonitor, times(1)).onSomePackagesChanged();
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageRemovedReplacing() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        intent.putExtra(Intent.EXTRA_REPLACING, true);
        intent.putExtra(Intent.EXTRA_REMOVED_FOR_ALL_USERS, true);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1))
                .onPackageUpdateStarted(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID));
        ArgumentCaptor<Bundle> argumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(spyPackageMonitor, times(1))
                .onPackageUpdateStartedWithExtras(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID),
                        argumentCaptor.capture());

        verify(spyPackageMonitor, times(1)).onPackageDisappearedWithExtras(eq(FAKE_PACKAGE_NAME),
                argumentCaptor.capture());
        Bundle capturedExtras = argumentCaptor.getValue();
        Bundle expectedExtras = intent.getExtras();
        assertThat(capturedExtras.getInt(Intent.EXTRA_USER_HANDLE))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_USER_HANDLE));
        assertThat(capturedExtras.getInt(Intent.EXTRA_UID))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_UID));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REPLACING))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REPLACING));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REMOVED_FOR_ALL_USERS))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REMOVED_FOR_ALL_USERS));

        verify(spyPackageMonitor, times(1))
                .onPackageDisappeared(eq(FAKE_PACKAGE_NAME), eq(PackageMonitor.PACKAGE_UPDATING));
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageRemovedReplacingArchived() {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        intent.putExtra(Intent.EXTRA_REPLACING, true);
        intent.putExtra(Intent.EXTRA_ARCHIVAL, true);
        intent.putExtra(Intent.EXTRA_REMOVED_FOR_ALL_USERS, true);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        ArgumentCaptor<Bundle> argumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(spyPackageMonitor, times(1))
                .onPackageUpdateStarted(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID));
        verify(spyPackageMonitor, times(1))
                .onPackageUpdateStartedWithExtras(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID),
                        argumentCaptor.capture());
        verify(spyPackageMonitor, times(1)).onPackageModified(eq(FAKE_PACKAGE_NAME));
        verify(spyPackageMonitor, times(1)).onPackageModifiedWithExtras(eq(FAKE_PACKAGE_NAME),
                argumentCaptor.capture());

        verify(spyPackageMonitor, times(1))
                .onPackageDisappearedWithExtras(eq(FAKE_PACKAGE_NAME), argumentCaptor.capture());
        Bundle capturedExtras = argumentCaptor.getValue();
        Bundle expectedExtras = intent.getExtras();
        assertThat(capturedExtras.getInt(Intent.EXTRA_USER_HANDLE))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_USER_HANDLE));
        assertThat(capturedExtras.getInt(Intent.EXTRA_UID))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_UID));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REPLACING))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REPLACING));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REMOVED_FOR_ALL_USERS))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REMOVED_FOR_ALL_USERS));

        verify(spyPackageMonitor, times(1))
                .onPackageDisappeared(eq(FAKE_PACKAGE_NAME), eq(PackageMonitor.PACKAGE_UPDATING));
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageRemovedNotReplacing()
            throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        intent.putExtra(Intent.EXTRA_REPLACING, false);
        intent.putExtra(Intent.EXTRA_REMOVED_FOR_ALL_USERS, true);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        ArgumentCaptor<Bundle> argumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(spyPackageMonitor, times(1))
                .onPackageRemoved(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID));
        verify(spyPackageMonitor, times(1))
                .onPackageRemovedWithExtras(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID),
                        argumentCaptor.capture());
        verify(spyPackageMonitor, times(1))
                .onPackageRemovedAllUsers(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID));
        verify(spyPackageMonitor, times(1))
                .onPackageRemovedAllUsersWithExtras(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID),
                        argumentCaptor.capture());

        verify(spyPackageMonitor, times(1)).onPackageDisappearedWithExtras(eq(FAKE_PACKAGE_NAME),
                argumentCaptor.capture());
        Bundle capturedExtras = argumentCaptor.getValue();
        Bundle expectedExtras = intent.getExtras();
        assertThat(capturedExtras.getInt(Intent.EXTRA_USER_HANDLE))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_USER_HANDLE));
        assertThat(capturedExtras.getInt(Intent.EXTRA_UID))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_UID));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REPLACING))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REPLACING));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REMOVED_FOR_ALL_USERS))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REMOVED_FOR_ALL_USERS));

        verify(spyPackageMonitor, times(1)).onPackageDisappeared(eq(FAKE_PACKAGE_NAME),
                eq(PackageMonitor.PACKAGE_PERMANENT_CHANGE));
        verify(spyPackageMonitor, times(1)).onSomePackagesChanged();
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageAddReplacing() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        intent.putExtra(Intent.EXTRA_REPLACING, true);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        ArgumentCaptor<Bundle> argumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(spyPackageMonitor, times(1))
                .onPackageUpdateFinished(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID));
        verify(spyPackageMonitor, times(1))
                .onPackageModifiedWithExtras(eq(FAKE_PACKAGE_NAME), argumentCaptor.capture());
        verify(spyPackageMonitor, times(1)).onPackageModified(eq(FAKE_PACKAGE_NAME));
        verify(spyPackageMonitor, times(1))
                .onPackageModifiedWithExtras(eq(FAKE_PACKAGE_NAME), argumentCaptor.capture());


        verify(spyPackageMonitor, times(1)).onPackageAppearedWithExtras(eq(FAKE_PACKAGE_NAME),
                argumentCaptor.capture());
        Bundle capturedExtras = argumentCaptor.getValue();
        Bundle expectedExtras = intent.getExtras();
        assertThat(capturedExtras.getInt(Intent.EXTRA_USER_HANDLE))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_USER_HANDLE));
        assertThat(capturedExtras.getInt(Intent.EXTRA_UID))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_UID));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REPLACING))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REPLACING));

        verify(spyPackageMonitor, times(1))
                .onPackageAppeared(eq(FAKE_PACKAGE_NAME), eq(PackageMonitor.PACKAGE_UPDATING));
        verify(spyPackageMonitor, times(1)).onSomePackagesChanged();
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    @Test
    public void testPackageMonitorDoHandlePackageEventPackageAddNotReplacing() throws Exception {
        PackageMonitor spyPackageMonitor = spy(new TestPackageMonitor());

        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED);
        intent.setData(Uri.fromParts("package", FAKE_PACKAGE_NAME, null));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, FAKE_USER_ID);
        intent.putExtra(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        intent.putExtra(Intent.EXTRA_REPLACING, false);
        spyPackageMonitor.doHandlePackageEvent(intent);

        verify(spyPackageMonitor, times(1)).onBeginPackageChanges();
        verify(spyPackageMonitor, times(1))
                .onPackageAdded(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID));
        ArgumentCaptor<Bundle> argumentCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(spyPackageMonitor, times(1))
                .onPackageAddedWithExtras(eq(FAKE_PACKAGE_NAME), eq(FAKE_PACKAGE_UID),
                        argumentCaptor.capture());

        verify(spyPackageMonitor, times(1)).onPackageAppearedWithExtras(eq(FAKE_PACKAGE_NAME),
                argumentCaptor.capture());
        Bundle capturedExtras = argumentCaptor.getValue();
        Bundle expectedExtras = intent.getExtras();
        assertThat(capturedExtras.getInt(Intent.EXTRA_USER_HANDLE))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_USER_HANDLE));
        assertThat(capturedExtras.getInt(Intent.EXTRA_UID))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_UID));
        assertThat(capturedExtras.getInt(Intent.EXTRA_REPLACING))
                .isEqualTo(expectedExtras.getInt(Intent.EXTRA_REPLACING));

        verify(spyPackageMonitor, times(1)).onPackageAppeared(eq(FAKE_PACKAGE_NAME),
                eq(PackageMonitor.PACKAGE_PERMANENT_CHANGE));
        verify(spyPackageMonitor, times(1)).onSomePackagesChanged();
        verify(spyPackageMonitor, times(1)).onFinishPackageChanges();
    }

    public static class TestPackageMonitor extends PackageMonitor {
        public TestPackageMonitor(boolean b) {
            super(b);
        }

        public TestPackageMonitor() {
            super(false);
        }
    }
}
