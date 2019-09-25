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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.util.IntArray;
import android.util.SparseLongArray;

import com.google.common.collect.Range;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;

@RunWith(JUnit4.class)
public class RollbackUnitTest {

    private static final String PKG_1 = "test.testpackage.pkg1";
    private static final String PKG_2 = "test.testpackage.pkg2";
    private static final String PKG_3 = "com.blah.hello.three";
    private static final String PKG_4 = "com.something.4pack";

    @Mock private AppDataRollbackHelper mMockDataHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void newEmptyStagedRollbackDefaults() {
        int rollbackId = 123;
        int sessionId = 567;
        File file = new File("/test/testing");

        Rollback rollback = new Rollback(rollbackId, file, sessionId);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.getBackupDir().getAbsolutePath()).isEqualTo("/test/testing");
        assertThat(rollback.isStaged()).isTrue();
        assertThat(rollback.getStagedSessionId()).isEqualTo(567);
    }

    @Test
    public void newEmptyNonStagedRollbackDefaults() {
        int rollbackId = 123;
        File file = new File("/test/testing");

        Rollback rollback = new Rollback(rollbackId, file, -1);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.getBackupDir().getAbsolutePath()).isEqualTo("/test/testing");
        assertThat(rollback.isStaged()).isFalse();
    }

    @Test
    public void rollbackMadeAvailable() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.isAvailable()).isFalse();
        assertThat(rollback.isCommitted()).isFalse();

        Instant availableTime = Instant.now();
        rollback.makeAvailable();

        assertThat(rollback.isEnabling()).isFalse();
        assertThat(rollback.isAvailable()).isTrue();
        assertThat(rollback.isCommitted()).isFalse();

        assertThat(rollback.getTimestamp()).isIn(Range.closed(availableTime, Instant.now()));
    }

    @Test
    public void deletedRollbackCannotBeMadeAvailable() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);

        rollback.delete(mMockDataHelper);

        assertThat(rollback.isDeleted()).isTrue();

        rollback.makeAvailable();

        assertThat(rollback.isAvailable()).isFalse();
        assertThat(rollback.isDeleted()).isTrue();
    }

    @Test
    public void getPackageNamesAllAndJustApex() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 11, true);
        PackageRollbackInfo pkgInfo3 = newPkgInfoFor(PKG_3, 19, 1, false);
        PackageRollbackInfo pkgInfo4 = newPkgInfoFor(PKG_4, 12, 1, true);

        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2, pkgInfo3, pkgInfo4));

        assertThat(rollback.getPackageNames()).containsExactly(PKG_1, PKG_2, PKG_3, PKG_4);
        assertThat(rollback.getApexPackageNames()).containsExactly(PKG_2, PKG_4);
    }

    @Test
    public void includesPackagesAfterEnable() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        PackageRollbackInfo pkgInfo3 = newPkgInfoFor(PKG_3, 157, 156, false);
        PackageRollbackInfo pkgInfo4 = newPkgInfoFor(PKG_4, 99, 1, true);

        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2, pkgInfo3, pkgInfo4));

        assertThat(rollback.includesPackage(PKG_2)).isTrue();
        assertThat(rollback.includesPackage(PKG_3)).isTrue();
        assertThat(rollback.includesPackage("com.something.else")).isFalse();

        assertThat(rollback.includesPackageWithDifferentVersion(PKG_1, 12)).isFalse();
        assertThat(rollback.includesPackageWithDifferentVersion(PKG_1, 1)).isTrue();

        assertThat(rollback.includesPackageWithDifferentVersion(PKG_2, 18)).isFalse();
        assertThat(rollback.includesPackageWithDifferentVersion(PKG_2, 12)).isTrue();

        assertThat(rollback.includesPackageWithDifferentVersion(PKG_3, 157)).isFalse();
        assertThat(rollback.includesPackageWithDifferentVersion(PKG_3, 156)).isTrue();
        assertThat(rollback.includesPackageWithDifferentVersion(PKG_3, 15)).isTrue();

        assertThat(rollback.includesPackageWithDifferentVersion(PKG_4, 99)).isFalse();
        assertThat(rollback.includesPackageWithDifferentVersion(PKG_4, 100)).isTrue();
    }

    @Test
    public void snapshotWhenEnabling() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        assertThat(rollback.isEnabling()).isTrue();

        int[] userIds = {4, 77};
        rollback.snapshotUserData(PKG_2, userIds, mMockDataHelper);

        // Data is snapshotted for the specified package.
        verify(mMockDataHelper).snapshotAppData(eq(123), pkgRollbackInfoFor(PKG_2), eq(userIds));
        verify(mMockDataHelper, never())
                .snapshotAppData(anyInt(), pkgRollbackInfoFor(PKG_1), any());
    }

    @Test
    public void snapshotWhenAvailable() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        rollback.makeAvailable();

        assertThat(rollback.isAvailable()).isTrue();

        int[] userIds = {4, 77};
        rollback.snapshotUserData(PKG_2, userIds, mMockDataHelper);

        // No data is snapshotted as rollback was not in the enabling state.
        verify(mMockDataHelper, never())
                .snapshotAppData(anyInt(), pkgRollbackInfoFor(PKG_1), any());
        verify(mMockDataHelper, never())
                .snapshotAppData(anyInt(), pkgRollbackInfoFor(PKG_2), any());
    }

    @Test
    public void snapshotWhenDeleted() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        rollback.delete(mMockDataHelper);

        assertThat(rollback.isDeleted()).isTrue();

        int[] userIds = {4, 77};
        rollback.snapshotUserData(PKG_2, userIds, mMockDataHelper);

        // No data is snapshotted as rollback was not in the enabling state.
        verify(mMockDataHelper, never())
                .snapshotAppData(anyInt(), pkgRollbackInfoFor(PKG_1), any());
        verify(mMockDataHelper, never())
                .snapshotAppData(anyInt(), pkgRollbackInfoFor(PKG_2), any());
    }

    @Test
    public void snapshotThenDelete() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        int[] userIds = {12, 18};
        rollback.snapshotUserData(PKG_2, userIds, mMockDataHelper);

        verify(mMockDataHelper).snapshotAppData(eq(123), pkgRollbackInfoFor(PKG_2), eq(userIds));

        rollback.delete(mMockDataHelper);

        verify(mMockDataHelper).destroyAppDataSnapshot(eq(123), pkgRollbackInfoFor(PKG_2), eq(12));
        verify(mMockDataHelper).destroyAppDataSnapshot(eq(123), pkgRollbackInfoFor(PKG_2), eq(18));

        assertThat(rollback.isDeleted()).isTrue();
    }

    @Test
    public void restoreUserDataDoesNothingIfNotInProgress() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        assertThat(rollback.isRestoreUserDataInProgress()).isFalse();

        assertThat(rollback.restoreUserDataForPackageIfInProgress(
                PKG_1, new int[] { 5 }, 333, "", mMockDataHelper)).isFalse();

        verify(mMockDataHelper, never()).restoreAppData(anyInt(), any(), anyInt(), anyInt(), any());
    }

    @Test
    public void restoreUserDataDoesNothingIfPackageNotFound() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        rollback.setRestoreUserDataInProgress(true);
        assertThat(rollback.isRestoreUserDataInProgress()).isTrue();

        assertThat(rollback.restoreUserDataForPackageIfInProgress(
                PKG_3, new int[] { 5 }, 333, "", mMockDataHelper)).isFalse();

        verify(mMockDataHelper, never()).restoreAppData(anyInt(), any(), anyInt(), anyInt(), any());
    }

    @Test
    public void restoreUserDataRestoresIfInProgressAndPackageFound() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        rollback.setRestoreUserDataInProgress(true);
        assertThat(rollback.isRestoreUserDataInProgress()).isTrue();

        assertThat(rollback.restoreUserDataForPackageIfInProgress(
                PKG_1, new int[] { 5, 7 }, 333, "blah", mMockDataHelper)).isTrue();

        verify(mMockDataHelper).restoreAppData(123, pkgInfo1, 5, 333, "blah");
        verify(mMockDataHelper).restoreAppData(123, pkgInfo1, 7, 333, "blah");
    }

    private static PackageRollbackInfo newPkgInfoFor(
            String packageName, long fromVersion, long toVersion, boolean isApex) {
        return new PackageRollbackInfo(new VersionedPackage(packageName, fromVersion),
                new VersionedPackage(packageName, toVersion),
                new IntArray(), new ArrayList<>(), isApex, new IntArray(), new SparseLongArray());
    }

    private static class PackageRollbackInfoForPackage implements
            ArgumentMatcher<PackageRollbackInfo> {
        private final String mPkg;

        PackageRollbackInfoForPackage(String pkg) {
            mPkg = pkg;
        }

        @Override
        public boolean matches(PackageRollbackInfo pkgRollbackInfo) {
            return pkgRollbackInfo.getPackageName().equals(mPkg);
        }
    }

    private static PackageRollbackInfo pkgRollbackInfoFor(String pkg) {
        return argThat(new PackageRollbackInfoForPackage(pkg));
    }
}
