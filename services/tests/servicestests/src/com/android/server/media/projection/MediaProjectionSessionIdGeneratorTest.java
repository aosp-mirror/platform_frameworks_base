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

/**
 * Tests for the {@link MediaProjectionSessionIdGenerator} class.
 *
 * <p>Build/Install/Run: atest FrameworksServicesTests:MediaProjectionSessionIdGeneratorTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class MediaProjectionSessionIdGeneratorTest {

    private static final String TEST_PREFS_FILE = "media-projection-session-id-test";

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final File mSharedPreferencesFile = new File(mContext.getCacheDir(), TEST_PREFS_FILE);
    private final SharedPreferences mSharedPreferences = createSharePreferences();
    private final MediaProjectionSessionIdGenerator mGenerator =
            createGenerator(mSharedPreferences);

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
    public void getCurrentSessionId_byDefault_returns0() {
        assertThat(mGenerator.getCurrentSessionId()).isEqualTo(0);
    }

    @Test
    public void getCurrentSessionId_multipleTimes_returnsSameValue() {
        assertThat(mGenerator.getCurrentSessionId()).isEqualTo(0);
        assertThat(mGenerator.getCurrentSessionId()).isEqualTo(0);
        assertThat(mGenerator.getCurrentSessionId()).isEqualTo(0);
    }

    @Test
    public void createAndGetNewSessionId_returnsIncrementedId() {
        int previousValue = mGenerator.getCurrentSessionId();

        int newValue = mGenerator.createAndGetNewSessionId();

        assertThat(newValue).isEqualTo(previousValue + 1);
    }

    @Test
    public void createAndGetNewSessionId_persistsNewValue() {
        int newValue = mGenerator.createAndGetNewSessionId();

        MediaProjectionSessionIdGenerator newInstance = createGenerator(createSharePreferences());

        assertThat(newInstance.getCurrentSessionId()).isEqualTo(newValue);
    }

    private SharedPreferences createSharePreferences() {
        return mContext.getSharedPreferences(mSharedPreferencesFile, Context.MODE_PRIVATE);
    }

    private MediaProjectionSessionIdGenerator createGenerator(SharedPreferences sharedPreferences) {
        return new MediaProjectionSessionIdGenerator(sharedPreferences);
    }
}
