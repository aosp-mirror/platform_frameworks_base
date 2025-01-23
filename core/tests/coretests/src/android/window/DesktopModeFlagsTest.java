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

package android.window;

import static android.window.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_MODE;
import static android.window.DesktopModeFlags.ToggleOverride.OVERRIDE_OFF;
import static android.window.DesktopModeFlags.ToggleOverride.OVERRIDE_ON;
import static android.window.DesktopModeFlags.ToggleOverride.OVERRIDE_UNSET;
import static android.window.DesktopModeFlags.ToggleOverride.fromSetting;

import static com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE;
import static com.android.window.flags.Flags.FLAG_SHOW_DESKTOP_EXPERIENCE_DEV_OPTION;
import static com.android.window.flags.Flags.FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.support.test.uiautomator.UiDevice;
import android.window.DesktopModeFlags.DesktopModeFlag;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.window.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Test class for {@link android.window.DesktopModeFlags}
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:DesktopModeFlagsTest
 */
@SmallTest
@Presubmit
@RunWith(ParameterizedAndroidJunit4.class)
public class DesktopModeFlagsTest {

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf(FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION,
                FLAG_SHOW_DESKTOP_EXPERIENCE_DEV_OPTION);
    }

    @Rule
    public SetFlagsRule mSetFlagsRule;

    private UiDevice mUiDevice;
    private Context mContext;
    private boolean mLocalFlagValue = false;
    private final DesktopModeFlag mOverriddenLocalFlag = new DesktopModeFlag(
            () -> mLocalFlagValue, true);
    private final DesktopModeFlag mNotOverriddenLocalFlag = new DesktopModeFlag(
            () -> mLocalFlagValue, false);

    private static final int OVERRIDE_OFF_SETTING = 0;
    private static final int OVERRIDE_ON_SETTING = 1;
    private static final int OVERRIDE_UNSET_SETTING = -1;

    public DesktopModeFlagsTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(flags);
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        setOverride(null);
    }

    @After
    public void tearDown() throws Exception {
        resetCache();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_overrideOff_featureFlagOn() throws Exception {
        setOverride(OVERRIDE_OFF_SETTING);

        if (showDesktopWindowingDevOpts()) {
            // DW Dev Opts turns off flags when ON
            assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isFalse();
        } else {
            // DE Dev Opts doesn't turn flags OFF
            assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isTrue();
        }
    }


    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_overrideOn_featureFlagOff() throws Exception {
        setOverride(OVERRIDE_ON_SETTING);

        if (showAnyDevOpts()) {
            assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isTrue();
        } else {
            assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isFalse();
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_overrideUnset_featureFlagOn() throws Exception {
        setOverride(OVERRIDE_UNSET_SETTING);

        // For overridableFlag, for unset overrides, follow flag
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_overrideUnset_featureFlagOff() throws Exception {
        setOverride(OVERRIDE_UNSET_SETTING);

        // For overridableFlag, for unset overrides, follow flag
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isFalse();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_noOverride_featureFlagOn_returnsTrue() throws Exception {
        setOverride(null);

        // For overridableFlag, in absence of overrides, follow flag
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_noOverride_featureFlagOff_returnsFalse() throws Exception {
        setOverride(null);

        // For overridableFlag, in absence of overrides, follow flag
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isFalse();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_unrecognizableOverride_featureFlagOn_returnsTrue() throws Exception {
        setOverride(-2);

        // For overridableFlag, for unrecognized overrides, follow flag
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_unrecognizableOverride_featureFlagOff_returnsFalse() throws Exception {
        setOverride(-2);

        // For overridableFlag, for unrecognizable overrides, follow flag
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isFalse();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_overrideOffThenOn_featureFlagOn_returnsFalseAndFalse() throws Exception {
        assumeTrue(showDesktopWindowingDevOpts());

        setOverride(OVERRIDE_OFF_SETTING);

        // For overridableFlag, follow override if they exist
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isFalse();

        setOverride(OVERRIDE_ON_SETTING);

        // Keep overrides constant through the process
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_overrideOnThenOff_featureFlagOff_returnsTrueAndTrue() throws Exception {
        assumeTrue(showAnyDevOpts());
        setOverride(OVERRIDE_ON_SETTING);

        // For overridableFlag, follow override if they exist
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isTrue();

        setOverride(OVERRIDE_OFF_SETTING);

        // Keep overrides constant through the process
        assertThat(ENABLE_DESKTOP_WINDOWING_MODE.isTrue()).isTrue();
    }

    @Test
    @EnableFlags({FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isTrue_dwFlagOn_overrideUnset_featureFlagOn() throws Exception {
        mLocalFlagValue = true;
        setOverride(OVERRIDE_UNSET_SETTING);

        // For unset overrides, follow flag
        assertThat(mOverriddenLocalFlag.isTrue()).isTrue();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isTrue();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOn_overrideUnset_featureFlagOff() throws Exception {
        mLocalFlagValue = false;
        setOverride(OVERRIDE_UNSET_SETTING);
        // For unset overrides, follow flag
        assertThat(mOverriddenLocalFlag.isTrue()).isFalse();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isFalse();
    }

    @Test
    @EnableFlags({FLAG_ENABLE_DESKTOP_WINDOWING_MODE})
    public void isTrue_dwFlagOn_overrideOn_featureFlagOn() throws Exception {
        mLocalFlagValue = true;
        setOverride(OVERRIDE_ON_SETTING);

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(mOverriddenLocalFlag.isTrue()).isTrue();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isTrue();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOn_overrideOn_featureFlagOff() throws Exception {
        mLocalFlagValue = false;
        setOverride(OVERRIDE_ON_SETTING);

        if (showDesktopExperienceDevOpts()) {
            assertThat(mOverriddenLocalFlag.isTrue()).isTrue();
        } else {
            assertThat(mOverriddenLocalFlag.isTrue()).isFalse();
        }
        assertThat(mNotOverriddenLocalFlag.isTrue()).isFalse();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOn_overrideOff_featureFlagOn() throws Exception {
        mLocalFlagValue = true;
        setOverride(OVERRIDE_OFF_SETTING);

        if (showDesktopWindowingDevOpts()) {
            // Follow override if they exist, and is not equal to default toggle state (dw flag)
            assertThat(mOverriddenLocalFlag.isTrue()).isFalse();
        } else {
            assertThat(mOverriddenLocalFlag.isTrue()).isTrue();
        }
        assertThat(mNotOverriddenLocalFlag.isTrue()).isTrue();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOn_overrideOff_featureFlagOff_returnsFalse() throws Exception {
        mLocalFlagValue = false;
        setOverride(OVERRIDE_OFF_SETTING);

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(mOverriddenLocalFlag.isTrue()).isFalse();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOff_overrideUnset_featureFlagOn_returnsTrue() throws Exception {
        mLocalFlagValue = true;
        setOverride(OVERRIDE_UNSET_SETTING);

        // For unset overrides, follow flag
        assertThat(mOverriddenLocalFlag.isTrue()).isTrue();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOff_overrideUnset_featureFlagOff_returnsFalse() throws Exception {
        mLocalFlagValue = false;
        setOverride(OVERRIDE_UNSET_SETTING);

        // For unset overrides, follow flag
        assertThat(mOverriddenLocalFlag.isTrue()).isFalse();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOff_overrideOn_featureFlagOn_returnsTrue() throws Exception {
        mLocalFlagValue = true;
        setOverride(OVERRIDE_ON_SETTING);

        // Follow override if they exist, and is not equal to default toggle state (dw flag)
        assertThat(mOverriddenLocalFlag.isTrue()).isTrue();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOff_overrideOn_featureFlagOff() throws Exception {
        mLocalFlagValue = false;
        setOverride(OVERRIDE_ON_SETTING);

        if (showAnyDevOpts()) {
            assertThat(mOverriddenLocalFlag.isTrue()).isTrue();
        } else {
            // Follow override if they exist, and is not equal to default toggle state (dw flag)
            assertThat(mOverriddenLocalFlag.isTrue()).isFalse();
        }
        assertThat(mNotOverriddenLocalFlag.isTrue()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOff_overrideOff_featureFlagOn_returnsTrue() throws Exception {
        mLocalFlagValue = true;
        setOverride(OVERRIDE_OFF_SETTING);

        // When toggle override matches its default state (dw flag), don't override flags
        assertThat(mOverriddenLocalFlag.isTrue()).isTrue();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isTrue();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    public void isTrue_dwFlagOff_overrideOff_featureFlagOff_returnsFalse() throws Exception {
        mLocalFlagValue = false;
        setOverride(OVERRIDE_OFF_SETTING);

        assertThat(mOverriddenLocalFlag.isTrue()).isFalse();
        assertThat(mNotOverriddenLocalFlag.isTrue()).isFalse();
    }

    @Test
    public void fromSetting_validInt_returnsToggleOverride() {
        assertThat(fromSetting(0, OVERRIDE_UNSET)).isEqualTo(OVERRIDE_OFF);
        assertThat(fromSetting(1, OVERRIDE_UNSET)).isEqualTo(OVERRIDE_ON);
        assertThat(fromSetting(-1, OVERRIDE_ON)).isEqualTo(OVERRIDE_UNSET);
    }

    @Test
    public void fromSetting_invalidInt_returnsFallback() {
        assertThat(fromSetting(2, OVERRIDE_ON)).isEqualTo(OVERRIDE_ON);
        assertThat(fromSetting(-2, OVERRIDE_UNSET)).isEqualTo(OVERRIDE_UNSET);
    }

    @Test
    public void getSetting_returnsToggleOverrideInteger() {
        assertThat(OVERRIDE_OFF.getSetting()).isEqualTo(0);
        assertThat(OVERRIDE_ON.getSetting()).isEqualTo(1);
        assertThat(OVERRIDE_UNSET.getSetting()).isEqualTo(-1);
    }

    private void setOverride(Integer setting) throws Exception {
        setSysProp(setting);

        ContentResolver contentResolver = mContext.getContentResolver();
        String key = Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES;

        if (setting == null) {
            Settings.Global.putString(contentResolver, key, null);
        } else {
            Settings.Global.putInt(contentResolver, key, setting);
        }
    }

    private void setSysProp(Integer value) throws Exception {
        if (value == null) {
            resetSysProp();
        } else {
            mUiDevice.executeShellCommand(
                    "setprop " + DesktopModeFlags.SYSTEM_PROPERTY_NAME + " " + value);
        }
    }

    private void resetSysProp() throws Exception {
        mUiDevice.executeShellCommand("setprop " + DesktopModeFlags.SYSTEM_PROPERTY_NAME + " ''");
    }

    private void resetCache() throws Exception {
        Field cachedToggleOverride = DesktopModeFlags.class.getDeclaredField(
                "sCachedToggleOverride");
        cachedToggleOverride.setAccessible(true);
        cachedToggleOverride.set(null, null);
    }

    private boolean showDesktopWindowingDevOpts() {
        return Flags.showDesktopWindowingDevOption() && !Flags.showDesktopExperienceDevOption();
    }

    private boolean showDesktopExperienceDevOpts() {
        return Flags.showDesktopExperienceDevOption();
    }

    private boolean showAnyDevOpts() {
        return Flags.showDesktopWindowingDevOption() || Flags.showDesktopExperienceDevOption();
    }
}
