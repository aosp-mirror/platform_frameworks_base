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

package com.android.server.wm.utils;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.utils.DesktopModeFlagsUtil.DESKTOP_WINDOWING_MODE;
import static com.android.server.wm.utils.DesktopModeFlagsUtil.ToggleOverride.OVERRIDE_OFF;
import static com.android.server.wm.utils.DesktopModeFlagsUtil.ToggleOverride.OVERRIDE_ON;
import static com.android.server.wm.utils.DesktopModeFlagsUtil.ToggleOverride.OVERRIDE_UNSET;
import static com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE;
import static com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY;
import static com.android.window.flags.Flags.FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import android.content.ContentResolver;
import android.os.SystemProperties;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import com.android.server.wm.WindowTestRunner;
import com.android.server.wm.WindowTestsBase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

/**
 * Test class for [DesktopModeFlagsUtil]
 *
 * Build/Install/Run:
 * atest WmTests:DesktopModeFlagsUtilTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DesktopModeFlagsUtilTest extends WindowTestsBase {

    @Rule
    public SetFlagsRule setFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        resetCache();
    }

    private static final String SYSTEM_PROPERTY_OVERRIDE_KEY =
            "sys.wmshell.desktopmode.dev_toggle_override";

    @Test
    @DisableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_devOptionFlagDisabled_overrideOff_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_OFF.getSetting());

        // In absence of dev options, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }


    @Test
    @DisableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_devOptionFlagDisabled_overrideOn_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_ON.getSetting());

        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_overrideUnset_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_UNSET.getSetting());

        // For overridableFlag, for unset overrides, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_overrideUnset_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_UNSET.getSetting());

        // For overridableFlag, for unset overrides, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_noOverride_featureFlagOn_returnsTrue() {
        setOverride(null);

        // For overridableFlag, in absence of overrides, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_noOverride_featureFlagOff_returnsFalse() {
        setOverride(null);

        // For overridableFlag, in absence of overrides, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_unrecognizableOverride_featureFlagOn_returnsTrue() {
        setOverride(-2);

        // For overridableFlag, for unrecognized overrides, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_unrecognizableOverride_featureFlagOff_returnsFalse() {
        setOverride(-2);

        // For overridableFlag, for unrecognizable overrides, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_overrideOff_featureFlagOn_returnsFalse() {
        setOverride(OVERRIDE_OFF.getSetting());

        // For overridableFlag, follow override if they exist
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_overrideOn_featureFlagOff_returnsTrue() {
        setOverride(OVERRIDE_ON.getSetting());

        // For overridableFlag, follow override if they exist
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_overrideOffThenOn_featureFlagOn_returnsFalseAndFalse() {
        setOverride(OVERRIDE_OFF.getSetting());

        // For overridableFlag, follow override if they exist
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();

        setOverride(OVERRIDE_ON.getSetting());

        // Keep overrides constant through the process
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_overrideOnThenOff_featureFlagOff_returnsTrueAndTrue() {
        setOverride(OVERRIDE_ON.getSetting());

        // For overridableFlag, follow override if they exist
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();

        setOverride(OVERRIDE_OFF.getSetting());

        // Keep overrides constant through the process
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_noProperty_overrideOn_featureFlagOff_returnsTrueAndPropertyOn() {
        setSystemProperty(-2);
        setOverride(OVERRIDE_ON.getSetting());

        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
        // Store System Property if not present
        verifySystemPropertySet(OVERRIDE_ON.getSetting());
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_noProperty_overrideUnset_featureFlagOn_returnsTrueAndPropertyUnset() {
        setSystemProperty(-2);
        setOverride(OVERRIDE_UNSET.getSetting());

        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
        // Store System Property if not present
        verifySystemPropertySet(OVERRIDE_UNSET.getSetting());
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_noProperty_overrideUnset_featureFlagOff_returnsFalseAndPropertyUnset() {
        setSystemProperty(-2);
        setOverride(OVERRIDE_UNSET.getSetting());

        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
        // Store System Property if not present
        verifySystemPropertySet(OVERRIDE_UNSET.getSetting());
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_propertyInvalid_overrideOff_featureFlagOn_returnsFalseAndPropertyOff() {
        setSystemProperty(-3);
        setOverride(OVERRIDE_OFF.getSetting());

        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
        // Store System Property if currently invalid
        verifySystemPropertySet(OVERRIDE_OFF.getSetting());
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_propertyOff_overrideOn_featureFlagOn_returnsFalseAndNoPropertyUpdate() {
        setSystemProperty(OVERRIDE_OFF.getSetting());
        setOverride(OVERRIDE_ON.getSetting());

        // Have a consistent override until reboot
        verifySystemPropertyNotUpdated();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_propertyOn_overrideOff_featureFlagOff_returnsTrueAndNoPropertyUpdate() {
        setSystemProperty(OVERRIDE_ON.getSetting());
        setOverride(OVERRIDE_OFF.getSetting());

        // Have a consistent override until reboot
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
        verifySystemPropertyNotUpdated();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_propertyUnset_overrideOff_featureFlagOn_returnsTrueAndNoPropertyUpdate() {
        setSystemProperty(OVERRIDE_UNSET.getSetting());
        setOverride(OVERRIDE_OFF.getSetting());

        // Have a consistent override until reboot
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
        verifySystemPropertyNotUpdated();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY})
    public void isEnabled_dwFlagOn_overrideUnset_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_UNSET.getSetting());

        // For unset overrides, follow flag
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
    public void isEnabled_dwFlagOn_overrideUnset_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_UNSET.getSetting());
        // For unset overrides, follow flag
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
    })
    public void isEnabled_dwFlagOn_overrideOn_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_ON.getSetting());

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
    public void isEnabled_dwFlagOn_overrideOn_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_ON.getSetting());

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
    })
    public void isEnabled_dwFlagOn_overrideOff_featureFlagOn_returnsFalse() {
        setOverride(OVERRIDE_OFF.getSetting());

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY)
    public void isEnabled_dwFlagOn_overrideOff_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_OFF.getSetting());

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
    })
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_dwFlagOff_overrideUnset_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_UNSET.getSetting());

        // For unset overrides, follow flag
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags({
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
    })
    public void isEnabled_dwFlagOff_overrideUnset_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_UNSET.getSetting());

        // For unset overrides, follow flag
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
    })
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_dwFlagOff_overrideOn_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_ON.getSetting());

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags({
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
    })
    public void isEnabled_dwFlagOff_overrideOn_featureFlagOff_returnTrue() {
        setOverride(OVERRIDE_ON.getSetting());

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
    })
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_dwFlagOff_overrideOff_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_OFF.getSetting());

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags({
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY
    })
    public void isEnabled_dwFlagOff_overrideOff_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_OFF.getSetting());

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(DesktopModeFlagsUtil.WALLPAPER_ACTIVITY.isEnabled(mContext)).isFalse();
    }

    private void setOverride(Integer setting) {
        ContentResolver contentResolver = mContext.getContentResolver();
        String key = Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES;

        if (setting == null) {
            Settings.Global.putString(contentResolver, key, null);
        } else {
            Settings.Global.putInt(contentResolver, key, setting);
        }
    }

    private void resetCache() throws Exception {
        Field cachedToggleOverride = DesktopModeFlagsUtil.class.getDeclaredField(
                "sCachedToggleOverride");
        cachedToggleOverride.setAccessible(true);
        cachedToggleOverride.set(null, null);

        // Clear override cache stored in System property
        setSystemProperty(-2);
    }

    private void setSystemProperty(int systemProperty) {
        doReturn(systemProperty).when(
                () -> SystemProperties.getInt(eq(SYSTEM_PROPERTY_OVERRIDE_KEY), anyInt()));
    }

    private void verifySystemPropertySet(int systemProperty) {
        verify(() ->
                SystemProperties.set(eq(SYSTEM_PROPERTY_OVERRIDE_KEY),
                        eq(String.valueOf(systemProperty))));
    }

    private void verifySystemPropertyNotUpdated() {
        verify(() -> SystemProperties.set(eq(SYSTEM_PROPERTY_OVERRIDE_KEY), anyString()), never());
    }
}
