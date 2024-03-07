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

package android.content.pm;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link android.content.pm.LauncherActivityInfo}
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LauncherActivityInfoTest {

    @Test
    public void testTrimStart() {
        // Invisible case
        assertThat(LauncherActivityInfo.trimStart("\u0009").toString()).isEmpty();
        // It is not supported in the system font
        assertThat(LauncherActivityInfo.trimStart("\u0FE1").toString()).isEmpty();
        // Surrogates case
        assertThat(LauncherActivityInfo.trimStart("\uD83E\uDD36").toString())
                .isEqualTo("\uD83E\uDD36");
        assertThat(LauncherActivityInfo.trimStart("\u0009\u0FE1\uD83E\uDD36A").toString())
                .isEqualTo("\uD83E\uDD36A");
        assertThat(LauncherActivityInfo.trimStart("\uD83E\uDD36A\u0009\u0FE1").toString())
                .isEqualTo("\uD83E\uDD36A\u0009\u0FE1");
        assertThat(LauncherActivityInfo.trimStart("A\uD83E\uDD36\u0009\u0FE1A").toString())
                .isEqualTo("A\uD83E\uDD36\u0009\u0FE1A");
        assertThat(LauncherActivityInfo.trimStart(
                "A\uD83E\uDD36\u0009\u0FE1A\uD83E\uDD36").toString())
                .isEqualTo("A\uD83E\uDD36\u0009\u0FE1A\uD83E\uDD36");
        assertThat(LauncherActivityInfo.trimStart(
                "\u0009\u0FE1\uD83E\uDD36A\u0009\u0FE1").toString())
                .isEqualTo("\uD83E\uDD36A\u0009\u0FE1");
    }

    @Test
    public void testTrimEnd() {
        // Invisible case
        assertThat(LauncherActivityInfo.trimEnd("\u0009").toString()).isEmpty();
        // It is not supported in the system font
        assertThat(LauncherActivityInfo.trimEnd("\u0FE1").toString()).isEmpty();
        // Surrogates case
        assertThat(LauncherActivityInfo.trimEnd("\uD83E\uDD36").toString())
                .isEqualTo("\uD83E\uDD36");
        assertThat(LauncherActivityInfo.trimEnd("\u0009\u0FE1\uD83E\uDD36A").toString())
                .isEqualTo("\u0009\u0FE1\uD83E\uDD36A");
        assertThat(LauncherActivityInfo.trimEnd("\uD83E\uDD36A\u0009\u0FE1").toString())
                .isEqualTo("\uD83E\uDD36A");
        assertThat(LauncherActivityInfo.trimEnd("A\uD83E\uDD36\u0009\u0FE1A").toString())
                .isEqualTo("A\uD83E\uDD36\u0009\u0FE1A");
        assertThat(LauncherActivityInfo.trimEnd(
                "A\uD83E\uDD36\u0009\u0FE1A\uD83E\uDD36").toString())
                .isEqualTo("A\uD83E\uDD36\u0009\u0FE1A\uD83E\uDD36");
        assertThat(LauncherActivityInfo.trimEnd(
                "\u0009\u0FE1\uD83E\uDD36A\u0009\u0FE1").toString())
                .isEqualTo("\u0009\u0FE1\uD83E\uDD36A");
    }

    @Test
    public void testTrim() {
        // Invisible case
        assertThat(LauncherActivityInfo.trim("\u0009").toString()).isEmpty();
        // It is not supported in the system font
        assertThat(LauncherActivityInfo.trim("\u0FE1").toString()).isEmpty();
        // Surrogates case
        assertThat(LauncherActivityInfo.trim("\uD83E\uDD36").toString())
                .isEqualTo("\uD83E\uDD36");
        assertThat(LauncherActivityInfo.trim("\u0009\u0FE1\uD83E\uDD36A").toString())
                .isEqualTo("\uD83E\uDD36A");
        assertThat(LauncherActivityInfo.trim("\uD83E\uDD36A\u0009\u0FE1").toString())
                .isEqualTo("\uD83E\uDD36A");
        assertThat(LauncherActivityInfo.trim("A\uD83E\uDD36\u0009\u0FE1A").toString())
                .isEqualTo("A\uD83E\uDD36\u0009\u0FE1A");
        assertThat(LauncherActivityInfo.trim(
                "A\uD83E\uDD36\u0009\u0FE1A\uD83E\uDD36").toString())
                .isEqualTo("A\uD83E\uDD36\u0009\u0FE1A\uD83E\uDD36");
        assertThat(LauncherActivityInfo.trim(
                "\u0009\u0FE1\uD83E\uDD36A\u0009\u0FE1").toString())
                .isEqualTo("\uD83E\uDD36A");
    }
}
