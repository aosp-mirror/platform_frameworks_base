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
import com.android.systemui.monet.scheme.SchemeContent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
public final class SchemeContentTest extends SysuiTestCase {

    @Test
    public void testKeyColors() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xff0000ff), false, 0.0);

        assertThat(MaterialDynamicColors.primaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff080CFF);
        assertThat(MaterialDynamicColors.secondaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff656DD3);
        assertThat(MaterialDynamicColors.tertiaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff81009F);
        assertThat(MaterialDynamicColors.neutralPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff767684);
        assertThat(MaterialDynamicColors.neutralVariantPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff757589);
    }

    @Test
    public void lightTheme_minContrast_primary() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xFF1218FF);
    }

    @Test
    public void lightTheme_standardContrast_primary() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xFF0001C3);
    }

    @Test
    public void lightTheme_maxContrast_primary() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xFF000181);
    }

    @Test
    public void lightTheme_minContrast_primaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF5660FF);
    }

    @Test
    public void lightTheme_standardContrast_primaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF2D36FF);
    }

    @Test
    public void lightTheme_maxContrast_primaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF0000E3);
    }

    @Test
    public void lightTheme_minContrast_tertiaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFB042CC);
    }

    @Test
    public void lightTheme_standardContrast_tertiaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF9221AF);
    }

    @Test
    public void lightTheme_maxContrast_tertiaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF73008E);
    }

    @Test
    public void lightTheme_minContrast_objectionableTertiaryContainerLightens() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF850096), false, -1.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFD03A71);
    }

    @Test
    public void lightTheme_standardContrast_objectionableTertiaryContainerLightens() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF850096), false, 0.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFAC1B57);
    }

    @Test
    public void lightTheme_maxContrast_objectionableTertiaryContainerDarkens() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF850096), false, 1.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF870040);
    }

    @Test
    public void lightTheme_minContrast_onPrimaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFCBCDFF);
    }

    @Test
    public void lightTheme_standardContrast_onPrimaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFCECFFF);
    }

    @Test
    public void lightTheme_maxContrast_onPrimaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFD6D6FF);
    }

    @Test
    public void lightTheme_minContrast_surface() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, -1);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xFFFBF8FF);
    }

    @Test
    public void lightTheme_standardContrast_surface() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xFFFBF8FF);
    }

    @Test
    public void lightTheme_maxContrast_surface() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xFFFBF8FF);
    }

    @Test
    public void darkTheme_minContrast_primary() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xFF5660FF);
    }

    @Test
    public void darkTheme_standardContrast_primary() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xFFBEC2FF);
    }

    @Test
    public void darkTheme_maxContrast_primary() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xFFF6F4FF);
    }

    @Test
    public void darkTheme_minContrast_primaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF0000E6);
    }

    @Test
    public void darkTheme_standardContrast_primaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF0000E6);
    }

    @Test
    public void darkTheme_maxContrast_primaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFC4C6FF);
    }

    @Test
    public void darkTheme_minContrast_onPrimaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF7A83FF);
    }

    @Test
    public void darkTheme_standardContrast_onPrimaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFA4AAFF);
    }

    @Test
    public void darkTheme_maxContrast_onPrimaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF0001C6);
    }

    @Test
    public void darkTheme_minContrast_onTertiaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFCF60EA);
    }

    @Test
    public void darkTheme_standardContrast_onTertiaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFFEB8CFF);
    }

    @Test
    public void darkTheme_maxContrast_onTertiaryContainer() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF63007B);
    }

    @Test
    public void darkTheme_minContrast_surface() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xFF12121D);
    }

    @Test
    public void darkTheme_standardContrast_surface() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xFF12121D);
    }

    @Test
    public void darkTheme_maxContrast_surface() {
        SchemeContent scheme = new SchemeContent(Hct.fromInt(0xFF0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xFF12121D);
    }
}
