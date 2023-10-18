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

package com.android.server.media.projection;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.SharedPreferences;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * Tests for the {@link MediaProjectionTimestampStore} class.
 *
 * <p>Build/Install/Run: atest FrameworksServicesTests:MediaProjectionTimestampStoreTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class MediaProjectionTimestampStoreTest {

    private static final String TEST_PREFS_FILE = "media-projection-timestamp-test";

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final File mSharedPreferencesFile = new File(mContext.getCacheDir(), TEST_PREFS_FILE);
    private final SharedPreferences mSharedPreferences = createSharePreferences();

    private Instant mCurrentInstant = Instant.ofEpochMilli(0);

    private final InstantSource mInstantSource = () -> mCurrentInstant;
    private final MediaProjectionTimestampStore mStore =
            new MediaProjectionTimestampStore(mSharedPreferences, mInstantSource);

    @Before
    public void setUp() {
        mSharedPreferences.edit().clear().commit();
    }

    @After
    public void tearDown() {
        mSharedPreferences.edit().clear().commit();
        mSharedPreferencesFile.delete();
    }

    @Test
    public void timeSinceLastActiveSession_byDefault_returnsNull() {
        assertThat(mStore.timeSinceLastActiveSession()).isNull();
    }

    @Test
    public void timeSinceLastActiveSession_returnsBasedOnLastActiveSessionEnded() {
        mCurrentInstant = Instant.ofEpochMilli(0);
        mStore.registerActiveSessionEnded();

        mCurrentInstant = mCurrentInstant.plusSeconds(60);

        assertThat(mStore.timeSinceLastActiveSession()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    public void timeSinceLastActiveSession_valueIsPersisted() {
        mCurrentInstant = Instant.ofEpochMilli(0);
        mStore.registerActiveSessionEnded();

        MediaProjectionTimestampStore newStoreInstance =
                new MediaProjectionTimestampStore(createSharePreferences(), mInstantSource);
        mCurrentInstant = mCurrentInstant.plusSeconds(123);

        assertThat(newStoreInstance.timeSinceLastActiveSession())
                .isEqualTo(Duration.ofSeconds(123));
    }

    private SharedPreferences createSharePreferences() {
        return mContext.getSharedPreferences(mSharedPreferencesFile, Context.MODE_PRIVATE);
    }
}
