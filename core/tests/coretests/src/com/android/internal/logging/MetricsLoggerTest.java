/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.logging;

import static com.google.common.truth.Truth.assertThat;

import android.metrics.LogMaker;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.testing.FakeMetricsLogger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MetricsLoggerTest {
    private FakeMetricsLogger mLogger;

    private static final int TEST_ACTION = 42;

    @Before
    public void setUp() throws Exception {
        mLogger = new FakeMetricsLogger();
    }

    @Test
    public void testEmpty() throws Exception {
        assertThat(mLogger.getLogs().size()).isEqualTo(0);
    }

    @Test
    public void testAction() throws Exception {
        mLogger.action(TEST_ACTION);
        assertThat(mLogger.getLogs().size()).isEqualTo(1);
        final LogMaker event = mLogger.getLogs().peek();
        assertThat(event.getType()).isEqualTo(MetricsProto.MetricsEvent.TYPE_ACTION);
        assertThat(event.getCategory()).isEqualTo(TEST_ACTION);
    }

    @Test
    public void testVisible() throws Exception {
        // Limited testing to confirm we don't crash
        mLogger.visible(TEST_ACTION);
        mLogger.hidden(TEST_ACTION);
        mLogger.visibility(TEST_ACTION, true);
        mLogger.visibility(TEST_ACTION, false);
    }
}
