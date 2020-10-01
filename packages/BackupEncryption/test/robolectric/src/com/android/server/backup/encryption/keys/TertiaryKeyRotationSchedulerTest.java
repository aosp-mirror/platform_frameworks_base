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

package com.android.server.backup.encryption.keys;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;
import static org.robolectric.RuntimeEnvironment.application;

import android.content.Context;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Tests for the tertiary key rotation scheduler */
@RunWith(RobolectricTestRunner.class)
public final class TertiaryKeyRotationSchedulerTest {

    private static final int MAXIMUM_ROTATIONS_PER_WINDOW = 2;
    private static final int MAX_BACKUPS_TILL_ROTATION = 31;
    private static final String SHARED_PREFS_NAME = "tertiary_key_rotation_tracker";
    private static final String PACKAGE_1 = "com.android.example1";
    private static final String PACKAGE_2 = "com.android.example2";

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Mock private Clock mClock;

    private File mFile;
    private TertiaryKeyRotationScheduler mScheduler;

    /** Setup the scheduler for test */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFile = temporaryFolder.newFile();
        mScheduler =
                new TertiaryKeyRotationScheduler(
                        new TertiaryKeyRotationTracker(
                                application.getSharedPreferences(
                                        SHARED_PREFS_NAME, Context.MODE_PRIVATE),
                                MAX_BACKUPS_TILL_ROTATION),
                        new TertiaryKeyRotationWindowedCount(mFile, mClock),
                        MAXIMUM_ROTATIONS_PER_WINDOW);
    }

    /** Test we don't trigger a rotation straight off */
    @Test
    public void isKeyRotationDue_isFalseInitially() {
        assertThat(mScheduler.isKeyRotationDue(PACKAGE_1)).isFalse();
    }

    /** Test we don't prematurely trigger a rotation */
    @Test
    public void isKeyRotationDue_isFalseAfterInsufficientBackups() {
        simulateBackups(MAX_BACKUPS_TILL_ROTATION - 1);
        assertThat(mScheduler.isKeyRotationDue(PACKAGE_1)).isFalse();
    }

    /** Test we do trigger a backup */
    @Test
    public void isKeyRotationDue_isTrueAfterEnoughBackups() {
        simulateBackups(MAX_BACKUPS_TILL_ROTATION);
        assertThat(mScheduler.isKeyRotationDue(PACKAGE_1)).isTrue();
    }

    /** Test rotation will occur if the quota allows */
    @Test
    public void isKeyRotationDue_isTrueIfRotationQuotaRemainsInWindow() {
        simulateBackups(MAX_BACKUPS_TILL_ROTATION);
        mScheduler.recordKeyRotation(PACKAGE_2);
        assertThat(mScheduler.isKeyRotationDue(PACKAGE_1)).isTrue();
    }

    /** Test rotation is blocked if the quota has been exhausted */
    @Test
    public void isKeyRotationDue_isFalseIfEnoughRotationsHaveHappenedInWindow() {
        simulateBackups(MAX_BACKUPS_TILL_ROTATION);
        mScheduler.recordKeyRotation(PACKAGE_2);
        mScheduler.recordKeyRotation(PACKAGE_2);
        assertThat(mScheduler.isKeyRotationDue(PACKAGE_1)).isFalse();
    }

    /** Test rotation is due after one window has passed */
    @Test
    public void isKeyRotationDue_isTrueAfterAWholeWindowHasPassed() {
        simulateBackups(MAX_BACKUPS_TILL_ROTATION);
        mScheduler.recordKeyRotation(PACKAGE_2);
        mScheduler.recordKeyRotation(PACKAGE_2);
        setTimeMillis(TimeUnit.HOURS.toMillis(24));
        assertThat(mScheduler.isKeyRotationDue(PACKAGE_1)).isTrue();
    }

    /** Test the rotation state changes after a rotation */
    @Test
    public void isKeyRotationDue_isFalseAfterRotation() {
        simulateBackups(MAX_BACKUPS_TILL_ROTATION);
        mScheduler.recordKeyRotation(PACKAGE_1);
        assertThat(mScheduler.isKeyRotationDue(PACKAGE_1)).isFalse();
    }

    /** Test the rate limiting for a given window */
    @Test
    public void isKeyRotationDue_neverAllowsMoreThanInWindow() {
        List<String> apps = makeTestApps(MAXIMUM_ROTATIONS_PER_WINDOW * MAX_BACKUPS_TILL_ROTATION);

        // simulate backups of all apps each night
        for (int i = 0; i < 300; i++) {
            setTimeMillis(i * TimeUnit.HOURS.toMillis(24));
            int rotationsThisNight = 0;
            for (String app : apps) {
                if (mScheduler.isKeyRotationDue(app)) {
                    rotationsThisNight++;
                    mScheduler.recordKeyRotation(app);
                } else {
                    mScheduler.recordBackup(app);
                }
            }
            assertThat(rotationsThisNight).isAtMost(MAXIMUM_ROTATIONS_PER_WINDOW);
        }
    }

    /** Test that backups are staggered over the window */
    @Test
    public void isKeyRotationDue_naturallyStaggersBackupsOverTime() {
        List<String> apps = makeTestApps(MAXIMUM_ROTATIONS_PER_WINDOW * MAX_BACKUPS_TILL_ROTATION);

        HashMap<String, ArrayList<Integer>> rotationDays = new HashMap<>();
        for (String app : apps) {
            rotationDays.put(app, new ArrayList<>());
        }

        // simulate backups of all apps each night
        for (int i = 0; i < 300; i++) {
            setTimeMillis(i * TimeUnit.HOURS.toMillis(24));
            for (String app : apps) {
                if (mScheduler.isKeyRotationDue(app)) {
                    rotationDays.get(app).add(i);
                    mScheduler.recordKeyRotation(app);
                } else {
                    mScheduler.recordBackup(app);
                }
            }
        }

        for (String app : apps) {
            List<Integer> days = rotationDays.get(app);
            for (int i = 1; i < days.size(); i++) {
                assertThat(days.get(i) - days.get(i - 1)).isEqualTo(MAX_BACKUPS_TILL_ROTATION + 1);
            }
        }
    }

    private ArrayList<String> makeTestApps(int n) {
        ArrayList<String> apps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            apps.add(String.format(Locale.US, "com.android.app%d", i));
        }
        return apps;
    }

    private void simulateBackups(int numberOfBackups) {
        while (numberOfBackups > 0) {
            mScheduler.recordBackup(PACKAGE_1);
            numberOfBackups--;
        }
    }

    private void setTimeMillis(long timeMillis) {
        when(mClock.millis()).thenReturn(timeMillis);
    }
}
