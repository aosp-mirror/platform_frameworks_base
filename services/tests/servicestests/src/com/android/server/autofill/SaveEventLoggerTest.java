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
package com.android.server.autofill;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class SaveEventLoggerTest {

    @Test
    public void testTimestampsInitialized() {
        SaveEventLogger mLogger = spy(SaveEventLogger.forSessionId(1, 1));

        mLogger.maybeSetLatencySaveUiDisplayMillis();
        mLogger.maybeSetLatencySaveRequestMillis();
        mLogger.maybeSetLatencySaveFinishMillis();

        ArgumentCaptor<Long> latencySaveUiDisplayMillis = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> latencySaveRequestMillis = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> latencySaveFinishMillis = ArgumentCaptor.forClass(Long.class);

        verify(mLogger, times(1))
                .maybeSetLatencySaveUiDisplayMillis(latencySaveUiDisplayMillis.capture());
        verify(mLogger, times(1))
                .maybeSetLatencySaveRequestMillis(latencySaveRequestMillis.capture());
        verify(mLogger, times(1))
                .maybeSetLatencySaveFinishMillis(latencySaveFinishMillis.capture());

        assertThat(latencySaveUiDisplayMillis.getValue())
                .isNotEqualTo(SaveEventLogger.UNINITIATED_TIMESTAMP);
        assertThat(latencySaveRequestMillis.getValue())
                .isNotEqualTo(SaveEventLogger.UNINITIATED_TIMESTAMP);
        assertThat(latencySaveFinishMillis.getValue())
                .isNotEqualTo(SaveEventLogger.UNINITIATED_TIMESTAMP);
    }

    @Test
    public void testTimestampsNotInitialized() {
        SaveEventLogger mLogger =
                spy(SaveEventLogger.forSessionId(1, SaveEventLogger.UNINITIATED_TIMESTAMP));

        mLogger.maybeSetLatencySaveUiDisplayMillis();
        mLogger.maybeSetLatencySaveRequestMillis();
        mLogger.maybeSetLatencySaveFinishMillis();
        ArgumentCaptor<Long> latencySaveUiDisplayMillis = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> latencySaveRequestMillis = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> latencySaveFinishMillis = ArgumentCaptor.forClass(Long.class);

        verify(mLogger, times(1))
                .maybeSetLatencySaveUiDisplayMillis(latencySaveUiDisplayMillis.capture());
        verify(mLogger, times(1))
                .maybeSetLatencySaveRequestMillis(latencySaveRequestMillis.capture());
        verify(mLogger, times(1))
                .maybeSetLatencySaveFinishMillis(latencySaveFinishMillis.capture());

        assertThat(latencySaveUiDisplayMillis.getValue())
                .isEqualTo(SaveEventLogger.UNINITIATED_TIMESTAMP);
        assertThat(latencySaveRequestMillis.getValue())
                .isEqualTo(SaveEventLogger.UNINITIATED_TIMESTAMP);
        assertThat(latencySaveFinishMillis.getValue())
                .isEqualTo(SaveEventLogger.UNINITIATED_TIMESTAMP);
    }
}
