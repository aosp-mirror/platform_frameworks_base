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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/** Tests for {@link TertiaryKeyRotationWindowedCount}. */
@RunWith(RobolectricTestRunner.class)
public class TertiaryKeyRotationWindowedCountTest {
    private static final int TIMESTAMP_SIZE_IN_BYTES = 8;

    @Rule public final TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Mock private Clock mClock;

    private File mFile;
    private TertiaryKeyRotationWindowedCount mWindowedcount;

    /** Setup the windowed counter for testing */
    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        mFile = mTemporaryFolder.newFile();
        mWindowedcount = new TertiaryKeyRotationWindowedCount(mFile, mClock);
    }

    /** Test handling bad files */
    @Test
    public void constructor_doesNotFailForBadFile() throws IOException {
        new TertiaryKeyRotationWindowedCount(mTemporaryFolder.newFolder(), mClock);
    }

    /** Test the count is 0 to start */
    @Test
    public void getCount_isZeroInitially() {
        assertThat(mWindowedcount.getCount()).isEqualTo(0);
    }

    /** Test the count is correct for a time window */
    @Test
    public void getCount_includesResultsInLastTwentyFourHours() {
        setTimeMillis(0);
        mWindowedcount.record();
        setTimeMillis(TimeUnit.HOURS.toMillis(4));
        mWindowedcount.record();
        setTimeMillis(TimeUnit.HOURS.toMillis(23));
        mWindowedcount.record();
        mWindowedcount.record();
        assertThat(mWindowedcount.getCount()).isEqualTo(4);
    }

    /** Test old results are ignored */
    @Test
    public void getCount_ignoresResultsOlderThanTwentyFourHours() {
        setTimeMillis(0);
        mWindowedcount.record();
        setTimeMillis(TimeUnit.HOURS.toMillis(24));
        assertThat(mWindowedcount.getCount()).isEqualTo(0);
    }

    /** Test future events are removed if the clock moves backways (e.g. DST, TZ change) */
    @Test
    public void getCount_removesFutureEventsIfClockHasChanged() {
        setTimeMillis(1000);
        mWindowedcount.record();
        setTimeMillis(0);
        assertThat(mWindowedcount.getCount()).isEqualTo(0);
    }

    /** Check recording doesn't fail for a bad file */
    @Test
    public void record_doesNotFailForBadFile() throws Exception {
        new TertiaryKeyRotationWindowedCount(mTemporaryFolder.newFolder(), mClock).record();
    }

    /** Checks the state is persisted */
    @Test
    public void record_persistsStateToDisk() {
        setTimeMillis(0);
        mWindowedcount.record();
        assertThat(new TertiaryKeyRotationWindowedCount(mFile, mClock).getCount()).isEqualTo(1);
    }

    /** Test the file doesn't contain unnecessary data */
    @Test
    public void record_compactsFileToLast24Hours() {
        setTimeMillis(0);
        mWindowedcount.record();
        assertThat(mFile.length()).isEqualTo(TIMESTAMP_SIZE_IN_BYTES);
        setTimeMillis(1);
        mWindowedcount.record();
        assertThat(mFile.length()).isEqualTo(2 * TIMESTAMP_SIZE_IN_BYTES);
        setTimeMillis(TimeUnit.HOURS.toMillis(24));
        mWindowedcount.record();
        assertThat(mFile.length()).isEqualTo(2 * TIMESTAMP_SIZE_IN_BYTES);
    }

    private void setTimeMillis(long timeMillis) {
        when(mClock.millis()).thenReturn(timeMillis);
    }
}
