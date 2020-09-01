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

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.backup.encryption.CryptoSettings;
import com.android.server.backup.encryption.tasks.StartSecondaryKeyRotationTask;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.io.File;
import java.time.Clock;

@Config(shadows = SecondaryKeyRotationSchedulerTest.ShadowStartSecondaryKeyRotationTask.class)
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class SecondaryKeyRotationSchedulerTest {
    private static final String SENTINEL_FILE_PATH = "force_secondary_key_rotation";

    @Mock private RecoverableKeyStoreSecondaryKeyManager mSecondaryKeyManager;
    @Mock private Clock mClock;

    private CryptoSettings mCryptoSettings;
    private SecondaryKeyRotationScheduler mScheduler;
    private long mRotationIntervalMillis;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context application = ApplicationProvider.getApplicationContext();

        mCryptoSettings = CryptoSettings.getInstanceForTesting(application);
        mRotationIntervalMillis = mCryptoSettings.backupSecondaryKeyRotationIntervalMs();

        mScheduler =
                new SecondaryKeyRotationScheduler(
                        application, mSecondaryKeyManager, mCryptoSettings, mClock);
        ShadowStartSecondaryKeyRotationTask.reset();
    }

    @Test
    public void startRotationIfScheduled_rotatesIfRotationWasFarEnoughInThePast() {
        long lastRotated = 100009;
        mCryptoSettings.setSecondaryLastRotated(lastRotated);
        setNow(lastRotated + mRotationIntervalMillis);

        mScheduler.startRotationIfScheduled();

        assertThat(ShadowStartSecondaryKeyRotationTask.sRan).isTrue();
    }

    @Test
    public void startRotationIfScheduled_setsNewRotationTimeIfRotationWasFarEnoughInThePast() {
        long lastRotated = 100009;
        long now = lastRotated + mRotationIntervalMillis;
        mCryptoSettings.setSecondaryLastRotated(lastRotated);
        setNow(now);

        mScheduler.startRotationIfScheduled();

        assertThat(mCryptoSettings.getSecondaryLastRotated().get()).isEqualTo(now);
    }

    @Test
    public void startRotationIfScheduled_rotatesIfClockHasChanged() {
        long lastRotated = 100009;
        mCryptoSettings.setSecondaryLastRotated(lastRotated);
        setNow(lastRotated - 1);

        mScheduler.startRotationIfScheduled();

        assertThat(ShadowStartSecondaryKeyRotationTask.sRan).isTrue();
    }

    @Test
    public void startRotationIfScheduled_rotatesIfSentinelFileIsPresent() throws Exception {
        File file = new File(RuntimeEnvironment.application.getFilesDir(), SENTINEL_FILE_PATH);
        file.createNewFile();

        mScheduler.startRotationIfScheduled();

        assertThat(ShadowStartSecondaryKeyRotationTask.sRan).isTrue();
    }

    @Test
    public void startRotationIfScheduled_setsNextRotationIfClockHasChanged() {
        long lastRotated = 100009;
        long now = lastRotated - 1;
        mCryptoSettings.setSecondaryLastRotated(lastRotated);
        setNow(now);

        mScheduler.startRotationIfScheduled();

        assertThat(mCryptoSettings.getSecondaryLastRotated().get()).isEqualTo(now);
    }

    @Test
    public void startRotationIfScheduled_doesNothingIfRotationWasRecentEnough() {
        long lastRotated = 100009;
        mCryptoSettings.setSecondaryLastRotated(lastRotated);
        setNow(lastRotated + mRotationIntervalMillis - 1);

        mScheduler.startRotationIfScheduled();

        assertThat(ShadowStartSecondaryKeyRotationTask.sRan).isFalse();
    }

    @Test
    public void startRotationIfScheduled_doesNotSetRotationTimeIfRotationWasRecentEnough() {
        long lastRotated = 100009;
        mCryptoSettings.setSecondaryLastRotated(lastRotated);
        setNow(lastRotated + mRotationIntervalMillis - 1);

        mScheduler.startRotationIfScheduled();

        assertThat(mCryptoSettings.getSecondaryLastRotated().get()).isEqualTo(lastRotated);
    }

    @Test
    public void startRotationIfScheduled_setsLastRotatedToNowIfNeverRotated() {
        long now = 13295436;
        setNow(now);

        mScheduler.startRotationIfScheduled();

        assertThat(mCryptoSettings.getSecondaryLastRotated().get()).isEqualTo(now);
    }

    private void setNow(long timestamp) {
        when(mClock.millis()).thenReturn(timestamp);
    }

    @Implements(StartSecondaryKeyRotationTask.class)
    public static class ShadowStartSecondaryKeyRotationTask {
        private static boolean sRan = false;

        @Implementation
        public void run() {
            sRan = true;
        }

        @Resetter
        public static void reset() {
            sRan = false;
        }
    }
}
