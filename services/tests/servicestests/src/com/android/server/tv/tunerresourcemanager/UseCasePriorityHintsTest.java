/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.tv.tunerresourcemanager;

import static com.google.common.truth.Truth.assertThat;

import android.media.tv.TvInputService;
import android.platform.test.annotations.Presubmit;
import android.util.Slog;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link UseCasePriorityHints} class.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class UseCasePriorityHintsTest {
    private static final String TAG = "UseCasePriorityHintsTest";
    private UseCasePriorityHints mPriorityHints;

    private final String mExampleXML =
            "<!-- A sample Use Case Priority Hints xml -->"
            + "<config version=\"1.0\" xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
            + "<useCaseDefault fgPriority=\"150\" bgPriority=\"50\"/>"
            + "<useCasePreDefined type=\"USE_CASE_RECORD\" fgPriority=\"600\" bgPriority=\"500\"/>"
            + "<useCasePreDefined type=\"USE_CASE_LIVE\" fgPriority=\"490\" bgPriority=\"400\"/>"
            + "<useCasePreDefined type=\"USE_CASE_PLAYBACK\" fgPriority=\"480\""
            + " bgPriority=\"300\"/>"
            + "<useCasePreDefined type=\"USE_CASE_BACKGROUND\" fgPriority=\"180\""
            + " bgPriority=\"100\"/>"
            + "<useCaseVendor type=\"VENDOR_USE_CASE_1\" id=\"1001\" fgPriority=\"300\""
            + " bgPriority=\"80\"/>"
            + "</config>";

    @Before
    public void setUp() throws Exception {
        mPriorityHints = new UseCasePriorityHints();
        try {
            mPriorityHints.parseInternal(
                    new ByteArrayInputStream(mExampleXML.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Error parse xml.", e);
        }
    }

    @Test
    public void parseTest_parseSampleXml() {
        // Pre-defined foreground
        assertThat(mPriorityHints.getForegroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_BACKGROUND)).isEqualTo(180);
        assertThat(mPriorityHints.getForegroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_SCAN)).isEqualTo(150);
        assertThat(mPriorityHints.getForegroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK)).isEqualTo(480);
        assertThat(mPriorityHints.getForegroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE)).isEqualTo(490);
        assertThat(mPriorityHints.getForegroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD)).isEqualTo(600);

        // Pre-defined background
        assertThat(mPriorityHints.getBackgroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_BACKGROUND)).isEqualTo(100);
        assertThat(mPriorityHints.getBackgroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_SCAN)).isEqualTo(50);
        assertThat(mPriorityHints.getBackgroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK)).isEqualTo(300);
        assertThat(mPriorityHints.getBackgroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE)).isEqualTo(400);
        assertThat(mPriorityHints.getBackgroundPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD)).isEqualTo(500);

        // Vendor use case
        assertThat(mPriorityHints.getForegroundPriority(1001)).isEqualTo(300);
        assertThat(mPriorityHints.getBackgroundPriority(1001)).isEqualTo(80);
    }

    @Test
    public void isDefinedUseCaseTest_invalidUseCase() {
        assertThat(mPriorityHints.isDefinedUseCase(1992)).isFalse();
    }

    @Test
    public void isDefinedUseCaseTest_validUseCase() {
        assertThat(mPriorityHints.isDefinedUseCase(1001)).isTrue();
        assertThat(mPriorityHints.isDefinedUseCase(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD)).isTrue();
    }
}
