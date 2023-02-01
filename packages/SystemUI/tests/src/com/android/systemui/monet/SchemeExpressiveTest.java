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
import com.android.systemui.monet.scheme.SchemeExpressive;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
public final class SchemeExpressiveTest extends SysuiTestCase {

    @Test
    public void testKeyColors() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);

        assertThat(MaterialDynamicColors.primaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff35855F);
        assertThat(MaterialDynamicColors.secondaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff8C6D8C);
        assertThat(MaterialDynamicColors.tertiaryPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff806EA1);
        assertThat(MaterialDynamicColors.neutralPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff79757F);
        assertThat(MaterialDynamicColors.neutralVariantPaletteKeyColor.getArgb(scheme))
                .isSameColorAs(0xff7A7585);
    }

    @Test
    public void lightTheme_minContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff32835D);
    }

    @Test
    public void lightTheme_standardContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff146C48);
    }

    @Test
    public void lightTheme_maxContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff002818);
    }

    @Test
    public void lightTheme_minContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffA2F4C6);
    }

    @Test
    public void lightTheme_standardContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xffA2F4C6);
    }

    @Test
    public void lightTheme_maxContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff004D31);
    }

    @Test
    public void lightTheme_minContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff1e724e);
    }

    @Test
    public void lightTheme_standardContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff002112);
    }

    @Test
    public void lightTheme_maxContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff9aebbe);
    }

    @Test
    public void lightTheme_minContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffdf7ff);
    }

    @Test
    public void lightTheme_standardContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffdf7ff);
    }

    @Test
    public void lightTheme_maxContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), false, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xfffdf7ff);
    }

    @Test
    public void darkTheme_minContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff32835d);
    }

    @Test
    public void darkTheme_standardContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xff87d7ab);
    }

    @Test
    public void darkTheme_maxContrast_primary() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primary.getArgb(scheme)).isSameColorAs(0xffd5ffe4);
    }

    @Test
    public void darkTheme_minContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff005234);
    }

    @Test
    public void darkTheme_standardContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff005234);
    }

    @Test
    public void darkTheme_maxContrast_primaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.primaryContainer.getArgb(scheme)).isSameColorAs(
                0xff8bdbaf);
    }

    @Test
    public void darkTheme_minContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff76c59b);
    }

    @Test
    public void darkTheme_standardContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xffa2f4c6);
    }

    @Test
    public void darkTheme_maxContrast_onPrimaryContainer() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.onPrimaryContainer.getArgb(scheme)).isSameColorAs(
                0xff004229);
    }

    @Test
    public void darkTheme_minContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, -1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff14121a);
    }

    @Test
    public void darkTheme_standardContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 0.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff14121a);
    }

    @Test
    public void darkTheme_maxContrast_surface() {
        SchemeExpressive scheme = new SchemeExpressive(Hct.fromInt(0xff0000ff), true, 1.0);
        assertThat(MaterialDynamicColors.surface.getArgb(scheme)).isSameColorAs(0xff14121a);
    }
}
