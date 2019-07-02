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
 * limitations under the License.
 */

package com.android.server.wm.flicker;

import static com.android.server.wm.flicker.TestFileUtils.readTestFile;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.wm.flicker.Assertions.Result;

import org.junit.Before;
import org.junit.Test;

/**
 * Contains {@link WindowManagerTrace} tests.
 * To run this test: {@code atest FlickerLibTest:WindowManagerTraceTest}
 */
public class WindowManagerTraceTest {
    private WindowManagerTrace mTrace;

    private static WindowManagerTrace readWindowManagerTraceFromFile(String relativePath) {
        try {
            return WindowManagerTrace.parseFrom(readTestFile(relativePath));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void setup() {
        mTrace = readWindowManagerTraceFromFile("wm_trace_openchrome.pb");
    }

    @Test
    public void canParseAllEntries() {
        assertThat(mTrace.getEntries().get(0).getTimestamp()).isEqualTo(241777211939236L);
        assertThat(mTrace.getEntries().get(mTrace.getEntries().size() - 1).getTimestamp()).isEqualTo
                (241779809471942L);
    }

    @Test
    public void canDetectAboveAppWindowVisibility() {
        WindowManagerTrace.Entry entry = mTrace.getEntry(241777211939236L);
        Result result = entry.isAboveAppWindowVisible("NavigationBar");
        assertThat(result.passed()).isTrue();
    }

    @Test
    public void canDetectBelowAppWindowVisibility() {
        WindowManagerTrace.Entry entry = mTrace.getEntry(241777211939236L);
        Result result = entry.isBelowAppWindowVisible("wallpaper");
        assertThat(result.passed()).isTrue();
    }

    @Test
    public void canDetectAppWindowVisibility() {
        WindowManagerTrace.Entry entry = mTrace.getEntry(241777211939236L);
        Result result = entry.isAppWindowVisible("com.google.android.apps.nexuslauncher");
        assertThat(result.passed()).isTrue();
    }

    @Test
    public void canFailWithReasonForVisibilityChecks_windowNotFound() {
        WindowManagerTrace.Entry entry = mTrace.getEntry(241777211939236L);
        Result result = entry.isAboveAppWindowVisible("ImaginaryWindow");
        assertThat(result.failed()).isTrue();
        assertThat(result.reason).contains("ImaginaryWindow cannot be found");
    }

    @Test
    public void canFailWithReasonForVisibilityChecks_windowNotVisible() {
        WindowManagerTrace.Entry entry = mTrace.getEntry(241777211939236L);
        Result result = entry.isAboveAppWindowVisible("AssistPreviewPanel");
        assertThat(result.failed()).isTrue();
        assertThat(result.reason).contains("AssistPreviewPanel is invisible");
    }

    @Test
    public void canDetectAppZOrder() {
        WindowManagerTrace.Entry entry = mTrace.getEntry(241778130296410L);
        Result result = entry.isVisibleAppWindowOnTop("com.google.android.apps.chrome");
        assertThat(result.passed()).isTrue();
    }

    @Test
    public void canFailWithReasonForZOrderChecks_windowNotOnTop() {
        WindowManagerTrace.Entry entry = mTrace.getEntry(241778130296410L);
        Result result = entry.isVisibleAppWindowOnTop("com.google.android.apps.nexuslauncher");
        assertThat(result.failed()).isTrue();
        assertThat(result.reason).contains("wanted=com.google.android.apps.nexuslauncher");
        assertThat(result.reason).contains("found=com.android.chrome/"
                + "com.google.android.apps.chrome.Main");
    }
}
