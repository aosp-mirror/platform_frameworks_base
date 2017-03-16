/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.timezone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;

import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@SmallTest
public class PackageStatusStorageTest {
    private static final PackageVersions VALID_PACKAGE_VERSIONS = new PackageVersions(1, 2);

    private PackageStatusStorage mPackageStatusStorage;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getContext();

        // Using the instrumentation context means the database is created in a test app-specific
        // directory.
        mPackageStatusStorage = new PackageStatusStorage(context);
    }

    @After
    public void tearDown() throws Exception {
        mPackageStatusStorage.deleteDatabaseForTests();
    }

    @Test
    public void getPackageStatus_initialState() {
        assertNull(mPackageStatusStorage.getPackageStatus());
    }

    @Test
    public void resetCheckState() {
        // Assert initial state.
        assertNull(mPackageStatusStorage.getPackageStatus());

        CheckToken token1 = mPackageStatusStorage.generateCheckToken(VALID_PACKAGE_VERSIONS);

        // There should now be a state.
        assertNotNull(mPackageStatusStorage.getPackageStatus());

        // Now clear the state.
        mPackageStatusStorage.resetCheckState();

        // After reset, there should be no package state again.
        assertNull(mPackageStatusStorage.getPackageStatus());

        CheckToken token2 = mPackageStatusStorage.generateCheckToken(VALID_PACKAGE_VERSIONS);

        // Token after a reset should still be distinct.
        assertFalse(token1.equals(token2));

        // Now clear the state again.
        mPackageStatusStorage.resetCheckState();

        // After reset, there should be no package state again.
        assertNull(mPackageStatusStorage.getPackageStatus());

        CheckToken token3 = mPackageStatusStorage.generateCheckToken(VALID_PACKAGE_VERSIONS);

        // A CheckToken generated after a reset should still be distinct.
        assertFalse(token2.equals(token3));
    }

    @Test
    public void generateCheckToken_missingRowBehavior() {
        // Assert initial state.
        assertNull(mPackageStatusStorage.getPackageStatus());

        CheckToken token1 = mPackageStatusStorage.generateCheckToken(VALID_PACKAGE_VERSIONS);
        assertNotNull(token1);

        // There should now be state.
        assertNotNull(mPackageStatusStorage.getPackageStatus());

        // Corrupt the table by removing the one row.
        mPackageStatusStorage.deleteRowForTests();

        // Check that generateCheckToken recovers.
        assertNotNull(mPackageStatusStorage.generateCheckToken(VALID_PACKAGE_VERSIONS));
    }

    @Test
    public void getPackageStatus_missingRowBehavior() {
        // Assert initial state.
        assertNull(mPackageStatusStorage.getPackageStatus());

        CheckToken token1 = mPackageStatusStorage.generateCheckToken(VALID_PACKAGE_VERSIONS);
        assertNotNull(token1);

        // There should now be a state.
        assertNotNull(mPackageStatusStorage.getPackageStatus());

        // Corrupt the table by removing the one row.
        mPackageStatusStorage.deleteRowForTests();

        assertNull(mPackageStatusStorage.getPackageStatus());
    }

    @Test
    public void markChecked_missingRowBehavior() {
        // Assert initial state.
        CheckToken token1 = mPackageStatusStorage.generateCheckToken(VALID_PACKAGE_VERSIONS);
        assertNotNull(token1);

        // There should now be a state.
        assertNotNull(mPackageStatusStorage.getPackageStatus());

        // Corrupt the table by removing the one row.
        mPackageStatusStorage.deleteRowForTests();

        // The missing row should mean token1 is now considered invalid, so we should get a false.
        assertFalse(mPackageStatusStorage.markChecked(token1, true /* succeeded */));

        // The storage should have recovered and we should be able to carry on like before.
        CheckToken token2 = mPackageStatusStorage.generateCheckToken(VALID_PACKAGE_VERSIONS);
        assertTrue(mPackageStatusStorage.markChecked(token2, true /* succeeded */));
    }

    @Test
    public void checkToken_tokenIsUnique() {
        PackageVersions packageVersions = VALID_PACKAGE_VERSIONS;
        PackageStatus expectedPackageStatus =
                new PackageStatus(PackageStatus.CHECK_STARTED, packageVersions);

        CheckToken token1 = mPackageStatusStorage.generateCheckToken(packageVersions);
        assertEquals(packageVersions, token1.mPackageVersions);

        PackageStatus actualPackageStatus1 = mPackageStatusStorage.getPackageStatus();
        assertEquals(expectedPackageStatus, actualPackageStatus1);

        CheckToken token2 = mPackageStatusStorage.generateCheckToken(packageVersions);
        assertEquals(packageVersions, token1.mPackageVersions);
        assertFalse(token1.mOptimisticLockId == token2.mOptimisticLockId);
        assertFalse(token1.equals(token2));
    }

    @Test
    public void markChecked_checkSucceeded() {
        PackageVersions packageVersions = VALID_PACKAGE_VERSIONS;

        CheckToken token = mPackageStatusStorage.generateCheckToken(packageVersions);
        boolean writeOk = mPackageStatusStorage.markChecked(token, true /* succeeded */);
        assertTrue(writeOk);

        PackageStatus expectedPackageStatus =
                new PackageStatus(PackageStatus.CHECK_COMPLETED_SUCCESS, packageVersions);
        assertEquals(expectedPackageStatus, mPackageStatusStorage.getPackageStatus());
    }

    @Test
    public void markChecked_checkFailed() {
        PackageVersions packageVersions = VALID_PACKAGE_VERSIONS;

        CheckToken token = mPackageStatusStorage.generateCheckToken(packageVersions);
        boolean writeOk = mPackageStatusStorage.markChecked(token, false /* succeeded */);
        assertTrue(writeOk);

        PackageStatus expectedPackageStatus =
                new PackageStatus(PackageStatus.CHECK_COMPLETED_FAILURE, packageVersions);
        assertEquals(expectedPackageStatus, mPackageStatusStorage.getPackageStatus());
    }

    @Test
    public void markChecked_optimisticLocking_multipleToken() {
        PackageVersions packageVersions = VALID_PACKAGE_VERSIONS;
        CheckToken token1 = mPackageStatusStorage.generateCheckToken(packageVersions);
        CheckToken token2 = mPackageStatusStorage.generateCheckToken(packageVersions);

        PackageStatus packageStatusBeforeChecked = mPackageStatusStorage.getPackageStatus();

        boolean writeOk1 = mPackageStatusStorage.markChecked(token1, true /* succeeded */);
        // Generation of token2 should mean that token1 is no longer valid.
        assertFalse(writeOk1);
        assertEquals(packageStatusBeforeChecked, mPackageStatusStorage.getPackageStatus());

        boolean writeOk2 = mPackageStatusStorage.markChecked(token2, true /* succeeded */);
        // token2 should still be valid, and the attempt with token1 should have had no effect.
        assertTrue(writeOk2);
        PackageStatus expectedPackageStatus =
                new PackageStatus(PackageStatus.CHECK_COMPLETED_SUCCESS, packageVersions);
        assertEquals(expectedPackageStatus, mPackageStatusStorage.getPackageStatus());
    }

    @Test
    public void markChecked_optimisticLocking_repeatedTokenUse() {
        PackageVersions packageVersions = VALID_PACKAGE_VERSIONS;
        CheckToken token = mPackageStatusStorage.generateCheckToken(packageVersions);

        boolean writeOk1 = mPackageStatusStorage.markChecked(token, true /* succeeded */);
        assertTrue(writeOk1);

        PackageStatus expectedPackageStatus =
                new PackageStatus(PackageStatus.CHECK_COMPLETED_SUCCESS, packageVersions);
        assertEquals(expectedPackageStatus, mPackageStatusStorage.getPackageStatus());

        // token cannot be reused.
        boolean writeOk2 = mPackageStatusStorage.markChecked(token, true /* succeeded */);
        assertFalse(writeOk2);
        assertEquals(expectedPackageStatus, mPackageStatusStorage.getPackageStatus());
    }
}
