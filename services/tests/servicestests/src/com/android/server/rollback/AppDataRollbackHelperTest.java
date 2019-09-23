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

package com.android.server.rollback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.util.IntArray;
import android.util.SparseLongArray;

import com.android.server.pm.Installer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;

@RunWith(JUnit4.class)
public class AppDataRollbackHelperTest {

    @Test
    public void testSnapshotAppData() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));

        // All users are unlocked so we should snapshot data for them.
        doReturn(true).when(helper).isUserCredentialLocked(eq(10));
        doReturn(true).when(helper).isUserCredentialLocked(eq(11));
        PackageRollbackInfo info = createPackageRollbackInfo("com.foo.bar");
        helper.snapshotAppData(5, info, new int[]{10, 11});

        assertEquals(2, info.getPendingBackups().size());
        assertEquals(10, info.getPendingBackups().get(0));
        assertEquals(11, info.getPendingBackups().get(1));

        assertEquals(0, info.getCeSnapshotInodes().size());

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).snapshotAppData(
                eq("com.foo.bar"), eq(10), eq(5), eq(Installer.FLAG_STORAGE_DE));
        inOrder.verify(installer).snapshotAppData(
                eq("com.foo.bar"), eq(11), eq(5), eq(Installer.FLAG_STORAGE_DE));
        inOrder.verifyNoMoreInteractions();

        // One of the users is unlocked but the other isn't
        doReturn(false).when(helper).isUserCredentialLocked(eq(10));
        doReturn(true).when(helper).isUserCredentialLocked(eq(11));
        when(installer.snapshotAppData(anyString(), anyInt(), anyInt(), anyInt())).thenReturn(239L);

        PackageRollbackInfo info2 = createPackageRollbackInfo("com.foo.bar");
        helper.snapshotAppData(7, info2, new int[]{10, 11});
        assertEquals(1, info2.getPendingBackups().size());
        assertEquals(11, info2.getPendingBackups().get(0));

        assertEquals(1, info2.getCeSnapshotInodes().size());
        assertEquals(239L, info2.getCeSnapshotInodes().get(10));

        inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).snapshotAppData(
                eq("com.foo.bar"), eq(10), eq(7),
                eq(Installer.FLAG_STORAGE_CE | Installer.FLAG_STORAGE_DE));
        inOrder.verify(installer).snapshotAppData(
                eq("com.foo.bar"), eq(11), eq(7), eq(Installer.FLAG_STORAGE_DE));
        inOrder.verifyNoMoreInteractions();
    }

    private static PackageRollbackInfo createPackageRollbackInfo(String packageName,
            final int[] installedUsers) {
        return new PackageRollbackInfo(
                new VersionedPackage(packageName, 2), new VersionedPackage(packageName, 1),
                new IntArray(), new ArrayList<>(), false, IntArray.wrap(installedUsers),
                new SparseLongArray());
    }

    private static PackageRollbackInfo createPackageRollbackInfo(String packageName) {
        return createPackageRollbackInfo(packageName, new int[] {});
    }

    @Test
    public void testRestoreAppDataSnapshot_pendingBackupForUser() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));

        PackageRollbackInfo info = createPackageRollbackInfo("com.foo");
        IntArray pendingBackups = info.getPendingBackups();
        pendingBackups.add(10);
        pendingBackups.add(11);

        assertTrue(helper.restoreAppData(13 /* rollbackId */, info, 10 /* userId */, 1 /* appId */,
                      "seinfo"));

        // Should only require FLAG_STORAGE_DE here because we have a pending backup that we
        // didn't manage to execute.
        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).restoreAppDataSnapshot(
                eq("com.foo"), eq(1) /* appId */, eq("seinfo"), eq(10) /* userId */,
                eq(13) /* rollbackId */, eq(Installer.FLAG_STORAGE_DE));
        inOrder.verifyNoMoreInteractions();

        assertEquals(1, pendingBackups.size());
        assertEquals(11, pendingBackups.get(0));
    }

    @Test
    public void testRestoreAppDataSnapshot_availableBackupForLockedUser() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));
        doReturn(true).when(helper).isUserCredentialLocked(eq(10));

        PackageRollbackInfo info = createPackageRollbackInfo("com.foo");

        assertTrue(helper.restoreAppData(73 /* rollbackId */, info, 10 /* userId */, 1 /* appId */,
                      "seinfo"));

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).restoreAppDataSnapshot(
                eq("com.foo"), eq(1) /* appId */, eq("seinfo"), eq(10) /* userId */,
                eq(73) /* rollbackId */, eq(Installer.FLAG_STORAGE_DE));
        inOrder.verifyNoMoreInteractions();

        ArrayList<RestoreInfo> pendingRestores = info.getPendingRestores();
        assertEquals(1, pendingRestores.size());
        assertEquals(10, pendingRestores.get(0).userId);
        assertEquals(1, pendingRestores.get(0).appId);
        assertEquals("seinfo", pendingRestores.get(0).seInfo);
    }

    @Test
    public void testRestoreAppDataSnapshot_availableBackupForUnlockedUser() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = spy(new AppDataRollbackHelper(installer));
        doReturn(false).when(helper).isUserCredentialLocked(eq(10));

        PackageRollbackInfo info = createPackageRollbackInfo("com.foo");
        assertFalse(helper.restoreAppData(101 /* rollbackId */, info, 10 /* userId */,
                      1 /* appId */, "seinfo"));

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).restoreAppDataSnapshot(
                eq("com.foo"), eq(1) /* appId */, eq("seinfo"), eq(10) /* userId */,
                eq(101) /* rollbackId */,
                eq(Installer.FLAG_STORAGE_DE | Installer.FLAG_STORAGE_CE));
        inOrder.verifyNoMoreInteractions();

        ArrayList<RestoreInfo> pendingRestores = info.getPendingRestores();
        assertEquals(0, pendingRestores.size());
    }

    @Test
    public void destroyAppData() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = new AppDataRollbackHelper(installer);

        PackageRollbackInfo info = createPackageRollbackInfo("com.foo.bar");
        info.putCeSnapshotInode(11, 239L);
        helper.destroyAppDataSnapshot(5 /* rollbackId */, info, 10 /* userId */);
        helper.destroyAppDataSnapshot(5 /* rollbackId */, info, 11 /* userId */);

        InOrder inOrder = Mockito.inOrder(installer);
        inOrder.verify(installer).destroyAppDataSnapshot(
                eq("com.foo.bar"), eq(10) /* userId */, eq(0L) /* ceSnapshotInode */,
                eq(5) /* rollbackId */, eq(Installer.FLAG_STORAGE_DE));
        inOrder.verify(installer).destroyAppDataSnapshot(
                eq("com.foo.bar"), eq(11) /* userId */, eq(239L) /* ceSnapshotInode */,
                eq(5) /* rollbackId */, eq(Installer.FLAG_STORAGE_DE | Installer.FLAG_STORAGE_CE));
        inOrder.verifyNoMoreInteractions();

        assertEquals(0, info.getCeSnapshotInodes().size());
    }

    @Test
    public void commitPendingBackupAndRestoreForUser() throws Exception {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = new AppDataRollbackHelper(installer);

        when(installer.snapshotAppData(anyString(), anyInt(), anyInt(), anyInt())).thenReturn(53L);

        // This one should be backed up.
        PackageRollbackInfo pendingBackup = createPackageRollbackInfo("com.foo", new int[]{37, 73});
        pendingBackup.addPendingBackup(37);

        // Nothing should be done for this one.
        PackageRollbackInfo wasRecentlyRestored = createPackageRollbackInfo("com.bar",
                new int[]{37, 73});
        wasRecentlyRestored.addPendingBackup(37);
        wasRecentlyRestored.getPendingRestores().add(
                new RestoreInfo(37 /* userId */, 239 /* appId*/, "seInfo"));

        // This one should be restored
        PackageRollbackInfo pendingRestore = createPackageRollbackInfo("com.abc",
                new int[]{37, 73});
        pendingRestore.putCeSnapshotInode(37, 1543L);
        pendingRestore.getPendingRestores().add(
                new RestoreInfo(37 /* userId */, 57 /* appId*/, "seInfo"));

        // This one shouldn't be processed, because it hasn't pending backups/restores for userId
        // 37.
        PackageRollbackInfo ignoredInfo = createPackageRollbackInfo("com.bar",
                new int[]{3, 73});
        wasRecentlyRestored.addPendingBackup(3);
        wasRecentlyRestored.addPendingBackup(73);
        wasRecentlyRestored.getPendingRestores().add(
                new RestoreInfo(73 /* userId */, 239 /* appId*/, "seInfo"));

        Rollback dataWithPendingBackup = new Rollback(101, new File("/does/not/exist"), -1);
        dataWithPendingBackup.info.getPackages().add(pendingBackup);

        Rollback dataWithRecentRestore = new Rollback(17239, new File("/does/not/exist"),
                -1);
        dataWithRecentRestore.info.getPackages().add(wasRecentlyRestored);

        Rollback dataForDifferentUser = new Rollback(17239, new File("/does/not/exist"),
                -1);
        dataForDifferentUser.info.getPackages().add(ignoredInfo);

        Rollback dataForRestore = new Rollback(17239, new File("/does/not/exist"), -1);
        dataForRestore.info.getPackages().add(pendingRestore);
        dataForRestore.info.getPackages().add(wasRecentlyRestored);

        InOrder inOrder = Mockito.inOrder(installer);

        // Check that pending backup and restore for the same package mutually destroyed each other.
        assertTrue(helper.commitPendingBackupAndRestoreForUser(37, dataWithRecentRestore));
        assertEquals(-1, wasRecentlyRestored.getPendingBackups().indexOf(37));
        assertNull(wasRecentlyRestored.getRestoreInfo(37));

        // Check that backup was performed.
        assertTrue(helper.commitPendingBackupAndRestoreForUser(37, dataWithPendingBackup));
        inOrder.verify(installer).snapshotAppData(eq("com.foo"), eq(37), eq(101),
                eq(Installer.FLAG_STORAGE_CE));
        assertEquals(-1, pendingBackup.getPendingBackups().indexOf(37));
        assertEquals(53, pendingBackup.getCeSnapshotInodes().get(37));

        // Check that restore was performed.
        assertTrue(helper.commitPendingBackupAndRestoreForUser(37, dataForRestore));
        inOrder.verify(installer).restoreAppDataSnapshot(
                eq("com.abc"), eq(57) /* appId */, eq("seInfo"), eq(37) /* userId */,
                eq(17239) /* rollbackId */, eq(Installer.FLAG_STORAGE_CE));
        assertNull(pendingRestore.getRestoreInfo(37));

        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void snapshotAddDataSavesSnapshottedUsersToInfo() {
        Installer installer = mock(Installer.class);
        AppDataRollbackHelper helper = new AppDataRollbackHelper(installer);

        PackageRollbackInfo info = createPackageRollbackInfo("com.foo.bar");
        helper.snapshotAppData(5, info, new int[]{10, 11});

        assertArrayEquals(info.getSnapshottedUsers().toArray(), new int[]{10, 11});
    }
}
