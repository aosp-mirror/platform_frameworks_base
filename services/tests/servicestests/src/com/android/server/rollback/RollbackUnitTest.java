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

import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.util.IntArray;
import android.util.SparseLongArray;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

@RunWith(JUnit4.class)
public class RollbackUnitTest {

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
    public void rollbackStateChanges() {
        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);

        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.isAvailable()).isFalse();
        assertThat(rollback.isCommitted()).isFalse();

        rollback.setAvailable();

        assertThat(rollback.isEnabling()).isFalse();
        assertThat(rollback.isAvailable()).isTrue();
        assertThat(rollback.isCommitted()).isFalse();

        rollback.setCommitted();

        assertThat(rollback.isEnabling()).isFalse();
        assertThat(rollback.isAvailable()).isFalse();
        assertThat(rollback.isCommitted()).isTrue();
    }

    @Test
    public void getPackageNamesAllAndJustApex() {
        String pkg1 = "test.testpackage.pkg1";
        String pkg2 = "test.testpackage.pkg2";
        String pkg3 = "com.blah.hello.three";
        String pkg4 = "com.something.4pack";

        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = pkgInfoFor(pkg1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = pkgInfoFor(pkg2, 12, 10, true);
        PackageRollbackInfo pkgInfo3 = pkgInfoFor(pkg3, 12, 10, false);
        PackageRollbackInfo pkgInfo4 = pkgInfoFor(pkg4, 12, 10, true);

        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2, pkgInfo3, pkgInfo4));

        assertThat(rollback.getPackageNames()).containsExactly(pkg1, pkg2, pkg3, pkg4);
        assertThat(rollback.getApexPackageNames()).containsExactly(pkg2, pkg4);
    }

    @Test
    public void includesPackages() {
        String pkg1 = "test.testpackage.pkg1";
        String pkg2 = "test.testpackage.pkg2";
        String pkg3 = "com.blah.hello.three";
        String pkg4 = "com.something.4pack";

        Rollback rollback = new Rollback(123, new File("/test/testing"), -1);
        PackageRollbackInfo pkgInfo1 = pkgInfoFor(pkg1, 12, 10, false);
        PackageRollbackInfo pkgInfo2 = pkgInfoFor(pkg2, 18, 12, true);
        PackageRollbackInfo pkgInfo3 = pkgInfoFor(pkg3, 157, 156, false);
        PackageRollbackInfo pkgInfo4 = pkgInfoFor(pkg4, 99, 1, true);

        rollback.info.getPackages().addAll(Arrays.asList(pkgInfo1, pkgInfo2, pkgInfo3, pkgInfo4));

        assertThat(rollback.includesPackage(pkg2)).isTrue();
        assertThat(rollback.includesPackage(pkg3)).isTrue();
        assertThat(rollback.includesPackage("com.something.else")).isFalse();

        assertThat(rollback.includesPackageWithDifferentVersion(pkg1, 12)).isFalse();
        assertThat(rollback.includesPackageWithDifferentVersion(pkg1, 1)).isTrue();

        assertThat(rollback.includesPackageWithDifferentVersion(pkg2, 18)).isFalse();
        assertThat(rollback.includesPackageWithDifferentVersion(pkg2, 12)).isTrue();

        assertThat(rollback.includesPackageWithDifferentVersion(pkg3, 157)).isFalse();
        assertThat(rollback.includesPackageWithDifferentVersion(pkg3, 156)).isTrue();
        assertThat(rollback.includesPackageWithDifferentVersion(pkg3, 15)).isTrue();

        assertThat(rollback.includesPackageWithDifferentVersion(pkg4, 99)).isFalse();
        assertThat(rollback.includesPackageWithDifferentVersion(pkg4, 100)).isTrue();
    }

    private static PackageRollbackInfo pkgInfoFor(
            String packageName, long fromVersion, long toVersion, boolean isApex) {
        return new PackageRollbackInfo(new VersionedPackage(packageName, fromVersion),
                new VersionedPackage(packageName, toVersion),
                new IntArray(), new ArrayList<>(), isApex, new IntArray(), new SparseLongArray());
    }
}
