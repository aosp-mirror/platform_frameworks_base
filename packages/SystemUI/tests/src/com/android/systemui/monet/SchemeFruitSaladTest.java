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
import com.android.systemui.monet.scheme.SchemeFruitSalad;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
public final class SchemeFruitSaladTest extends SysuiTestCase {

    @Test
    public void testKeyColors() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 0.0);

        assertThat(MaterialDynamicColors.primaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff0091C0);
        assertThat(MaterialDynamicColors.secondaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff3A7E9E);
        assertThat(MaterialDynamicColors.tertiaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff6E72AC);
        assertThat(MaterialDynamicColors.neutralPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff777682);
        assertThat(MaterialDynamicColors.neutralVariantPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff75758B);
    }

    @Test
    public void lightTheme_minContrast_primary() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff007ea7);
    }

    @Test
    public void lightTheme_standardContrast_primary() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff006688);
    }

    @Test
    public void lightTheme_maxContrast_primary() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff002635);
    }

    @Test
    public void lightTheme_minContrast_primaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffC2E8FF);
    }

    @Test
    public void lightTheme_standardContrast_primaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffC2E8FF);
    }

    @Test
    public void lightTheme_maxContrast_primaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff004862);
    }

    @Test
    public void lightTheme_minContrast_tertiaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xffE0E0FF);
    }

    @Test
    public void lightTheme_standardContrast_tertiaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xffE0E0FF);
    }

    @Test
    public void lightTheme_maxContrast_tertiaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.tertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF3A3E74);
    }

    @Test
    public void lightTheme_minContrast_onPrimaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff006C90);
    }

    @Test
    public void lightTheme_standardContrast_onPrimaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff001E2B);
    }

    @Test
    public void lightTheme_maxContrast_onPrimaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffACE1FF);
    }

    @Test
    public void lightTheme_minContrast_surface() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void lightTheme_standardContrast_surface() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void lightTheme_maxContrast_surface() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffbf8ff);
    }

    @Test
    public void darkTheme_minContrast_primary() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff007EA7);
    }

    @Test
    public void darkTheme_standardContrast_primary() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xFF76D1FF);
    }

    @Test
    public void darkTheme_maxContrast_primary() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xFFECF7FF);
    }

    @Test
    public void darkTheme_minContrast_primaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF004D67);
    }

    @Test
    public void darkTheme_standardContrast_primaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF004D67);
    }

    @Test
    public void darkTheme_maxContrast_primaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xFF83D5FF);
    }

    @Test
    public void darkTheme_minContrast_onPrimaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff55C0F2);
    }

    @Test
    public void darkTheme_standardContrast_onPrimaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffC2E8FF);
    }

    @Test
    public void darkTheme_maxContrast_onPrimaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff003E54);
    }

    @Test
    public void darkTheme_minContrast_onTertiaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xffADB0EF);
    }

    @Test
    public void darkTheme_standardContrast_onTertiaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xffe0e0ff);
    }

    @Test
    public void darkTheme_maxContrast_onTertiaryContainer() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onTertiaryContainer.getArgb(scheme)).isSameColorAs(
                0xff30346A);
    }

    @Test
    public void darkTheme_minContrast_surface() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff12131c);
    }

    @Test
    public void darkTheme_standardContrast_surface() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff12131c);
    }

    @Test
    public void darkTheme_maxContrast_surface() {
        SchemeFruitSalad scheme = new SchemeFruitSalad(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff12131c);
    }

}
