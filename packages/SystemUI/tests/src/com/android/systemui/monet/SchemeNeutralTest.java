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

package com.android.systemui.monet;

import static com.android.systemui.monet.utils.ArgbSubject.assertThat;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.monet.dynamiccolor.MaterialDynamicColors;
import com.android.systemui.monet.hct.Hct;
import com.android.systemui.monet.scheme.SchemeNeutral;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
public final class SchemeNeutralTest extends SysuiTestCase {

    @Test
    public void testKeyColors() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 0.0);

        assertThat(MaterialDynamicColors.primaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff767685);
        assertThat(MaterialDynamicColors.secondaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff777680);
        assertThat(MaterialDynamicColors.tertiaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff75758B);
        assertThat(MaterialDynamicColors.neutralPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff787678);
        assertThat(MaterialDynamicColors.neutralVariantPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff787678);
    }

    @Test
    public void lightTheme_minContrast_primary() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff737383);
    }

    @Test
    public void lightTheme_standardContrast_primary() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff5d5d6c);
    }

    @Test
    public void lightTheme_maxContrast_primary() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff21212e);
    }

    @Test
    public void lightTheme_minContrast_primaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffe2e1f3);
    }

    @Test
    public void lightTheme_standardContrast_primaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffe2e1f3);
    }

    @Test
    public void lightTheme_maxContrast_primaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff414250);
    }

    @Test
    public void lightTheme_minContrast_onPrimaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff636372);
    }

    @Test
    public void lightTheme_standardContrast_onPrimaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff1a1b27);
    }

    @Test
    public void lightTheme_maxContrast_onPrimaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffd9d8ea);
    }

    @Test
    public void lightTheme_minContrast_surface() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffcf8fa);
    }

    @Test
    public void lightTheme_standardContrast_surface() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffcf8fa);
    }

    @Test
    public void lightTheme_maxContrast_surface() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffcf8fa);
    }

    @Test
    public void darkTheme_minContrast_primary() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff737383);
    }

    @Test
    public void darkTheme_standardContrast_primary() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xffc6c5d6);
    }

    @Test
    public void darkTheme_maxContrast_primary() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xfff6f4ff);
    }

    @Test
    public void darkTheme_minContrast_primaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff454654);
    }

    @Test
    public void darkTheme_standardContrast_primaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff454654);
    }

    @Test
    public void darkTheme_maxContrast_primaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffcac9da);
    }

    @Test
    public void darkTheme_minContrast_onPrimaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffb5b3c4);
    }

    @Test
    public void darkTheme_standardContrast_onPrimaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffe2e1f3);
    }

    @Test
    public void darkTheme_maxContrast_onPrimaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff373846);
    }

    @Test
    public void darkTheme_minContrast_onTertiaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xffb3b3cb);
    }

    @Test
    public void darkTheme_standardContrast_onTertiaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xffe1e0f9);
    }

    @Test
    public void darkTheme_maxContrast_onTertiaryContainer() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xff37374b);
    }

    @Test
    public void darkTheme_minContrast_surface() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff131315);
    }

    @Test
    public void darkTheme_standardContrast_surface() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff131315);
    }

    @Test
    public void darkTheme_maxContrast_surface() {
        SchemeNeutral scheme = new SchemeNeutral(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff131315);
    }
}
