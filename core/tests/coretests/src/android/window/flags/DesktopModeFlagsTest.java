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

package android.window.flags;

import static android.window.flags.DesktopModeFlags.DESKTOP_WINDOWING_MODE;

import static com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE;
import static com.android.window.flags.Flags.FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS;
import static com.android.window.flags.Flags.FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.content.Context;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

/**
 * Test class for {@link DesktopModeFlags}
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:DesktopModeFlagsTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DesktopModeFlagsTest {

    @Rule
    public SetFlagsRule setFlagsRule = new SetFlagsRule();

    private Context mContext;

    private static final int OVERRIDE_OFF_SETTING = 0;
    private static final int OVERRIDE_ON_SETTING = 1;
    private static final int OVERRIDE_UNSET_SETTING = -1;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resetCache();
    }

    @Test
    @DisableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_devOptionFlagDisabled_overrideOff_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_OFF_SETTING);
        // In absence of dev options, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }


    @Test
    @DisableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_devOptionFlagDisabled_overrideOn_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_ON_SETTING);

        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_overrideUnset_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_UNSET_SETTING);

        // For overridableFlag, for unset overrides, follow flag
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_overrideUnset_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_UNSET_SETTING);

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
        setOverride(OVERRIDE_OFF_SETTING);

        // For overridableFlag, follow override if they exist
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_overrideOn_featureFlagOff_returnsTrue() {
        setOverride(OVERRIDE_ON_SETTING);

        // For overridableFlag, follow override if they exist
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isEnabled_overrideOffThenOn_featureFlagOn_returnsFalseAndFalse() {
        setOverride(OVERRIDE_OFF_SETTING);

        // For overridableFlag, follow override if they exist
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();

        setOverride(OVERRIDE_ON_SETTING);

        // Keep overrides constant through the process
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_overrideOnThenOff_featureFlagOff_returnsTrueAndTrue() {
        setOverride(OVERRIDE_ON_SETTING);

        // For overridableFlag, follow override if they exist
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();

        setOverride(OVERRIDE_OFF_SETTING);

        // Keep overrides constant through the process
        assertThat(DESKTOP_WINDOWING_MODE.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS})
    public void isEnabled_dwFlagOn_overrideUnset_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_UNSET_SETTING);

        // For unset overrides, follow flag
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    @DisableFlags(FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void isEnabled_dwFlagOn_overrideUnset_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_UNSET_SETTING);
        // For unset overrides, follow flag
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    })
    public void isEnabled_dwFlagOn_overrideOn_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_ON_SETTING);

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    @DisableFlags(FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void isEnabled_dwFlagOn_overrideOn_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_ON_SETTING);

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    })
    public void isEnabled_dwFlagOn_overrideOff_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_OFF_SETTING);

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags({FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION, FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    @DisableFlags(FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS)
    public void isEnabled_dwFlagOn_overrideOff_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_OFF_SETTING);

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    })
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_dwFlagOff_overrideUnset_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_UNSET_SETTING);

        // For unset overrides, follow flag
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags({
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    })
    public void isEnabled_dwFlagOff_overrideUnset_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_UNSET_SETTING);

        // For unset overrides, follow flag
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    })
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_dwFlagOff_overrideOn_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_ON_SETTING);

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags({
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    })
    public void isEnabled_dwFlagOff_overrideOn_featureFlagOff_returnFalse() {
        setOverride(OVERRIDE_ON_SETTING);

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isFalse();
    }

    @Test
    @EnableFlags({
            FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    })
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isEnabled_dwFlagOff_overrideOff_featureFlagOn_returnsTrue() {
        setOverride(OVERRIDE_OFF_SETTING);

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isTrue();
    }

    @Test
    @EnableFlags(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
    @DisableFlags({
            FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            FLAG_ENABLE_WINDOWING_DYNAMIC_INITIAL_BOUNDS
    })
    public void isEnabled_dwFlagOff_overrideOff_featureFlagOff_returnsFalse() {
        setOverride(OVERRIDE_OFF_SETTING);

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(DesktopModeFlags.DYNAMIC_INITIAL_BOUNDS.isEnabled(mContext)).isFalse();
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
        Field cachedToggleOverride = DesktopModeFlags.class.getDeclaredField(
                "sCachedToggleOverride");
        cachedToggleOverride.setAccessible(true);
        cachedToggleOverride.set(null, null);
    }
}
