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
import static org.mockito.Mockito.when;

import android.content.pm.PackageManagerInternal;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.server.pm.PackageList;
import com.android.server.pm.parsing.pkg.PackageImpl;
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
import java.util.List;

@RunWith(JUnit4.class)
public class RollbackUnitTest {

    private static final String PKG_1 = "test.testpackage.pkg1";
    private static final String PKG_2 = "test.testpackage.pkg2";
    private static final String PKG_3 = "com.blah.hello.three";
    private static final String PKG_4 = "com.something.4pack";
    private static final int USER = 0;
    private static final String INSTALLER = "some.installer";

    @Mock private AppDataRollbackHelper mMockDataHelper;
    @Mock private PackageManagerInternal mMockPmi;

    private List<String> mPackages;
    private PackageList mPackageList;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mPackages = new ArrayList<>();
        mPackageList = new PackageList(mPackages, null);
        when(mMockPmi.getPackageList()).thenReturn(mPackageList);
    }

    private Rollback createStagedRollback(int rollbackId, File backupDir, int originalSessionId) {
        return new Rollback(rollbackId, backupDir, originalSessionId, /* isStaged */ true, USER,
                INSTALLER, null, new SparseIntArray(0));
    }

    private Rollback createNonStagedRollback(int rollbackId, File backupDir) {
        return new Rollback(rollbackId, backupDir, -1, /* isStaged */ false, USER,
                INSTALLER, null, new SparseIntArray(0));
    }

    @Test
    public void newEmptyStagedRollbackDefaults() {
        int rollbackId = 123;
        int sessionId = 567;
        File file = new File("/test/testing");

        Rollback rollback = createStagedRollback(rollbackId, file, sessionId);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.getBackupDir().getAbsolutePath()).isEqualTo("/test/testing");
        assertThat(rollback.isStaged()).isTrue();
        assertThat(rollback.getOriginalSessionId()).isEqualTo(567);
    }

    @Test
    public void newEmptyNonStagedRollbackDefaults() {
        int rollbackId = 123;
        File file = new File("/test/testing");

        Rollback rollback = createNonStagedRollback(rollbackId, file);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.getBackupDir().getAbsolutePath()).isEqualTo("/test/testing");
        assertThat(rollback.isStaged()).isFalse();
    }

    @Test
    public void rollbackMadeAvailable() {
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));

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
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));

        rollback.delete(mMockDataHelper, "test");

        assertThat(rollback.isDeleted()).isTrue();

        rollback.makeAvailable();

        assertThat(rollback.isAvailable()).isFalse();
        assertThat(rollback.isDeleted()).isTrue();
    }

    @Test
    public void getPackageNamesAllAndJustApex() {
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
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
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
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
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
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
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
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
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        rollback.delete(mMockDataHelper, "test");

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
    public void snapshotThenDeleteNoApex() {
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, false);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        int[] userIds = {111, 222};
        rollback.snapshotUserData(PKG_2, userIds, mMockDataHelper);

        verify(mMockDataHelper).snapshotAppData(eq(123), pkgRollbackInfoFor(PKG_2), eq(userIds));

        rollback.delete(mMockDataHelper, "test");

        verify(mMockDataHelper).destroyAppDataSnapshot(eq(123), pkgRollbackInfoFor(PKG_2), eq(111));
        verify(mMockDataHelper).destroyAppDataSnapshot(eq(123), pkgRollbackInfoFor(PKG_2), eq(222));
        verify(mMockDataHelper, never()).destroyApexDeSnapshots(anyInt());
        verify(mMockDataHelper, never()).destroyApexCeSnapshots(anyInt(), anyInt());

        assertThat(rollback.isDeleted()).isTrue();
    }

    @Test
    public void snapshotThenDeleteWithApex() {
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
        PackageRollbackInfo pkgInfo1 = newPkgInfoFor(PKG_1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = newPkgInfoFor(PKG_2, 18, 12, true);
        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2));

        int[] userIds = {111, 222};
        rollback.snapshotUserData(PKG_2, userIds, mMockDataHelper);

        verify(mMockDataHelper).snapshotAppData(eq(123), pkgRollbackInfoFor(PKG_2), eq(userIds));

        rollback.delete(mMockDataHelper, "test");

        verify(mMockDataHelper, never())
                .destroyAppDataSnapshot(anyInt(), pkgRollbackInfoFor(PKG_2), anyInt());
        verify(mMockDataHelper).destroyApexDeSnapshots(123);
        verify(mMockDataHelper).destroyApexCeSnapshots(111, 123);
        verify(mMockDataHelper).destroyApexCeSnapshots(222, 123);

        assertThat(rollback.isDeleted()).isTrue();
    }

    @Test
    public void restoreUserDataDoesNothingIfNotInProgress() {
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
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
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
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
        Rollback rollback = createNonStagedRollback(123, new File("/test/testing"));
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

    @Test
    public void allPackagesEnabled() {
        int[] sessionIds = new int[]{ 7777, 8888 };
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1, false, USER, INSTALLER,
                sessionIds, new SparseIntArray(0));
        // #allPackagesEnabled returns false when 1 out of 2 packages is enabled.
        rollback.info.getPackages().add(newPkgInfoFor(PKG_1, 12, 10, false));
        assertThat(rollback.allPackagesEnabled()).isFalse();
        // #allPackagesEnabled returns false for ApkInApex doesn't count.
        rollback.info.getPackages().add(newPkgInfoForApkInApex(PKG_3, 157, 156));
        assertThat(rollback.allPackagesEnabled()).isFalse();
        // #allPackagesEnabled returns true when 2 out of 2 packages are enabled.
        rollback.info.getPackages().add(newPkgInfoFor(PKG_2, 18, 12, true));
        assertThat(rollback.allPackagesEnabled()).isTrue();
    }

    @Test
    public void minExtVerConstraintNotViolated() {
        addPkgWithMinExtVersions("pkg0", new int[][] {{30, 4}});
        addPkgWithMinExtVersions("pkg1", new int[][] {});
        addPkgWithMinExtVersions("pkg2", new int[][] {{30, 5}, {31, 1}});
        addPkgWithMinExtVersions("pkg3", new int[][] {{31, 7}, {32, 15}});

        assertThat(Rollback.extensionVersionReductionWouldViolateConstraint(
                sparseArrayFrom(new int[][] {{30, 5}}), mMockPmi)).isFalse();
    }

    @Test
    public void minExtVerConstraintExists() {
        addPkgWithMinExtVersions("pkg0", null);
        addPkgWithMinExtVersions("pkg1", new int[][] {{30, 5}, {31, 1}});

        assertThat(Rollback.extensionVersionReductionWouldViolateConstraint(
                sparseArrayFrom(new int[][] {{30, 4}}), mMockPmi)).isTrue();
    }

    @Test
    public void minExtVerConstraintExistsOnOnePackage() {
        addPkgWithMinExtVersions("pkg0", new int[][] {{30, 4}});
        addPkgWithMinExtVersions("pkg1", new int[][] {});
        addPkgWithMinExtVersions("pkg2", new int[][] {{30, 5}, {31, 1}});
        addPkgWithMinExtVersions("pkg3", new int[][] {{31, 7}, {32, 15}});

        assertThat(Rollback.extensionVersionReductionWouldViolateConstraint(
                sparseArrayFrom(new int[][] {{30, 4}}), mMockPmi)).isTrue();
    }

    @Test
    public void minExtVerConstraintDifferentSdk() {
        addPkgWithMinExtVersions("pkg0", null);
        addPkgWithMinExtVersions("pkg1", new int[][] {{30, 5}, {31, 1}});

        assertThat(Rollback.extensionVersionReductionWouldViolateConstraint(
                sparseArrayFrom(new int[][] {{32, 4}}), mMockPmi)).isFalse();
    }

    @Test
    public void minExtVerConstraintNoneRecordedOnRollback() {
        addPkgWithMinExtVersions("pkg0", new int[][] {{30, 4}});
        addPkgWithMinExtVersions("pkg1", new int[][] {});
        addPkgWithMinExtVersions("pkg2", new int[][] {{30, 5}, {31, 1}});
        addPkgWithMinExtVersions("pkg3", new int[][] {{31, 7}, {32, 15}});

        assertThat(Rollback.extensionVersionReductionWouldViolateConstraint(
                new SparseIntArray(0), mMockPmi)).isFalse();
    }

    @Test
    public void minExtVerConstraintNoMinsRecorded() {
        addPkgWithMinExtVersions("pkg0", null);
        addPkgWithMinExtVersions("pkg1", null);

        assertThat(Rollback.extensionVersionReductionWouldViolateConstraint(
                sparseArrayFrom(new int[][] {{32, 4}}), mMockPmi)).isFalse();
    }

    private void addPkgWithMinExtVersions(String pkg, int[][] minExtVersions) {
        mPackages.add(pkg);
        PackageImpl pkgImpl = new PackageImpl(pkg, "baseCodePath", "codePath", null, false);
        pkgImpl.setMinExtensionVersions(sparseArrayFrom(minExtVersions));

        when(mMockPmi.getPackage(pkg)).thenReturn(pkgImpl);
    }

    private static SparseIntArray sparseArrayFrom(int[][] arr) {
        if (arr == null) {
            return null;
        }
        SparseIntArray result = new SparseIntArray(arr.length);
        for (int[] pair : arr) {
            result.put(pair[0], pair[1]);
        }
        return result;
    }

    @Test
    public void readAndWriteStagedRollbackIdsFile() throws Exception {
        File testFile = File.createTempFile("test", ".txt");
        RollbackPackageHealthObserver.writeStagedRollbackId(testFile, 2468, null);
        RollbackPackageHealthObserver.writeStagedRollbackId(testFile, 12345,
                new VersionedPackage("com.test.package", 1));
        RollbackPackageHealthObserver.writeStagedRollbackId(testFile, 13579,
                new VersionedPackage("com.test.package2", 2));
        SparseArray<String> readInfo =
                RollbackPackageHealthObserver.readStagedRollbackIds(testFile);
        assertThat(readInfo.size()).isEqualTo(3);

        assertThat(readInfo.keyAt(0)).isEqualTo(2468);
        assertThat(readInfo.valueAt(0)).isEqualTo("");
        assertThat(readInfo.keyAt(1)).isEqualTo(12345);
        assertThat(readInfo.valueAt(1)).isEqualTo("com.test.package");
        assertThat(readInfo.keyAt(2)).isEqualTo(13579);
        assertThat(readInfo.valueAt(2)).isEqualTo("com.test.package2");
    }

    private static PackageRollbackInfo newPkgInfoFor(
            String packageName, long fromVersion, long toVersion, boolean isApex) {
        return new PackageRollbackInfo(new VersionedPackage(packageName, fromVersion),
                new VersionedPackage(packageName, toVersion),
                new ArrayList<>(), new ArrayList<>(), isApex, false, new ArrayList<>());
    }

    /**
     * TODO: merge newPkgInfoFor and newPkgInfoForApkInApex by using enums to specify
     * 1. IS_APK
     * 2. IS_APEX
     * 3. IS_APK_IN_APEX
     */
    private static PackageRollbackInfo newPkgInfoForApkInApex(
            String packageName, long fromVersion, long toVersion) {
        return new PackageRollbackInfo(new VersionedPackage(packageName, fromVersion),
                new VersionedPackage(packageName, toVersion),
                new ArrayList<>(), new ArrayList<>(), false, true, new ArrayList<>());
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
