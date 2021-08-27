/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.tv;

import static android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING;
import static android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN;
import static android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING;
import static android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.android.internal.util.FrameworkStatsLog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/**
 * Tests for {@link TvInputManagerService}.
 */
@Presubmit
@RunWith(JUnit4.class)
public class TvInputServiceManagerTest {

    @Test
    public void getVideoUnavailableReasonForStatsd_tuning() {
        assertThat(TvInputManagerService.getVideoUnavailableReasonForStatsd(
                VIDEO_UNAVAILABLE_REASON_TUNING
        )).isEqualTo(
                FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_TUNING);
    }

    @Test
    public void getVideoUnavailableReasonForStatsd_unknown() {
        assertThat(TvInputManagerService.getVideoUnavailableReasonForStatsd(
                VIDEO_UNAVAILABLE_REASON_UNKNOWN
        )).isEqualTo(
                FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_UNKNOWN);
    }


    @Test
    public void getVideoUnavailableReasonForStatsd_casBuffering() {
        assertThat(TvInputManagerService.getVideoUnavailableReasonForStatsd(
                VIDEO_UNAVAILABLE_REASON_BUFFERING
        )).isEqualTo(
                FrameworkStatsLog
                        .TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_BUFFERING);
    }

    @Test
    public void getVideoUnavailableReasonForStatsd_casUnknown() {
        assertThat(TvInputManagerService.getVideoUnavailableReasonForStatsd(
                VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN
        )).isEqualTo(
                FrameworkStatsLog
                        .TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN);
    }

    @Test
    public void getVideoUnavailableReasonForStatsd_negative() {
        assertThat(TvInputManagerService.getVideoUnavailableReasonForStatsd(
                -1
        )).isEqualTo(
                FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_UNKNOWN);
    }

    @Test
    public void getVideoUnavailableReasonForStatsd_oneBelow() {
        assertThat(TvInputManagerService.getVideoUnavailableReasonForStatsd(
                VIDEO_UNAVAILABLE_REASON_UNKNOWN - 1
        )).isEqualTo(
                FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_UNKNOWN);
    }

    @Test
    public void getVideoUnavailableReasonForStatsd_oneAbove() {
        assertThat(TvInputManagerService.getVideoUnavailableReasonForStatsd(
                VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN + 1
        )).isEqualTo(
                FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_UNKNOWN);
    }

}
