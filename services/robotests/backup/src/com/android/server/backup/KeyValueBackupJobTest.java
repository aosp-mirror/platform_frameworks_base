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
 * limitations under the License
 */

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class KeyValueBackupJobTest {
    private Context mContext;
    private BackupManagerConstants mConstants;

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.application;
        mConstants = new BackupManagerConstants(Handler.getMain(), mContext.getContentResolver());
        mConstants.start();
    }

    @After
    public void tearDown() throws Exception {
        mConstants.stop();
        KeyValueBackupJob.cancel(mContext);
    }

    @Test
    public void testIsScheduled_beforeScheduling_returnsFalse() {
        boolean isScheduled = KeyValueBackupJob.isScheduled();

        assertThat(isScheduled).isFalse();
    }

    @Test
    public void testIsScheduled_afterScheduling_returnsTrue() {
        KeyValueBackupJob.schedule(mContext, mConstants);

        boolean isScheduled = KeyValueBackupJob.isScheduled();

        assertThat(isScheduled).isTrue();
    }
}
