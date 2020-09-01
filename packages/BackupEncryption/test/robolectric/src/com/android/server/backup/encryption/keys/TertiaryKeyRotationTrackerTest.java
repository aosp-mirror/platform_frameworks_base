/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.keys;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Tests for {@link TertiaryKeyRotationTracker}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class TertiaryKeyRotationTrackerTest {
    private static final String PACKAGE_1 = "com.package.one";
    private static final int NUMBER_OF_BACKUPS_BEFORE_ROTATION = 31;

    private TertiaryKeyRotationTracker mTertiaryKeyRotationTracker;

    /** Instantiate a {@link TertiaryKeyRotationTracker} for use in tests. */
    @Before
    public void setUp() {
        mTertiaryKeyRotationTracker = newInstance();
    }

    /** New packages should not be due for key rotation. */
    @Test
    public void isKeyRotationDue_forNewPackage_isFalse() {
        // Simulate a new package by not calling simulateBackups(). As a result, PACKAGE_1 hasn't
        // been seen by mTertiaryKeyRotationTracker before.
        boolean keyRotationDue = mTertiaryKeyRotationTracker.isKeyRotationDue(PACKAGE_1);

        assertThat(keyRotationDue).isFalse();
    }

    /**
     * Key rotation should not be due after less than {@code NUMBER_OF_BACKUPS_BEFORE_ROTATION}
     * backups.
     */
    @Test
    public void isKeyRotationDue_afterLessThanRotationAmountBackups_isFalse() {
        simulateBackups(PACKAGE_1, NUMBER_OF_BACKUPS_BEFORE_ROTATION - 1);

        boolean keyRotationDue = mTertiaryKeyRotationTracker.isKeyRotationDue(PACKAGE_1);

        assertThat(keyRotationDue).isFalse();
    }

    /** Key rotation should be due after {@code NUMBER_OF_BACKUPS_BEFORE_ROTATION} backups. */
    @Test
    public void isKeyRotationDue_afterRotationAmountBackups_isTrue() {
        simulateBackups(PACKAGE_1, NUMBER_OF_BACKUPS_BEFORE_ROTATION);

        boolean keyRotationDue = mTertiaryKeyRotationTracker.isKeyRotationDue(PACKAGE_1);

        assertThat(keyRotationDue).isTrue();
    }

    /**
     * A call to {@link TertiaryKeyRotationTracker#resetCountdown(String)} should make sure no key
     * rotation is due.
     */
    @Test
    public void resetCountdown_makesKeyRotationNotDue() {
        simulateBackups(PACKAGE_1, NUMBER_OF_BACKUPS_BEFORE_ROTATION);

        mTertiaryKeyRotationTracker.resetCountdown(PACKAGE_1);

        assertThat(mTertiaryKeyRotationTracker.isKeyRotationDue(PACKAGE_1)).isFalse();
    }

    /**
     * New instances of {@link TertiaryKeyRotationTracker} should read state about the number of
     * backups from disk.
     */
    @Test
    public void isKeyRotationDue_forNewInstance_readsStateFromDisk() {
        simulateBackups(PACKAGE_1, NUMBER_OF_BACKUPS_BEFORE_ROTATION);

        boolean keyRotationDueForNewInstance = newInstance().isKeyRotationDue(PACKAGE_1);

        assertThat(keyRotationDueForNewInstance).isTrue();
    }

    /**
     * A call to {@link TertiaryKeyRotationTracker#markAllForRotation()} should mark all previously
     * seen packages for rotation.
     */
    @Test
    public void markAllForRotation_marksSeenPackagesForKeyRotation() {
        simulateBackups(PACKAGE_1, /*numberOfBackups=*/ 1);

        mTertiaryKeyRotationTracker.markAllForRotation();

        assertThat(mTertiaryKeyRotationTracker.isKeyRotationDue(PACKAGE_1)).isTrue();
    }

    /**
     * A call to {@link TertiaryKeyRotationTracker#markAllForRotation()} should not mark any new
     * packages for rotation.
     */
    @Test
    public void markAllForRotation_doesNotMarkUnseenPackages() {
        mTertiaryKeyRotationTracker.markAllForRotation();

        assertThat(mTertiaryKeyRotationTracker.isKeyRotationDue(PACKAGE_1)).isFalse();
    }

    private void simulateBackups(String packageName, int numberOfBackups) {
        while (numberOfBackups > 0) {
            mTertiaryKeyRotationTracker.recordBackup(packageName);
            numberOfBackups--;
        }
    }

    private static TertiaryKeyRotationTracker newInstance() {
        return TertiaryKeyRotationTracker.getInstance(RuntimeEnvironment.application);
    }
}
