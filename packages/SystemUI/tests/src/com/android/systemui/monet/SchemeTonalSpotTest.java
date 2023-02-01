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
import com.android.systemui.monet.scheme.SchemeTonalSpot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
public final class SchemeTonalSpotTest extends SysuiTestCase {

    @Test
    public void testKeyColors() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);

        assertThat(MaterialDynamicColors.primaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff6E72AC);
        assertThat(MaterialDynamicColors.secondaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff75758B);
        assertThat(MaterialDynamicColors.tertiaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff936B84);
        assertThat(MaterialDynamicColors.neutralPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff78767A);
        assertThat(MaterialDynamicColors.neutralVariantPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff777680);
    }

    @Test
    public void lightTheme_minContrast_primary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff6c70aa);
    }

    @Test
    public void lightTheme_standardContrast_primary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff555992);
    }

    @Test
    public void lightTheme_maxContrast_primary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff181c51);
    }

    @Test
    public void lightTheme_minContrast_primaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffe0e0ff);
    }

    @Test
    public void lightTheme_standardContrast_primaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffe0e0ff);
    }

    @Test
    public void lightTheme_maxContrast_primaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff3a3e74);
    }

    @Test
    public void lightTheme_minContrast_onPrimaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff5C5F98);
    }

    @Test
    public void lightTheme_standardContrast_onPrimaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff11144B);
    }

    @Test
    public void lightTheme_maxContrast_onPrimaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffd6d6ff);
    }

    @Test
    public void lightTheme_minContrast_surface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xffFCF8FD);
    }

    @Test
    public void lightTheme_standardContrast_surface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xffFCF8FD);
    }

    @Test
    public void lightTheme_maxContrast_surface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xffFCF8FD);
    }

    @Test
    public void lightTheme_minContrast_onSurface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onSurface.getArgb(scheme)).isSameColorAs(0xff605E62);
    }

    @Test
    public void lightTheme_standardContrast_onSurface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onSurface.getArgb(scheme)).isSameColorAs(0xff1B1B1F);
    }

    @Test
    public void lightTheme_maxContrast_onSurface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onSurface.getArgb(scheme)).isSameColorAs(0xff1B1A1E);
    }

    @Test
    public void lightTheme_minContrast_onSecondary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onSecondary.getArgb(scheme)).isSameColorAs(0xffcfcfe7);
    }

    @Test
    public void lightTheme_standardContrast_onSecondary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onSecondary.getArgb(scheme)).isSameColorAs(0xffffffff);
    }

    @Test
    public void lightTheme_maxContrast_onSecondary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onSecondary.getArgb(scheme)).isSameColorAs(0xffababc3);
    }

    @Test
    public void lightTheme_minContrast_onTertiary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onTertiary.getArgb(scheme)).isSameColorAs(0xfff3c3df);
    }

    @Test
    public void lightTheme_standardContrast_onTertiary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onTertiary.getArgb(scheme)).isSameColorAs(0xffffffff);
    }

    @Test
    public void lightTheme_maxContrast_onTertiary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onTertiary.getArgb(scheme)).isSameColorAs(0xffcda0bb);
    }

    @Test
    public void lightTheme_minContrast_onError() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onError.getArgb(scheme)).isSameColorAs(0xffffc2bb);
    }

    @Test
    public void lightTheme_standardContrast_onError() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onError.getArgb(scheme)).isSameColorAs(0xffffffff);
    }

    @Test
    public void lightTheme_maxContrast_onError() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onError.getArgb(scheme)).isSameColorAs(0xffff8d80);
    }

    @Test
    public void darkTheme_minContrast_primary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff6C70AA);
    }

    @Test
    public void darkTheme_standardContrast_primary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xffbec2ff);
    }

    @Test
    public void darkTheme_maxContrast_primary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xfff6f4ff);
    }

    @Test
    public void darkTheme_minContrast_primaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff3E4278);
    }

    @Test
    public void darkTheme_standardContrast_primaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff3E4278);
    }

    @Test
    public void darkTheme_maxContrast_primaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffc4c6ff);
    }

    @Test
    public void darkTheme_minContrast_onPrimaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffadb0ef);
    }

    @Test
    public void darkTheme_standardContrast_onPrimaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffe0e0ff);
    }

    @Test
    public void darkTheme_maxContrast_onPrimaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff30346A);
    }

    @Test
    public void darkTheme_minContrast_onTertiaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xffd5a8c3);
    }

    @Test
    public void darkTheme_standardContrast_onTertiaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xffffd8ee);
    }

    @Test
    public void darkTheme_maxContrast_onTertiaryContainer() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xff4f2e44);
    }

    @Test
    public void darkTheme_minContrast_onSecondary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onSecondary.getArgb(scheme)).isSameColorAs(0xfffffbff);
    }

    @Test
    public void darkTheme_standardContrast_onSecondary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onSecondary.getArgb(scheme)).isSameColorAs(0xff2e2f42);
    }

    @Test
    public void darkTheme_maxContrast_onSecondary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onSecondary.getArgb(scheme)).isSameColorAs(0xff505165);
    }

    @Test
    public void darkTheme_minContrast_onTertiary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onTertiary.getArgb(scheme)).isSameColorAs(0xfffffbff);
    }

    @Test
    public void darkTheme_standardContrast_onTertiary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onTertiary.getArgb(scheme)).isSameColorAs(0xff46263b);
    }

    @Test
    public void darkTheme_maxContrast_onTertiary() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onTertiary.getArgb(scheme)).isSameColorAs(0xff6b485f);
    }

    @Test
    public void darkTheme_minContrast_onError() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onError.getArgb(scheme)).isSameColorAs(0xfffffbff);
    }

    @Test
    public void darkTheme_standardContrast_onError() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onError.getArgb(scheme)).isSameColorAs(0xff690005);
    }

    @Test
    public void darkTheme_maxContrast_onError() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onError.getArgb(scheme)).isSameColorAs(0xffa80710);
    }

    @Test
    public void darkTheme_minContrast_surface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff131316);
    }

    @Test
    public void darkTheme_standardContrast_surface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff131316);
    }

    @Test
    public void darkTheme_maxContrast_surface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff131316);
    }

    @Test
    public void darkTheme_minContrast_onSurface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onSurface.getArgb(scheme)).isSameColorAs(0xffa4a2a6);
    }

    @Test
    public void darkTheme_standardContrast_onSurface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onSurface.getArgb(scheme)).isSameColorAs(0xffe5e1e6);
    }

    @Test
    public void darkTheme_maxContrast_onSurface() {
        SchemeTonalSpot scheme = new SchemeTonalSpot(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onSurface.getArgb(scheme)).isSameColorAs(0xffe6e2e7);
    }
}
