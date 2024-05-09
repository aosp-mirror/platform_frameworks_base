/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.theme;

import com.google.ux.material.libmonet.dynamiccolor.ContrastCurve;
import com.google.ux.material.libmonet.dynamiccolor.DynamicColor;
import com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors;
import com.google.ux.material.libmonet.dynamiccolor.ToneDeltaPair;
import com.google.ux.material.libmonet.dynamiccolor.TonePolarity;

class CustomDynamicColors {
    private final MaterialDynamicColors mMdc;

    CustomDynamicColors(boolean isExtendedFidelity) {
        this.mMdc = new MaterialDynamicColors(isExtendedFidelity);
    }

    // CLOCK COLORS

    public DynamicColor widgetBackground() {
        return new DynamicColor(
                /* name= */ "widget_background",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> s.isDark ? 20.0 : 95.0,
                /* isBackground= */ true,
                /* background= */ null,
                /* secondBackground= */ null,
                /* contrastCurve= */ null,
                /* toneDeltaPair= */ null);
    }

    public DynamicColor clockHour() {
        return new DynamicColor(
                /* name= */ "clock_hour",
                /* palette= */ (s) -> s.secondaryPalette,
                /* tone= */ (s) -> s.isDark ? 30.0 : 60.0,
                /* isBackground= */ false,
                /* background= */ (s) -> widgetBackground(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 4.0, 5.0, 15.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(clockHour(), clockMinute(), 10.0, TonePolarity.DARKER,
                        false));
    }

    public DynamicColor clockMinute() {
        return new DynamicColor(
                /* name= */ "clock_minute",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> s.isDark ? 40.0 : 90.0,
                /* isBackground= */ false,
                /* background= */ (s) -> widgetBackground(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 6.5, 10.0, 15.0),
                /* toneDeltaPair= */ null);
    }

    public DynamicColor clockSecond() {
        return new DynamicColor(
                /* name= */ "clock_second",
                /* palette= */ (s) -> s.tertiaryPalette,
                /* tone= */ (s) -> s.isDark ? 40.0 : 90.0,
                /* isBackground= */ false,
                /* background= */ (s) -> widgetBackground(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 5.0, 70.0, 11.0),
                /* toneDeltaPair= */ null);
    }

    public DynamicColor weatherTemp() {
        return new DynamicColor(
                /* name= */ "clock_second",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> s.isDark ? 55.0 : 80.0,
                /* isBackground= */ false,
                /* background= */ (s) -> widgetBackground(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 5.0, 70.0, 11.0),
                /* toneDeltaPair= */ null);
    }

    // THEME APP ICONS

    public DynamicColor themeApp() {
        return new DynamicColor(
                /* name= */ "theme_app",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> s.isDark ? 90.0 : 30.0, // Adjusted values
                /* isBackground= */ true,
                /* background= */ null,
                /* secondBackground= */ null,
                /* contrastCurve= */ null,
                /* toneDeltaPair= */ null);
    }

    public DynamicColor onThemeApp() {
        return new DynamicColor(
                /* name= */ "on_theme_app",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> s.isDark ? 40.0 : 80.0, // Adjusted values
                /* isBackground= */ false,
                /* background= */ (s) -> themeApp(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 3.0, 7.0, 10.0),
                /* toneDeltaPair= */ null);
    }

    public DynamicColor themeAppRing() {
        return new DynamicColor(
                /* name= */ "theme_app_ring",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> 70.0,
                /* isBackground= */ true,
                /* background= */ null,
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 1.0, 1.0, 1.0),
                /* toneDeltaPair= */ null);
    }

