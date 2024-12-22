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
    public void testIsVisible_normal() {
        // normal
        assertThat(LauncherActivityInfo.isVisible("label")).isTrue();
        // 1 surrogates case
        assertThat(LauncherActivityInfo.isVisible("\uD83E\uDD36")).isTrue();
    }

    @Test
    public void testIsVisible_onlyInvisibleCharacter() {
        // 1 invisible
        assertThat(LauncherActivityInfo.isVisible("\u0009")).isFalse();
        // 2 invisible
        assertThat(LauncherActivityInfo.isVisible("\u0009\u3164")).isFalse();
        // 3 invisible
        assertThat(LauncherActivityInfo.isVisible("\u3000\u0009\u3164")).isFalse();
        // 4 invisible
        assertThat(LauncherActivityInfo.isVisible("\u200F\u3000\u0009\u3164")).isFalse();
    }

    @Test
    public void testIsVisible_onlyNotSupportedCharacter() {
        // 1 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0FE1")).isFalse();
        // 2 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0FE2")).isFalse();
        // 3 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0FE2\u0FE3")).isFalse();
        // 4 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0FE2\u0FE3\u0FE4")).isFalse();
    }

    @Test
    public void testIsVisible_invisibleAndNotSupportedCharacter() {
        // 1 invisible, 1 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0009\u0FE1")).isFalse();
        // 1 invisible, 2 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0009\u0FE1\u0FE2")).isFalse();
        // 1 invisible, 3 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0009\u0FE1\u0FE2\u0FE3")).isFalse();
        // 1 invisible, 4 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0009\u0FE1\u0FE2\u0FE3\u0FE4")).isFalse();

        // 2 invisible, 1 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0009\u3164\u0FE1")).isFalse();
        // 2 invisible, 2 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0009\u3164\u0FE1\u0FE2")).isFalse();
        // 2 invisible, 3 not supported
        assertThat(LauncherActivityInfo.isVisible("\u0009\u3164\u0FE1\u0FE2\u0FE3")).isFalse();
        // 2 invisible, 4 not supported
        assertThat(LauncherActivityInfo.isVisible(
                "\u0009\u3164\u0FE1\u0FE2\u0FE3\u0FE4")).isFalse();

        // 3 invisible, 1 not supported
        assertThat(LauncherActivityInfo.isVisible("\u3000\u0009\u3164\u0FE1")).isFalse();
        // 3 invisible, 2 not supported
        assertThat(LauncherActivityInfo.isVisible("\u3000\u0009\u3164\u0FE1\u0FE2")).isFalse();
        // 3 invisible, 3 not supported
        assertThat(LauncherActivityInfo.isVisible(
                "\u3000\u0009\u3164\u0FE1\u0FE2\u0FE3")).isFalse();
        // 3 invisible, 4 not supported
        assertThat(LauncherActivityInfo.isVisible(
                "\u3000\u0009\u3164\u0FE1\u0FE2\u0FE3\u0FE4")).isFalse();

        // 4 invisible, 1 not supported
        assertThat(LauncherActivityInfo.isVisible("\u200F\u3000\u0009\u3164\u0FE1")).isFalse();
        // 4 invisible, 2 not supported
        assertThat(LauncherActivityInfo.isVisible(
                "\u200F\u3000\u0009\u3164\u0FE1\u0FE2")).isFalse();
        // 4 invisible, 3 not supported
        assertThat(LauncherActivityInfo.isVisible(
                "\u200F\u3000\u0009\u3164\u0FE1\u0FE2\u0FE3")).isFalse();
        // 4 invisible, 4 not supported
        assertThat(LauncherActivityInfo.isVisible(
                "\u200F\u3000\u0009\u3164\u0FE1\u0FE2\u0FE3\u0FE4")).isFalse();

        // 1 not supported, 1 invisible,
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0009")).isFalse();
        // 1 not supported, 2 invisible
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0009\u3164")).isFalse();
        // 1 not supported, 3 invisible
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u3000\u0009\u3164")).isFalse();
        // 1 not supported, 4 invisible
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u200F\u3000\u0009\u3164")).isFalse();
    }

    @Test
    public void testIsVisible_invisibleAndNormalCharacter() {
        // 1 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0009\uD83E\uDD36")).isTrue();
        // 2 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0009\u3164\uD83E\uDD36")).isTrue();
        // 3 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u3000\u0009\u3164\uD83E\uDD36")).isFalse();
        // 4 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u200F\u3000\u0009\u3164\uD83E\uDD36")).isFalse();
    }

    @Test
    public void testIsVisible_notSupportedAndNormalCharacter() {
        // 1 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\uD83E\uDD36")).isTrue();
        // 2 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0FE2\uD83E\uDD36")).isTrue();
        // 3 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0FE2\u0FE3\uD83E\uDD36")).isTrue();
        // 4 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u0FE1\u0FE2\u0FE3\u0FE4\uD83E\uDD36")).isTrue();
    }

    @Test
    public void testIsVisible_mixAllCharacter() {
        // 1 invisible, 1 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0009\u0FE1\uD83E\uDD36")).isTrue();
        // 1 invisible, 1 not supported, 1 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0009\u0FE1\u3164\uD83E\uDD36")).isTrue();
        // 1 invisible, 1 not supported, 2 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u0009\u0FE1\u3000\u3164\uD83E\uDD36")).isTrue();
        // 1 invisible, 1 not supported, 3 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u0009\u0FE1\u200F\u3000\u3164\uD83E\uDD36")).isTrue();

        // 2 invisible, 1 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0009\u3164\u0FE1\uD83E\uDD36")).isTrue();
        // 2 invisible, 2 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u0009\u3164\u0FE1\u0FE2\uD83E\uDD36")).isTrue();

        // 3 invisible, 1 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u3000\u0009\u3164\u0FE1\uD83E\uDD36")).isFalse();
        // 3 invisible, 2 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u3000\u0009\u3164\u0FE1\u0FE2\uD83E\uDD36")).isFalse();
        // 3 invisible, 3 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u3000\u0009\u3164\u0FE1\u0FE2\u0FE3\uD83E\uDD36")).isFalse();

        // 4 invisible, 1 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u200F\u3000\u0009\u3164\u0FE1\uD83E\uDD36")).isFalse();
        // 4 invisible, 2 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u200F\u3000\u0009\u3164\u0FE1\u0FE2\uD83E\uDD36")).isFalse();
        // 4 invisible, 3 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u200F\u3000\u0009\u3164\u0FE1\u0FE2\u0FE3\uD83E\uDD36")).isFalse();
        // 4 invisible, 4 not supported, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u200F\u3000\u0009\u3164\u0FE1\u0FE2\u0FE3\u0FE4\uD83E\uDD36")).isFalse();

        // 1 not supported, 1 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0009\uD83E\uDD36")).isTrue();
        // 1 not supported, 2 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible("\u0FE1\u0009\u3164\uD83E\uDD36")).isTrue();
        // 1 not supported, 3 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u0FE1\u3000\u0009\u3164\uD83E\uDD36")).isTrue();
        // 1 not supported, 4 invisible, 1 surrogates
        assertThat(LauncherActivityInfo.isVisible(
                "\u0FE1\u200F\u3000\u0009\u3164\uD83E\uDD36")).isTrue();

    }
}