    public DynamicColor themeNotif() {
        return new DynamicColor(
                /* name= */ "theme_notif",
                /* palette= */ (s) -> s.tertiaryPalette,
                /* tone= */ (s) -> s.isDark ? 80.0 : 90.0,
                /* isBackground= */ false,
                /* background= */ (s) -> themeAppRing(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 1.0, 1.0, 1.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(themeNotif(), themeAppRing(), 10.0, TonePolarity.NEARER,
                        false));
    }

    // SUPER G COLORS

    public DynamicColor brandA() {
        return new DynamicColor(
                /* name= */ "brand_a",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> s.isDark ? 40.0 : 80.0,
                /* isBackground= */ true,
                /* background= */ (s) -> mMdc.surfaceContainerLow(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 3.0, 7.0, 17.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(brandA(), brandB(), 10.0, TonePolarity.NEARER, false));
    }

    public DynamicColor brandB() {
        return new DynamicColor(
                /* name= */ "brand_b",
                /* palette= */ (s) -> s.secondaryPalette,
                /* tone= */ (s) -> s.isDark ? 70.0 : 98.0,
                /* isBackground= */ true,
                /* background= */ (s) -> mMdc.surfaceContainerLow(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 3.0, 3.0, 6.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(brandB(), brandC(), 10.0, TonePolarity.NEARER, false));
    }

    public DynamicColor brandC() {
        return new DynamicColor(
                /* name= */ "brand_c",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> s.isDark ? 50.0 : 60.0,
                /* isBackground= */ false,
                /* background= */ (s) -> mMdc.surfaceContainerLow(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 3.0, 4.0, 9.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(brandC(), brandD(), 10.0, TonePolarity.NEARER, false));
    }

    public DynamicColor brandD() {
        return new DynamicColor(
                /* name= */ "brand_d",
                /* palette= */ (s) -> s.tertiaryPalette,
                /* tone= */ (s) -> s.isDark ? 59.0 : 90.0,
                /* isBackground= */ false,
                /* background= */ (s) -> mMdc.surfaceContainerLow(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 3.0, 4.0, 13.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(brandD(), brandA(), 10.0, TonePolarity.NEARER, false));
    }

    // QUICK SETTING TIILES

    public DynamicColor underSurface() {
        return new DynamicColor(
                /* name= */ "under_surface",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> 0.0,
                /* isBackground= */ true,
                /* background= */ null,
                /* secondBackground= */ null,
                /* contrastCurve= */ null,
                /* toneDeltaPair= */ null);
    }

    public DynamicColor shadeActive() {
        return new DynamicColor(
                /* name= */ "shade_active",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> 90.0,
                /* isBackground= */ false,
                /* background= */ (s) -> underSurface(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 3.0, 4.5, 7.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(shadeActive(), shadeInactive(), 30.0, TonePolarity.LIGHTER,
                        false));
    }

    public DynamicColor onShadeActive() {
        return new DynamicColor(
                /* name= */ "on_shade_active",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> 10.0,
                /* isBackground= */ false,
                /* background= */ (s) -> shadeActive(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 4.5, 7.0, 11.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(onShadeActive(), onShadeActiveVariant(), 20.0,
                        TonePolarity.NEARER, false));
    }

    public DynamicColor onShadeActiveVariant() {
        return new DynamicColor(
                /* name= */ "on_shade_active_variant",
                /* palette= */ (s) -> s.primaryPalette,
                /* tone= */ (s) -> 30.0,
                /* isBackground= */ false,
                /* background= */ (s) -> shadeActive(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 4.5, 7.0, 11.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(onShadeActiveVariant(), onShadeActive(), 20.0,
                        TonePolarity.NEARER, false));
    }

    public DynamicColor shadeInactive() {
        return new DynamicColor(
                /* name= */ "shade_inactive",
                /* palette= */ (s) -> s.neutralPalette,
                /* tone= */ (s) -> 20.0,
                /* isBackground= */ true,
                /* background= */ (s) -> underSurface(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 1.0, 1.0, 1.0),
                /* toneDeltaPair= */(s) -> new ToneDeltaPair(shadeInactive(), shadeDisabled(), 15.0,
                TonePolarity.LIGHTER, false));
    }

    public DynamicColor onShadeInactive() {
        return new DynamicColor(
                /* name= */ "on_shade_inactive",
                /* palette= */ (s) -> s.neutralVariantPalette,
                /* tone= */ (s) -> 90.0,
                /* isBackground= */ true,
                /* background= */ (s) -> shadeInactive(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 4.5, 7.0, 11.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(onShadeInactive(), onShadeInactiveVariant(), 10.0,
                        TonePolarity.NEARER, false));
    }

    public DynamicColor onShadeInactiveVariant() {
        return new DynamicColor(
                /* name= */ "on_shade_inactive_variant",
                /* palette= */ (s) -> s.neutralVariantPalette,
                /* tone= */ (s) -> 80.0,
                /* isBackground= */ false,
                /* background= */ (s) -> shadeInactive(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 4.5, 7.0, 11.0),
                /* toneDeltaPair= */
                (s) -> new ToneDeltaPair(onShadeInactiveVariant(), onShadeInactive(), 10.0,
                        TonePolarity.NEARER, false));
    }

    public DynamicColor shadeDisabled() {
        return new DynamicColor(
                /* name= */ "shade_disabled",
                /* palette= */ (s) -> s.neutralPalette,
                /* tone= */ (s) -> 4.0,
                /* isBackground= */ false,
                /* background= */ (s) -> underSurface(),
                /* secondBackground= */ null,
                /* contrastCurve= */ new ContrastCurve(1.0, 1.0, 1.0, 1.0),
                /* toneDeltaPair= */ null);
    }

    public DynamicColor overviewBackground() {
        return new DynamicColor(
                /* name= */ "overview_background",
                /* palette= */ (s) -> s.neutralVariantPalette,
                /* tone= */ (s) -> s.isDark ? 80.0 : 35.0,
                /* isBackground= */ true,
                /* background= */ null,
                /* secondBackground= */ null,
                /* contrastCurve= */null,
                /* toneDeltaPair= */ null);
    }
}
