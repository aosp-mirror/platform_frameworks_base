/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.wm;

import static android.provider.Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.res.Resources;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.window.DesktopModeFlags;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.window.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.lang.reflect.Field;

/**
 * Test class for {@link DesktopModeHelper}.
 */
@SmallTest
@Presubmit
@EnableFlags(Flags.FLAG_SHOW_DESKTOP_WINDOWING_DEV_OPTION)
public class DesktopModeHelperTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;
    private Context mMockContext;
    private Resources mMockResources;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mMockContext = mock(Context.class);
        mMockResources = mock(Resources.class);

        doReturn(mMockResources).when(mMockContext).getResources();
        doReturn(false).when(mMockResources).getBoolean(eq(R.bool.config_isDesktopModeSupported));
        doReturn(false).when(mMockResources).getBoolean(
                eq(R.bool.config_isDesktopModeDevOptionSupported));
        doReturn(mContext.getContentResolver()).when(mMockContext).getContentResolver();
        resetDesktopModeFlagsCache();
        resetEnforceDeviceRestriction();
        resetFlagOverride();
    }

    @After
    public void tearDown() throws Exception {
        resetDesktopModeFlagsCache();
        resetEnforceDeviceRestriction();
        resetFlagOverride();
    }

    @DisableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION})
    @Test
    public void canEnterDesktopMode_DWFlagDisabled_configsOff_returnsFalse() {
        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isFalse();
    }

    @DisableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION})
    @Test
    public void canEnterDesktopMode_DWFlagDisabled_configsOn_disableDeviceCheck_returnsFalse()
            throws Exception {
        doReturn(true).when(mMockResources).getBoolean(eq(R.bool.config_isDesktopModeSupported));
        doReturn(true).when(mMockResources).getBoolean(
                eq(R.bool.config_isDesktopModeDevOptionSupported));
        disableEnforceDeviceRestriction();

        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isFalse();
    }

    @DisableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION})
    @Test
    public void canEnterDesktopMode_DWFlagDisabled_configDevOptionOn_returnsFalse() {
        doReturn(true).when(mMockResources).getBoolean(
                eq(R.bool.config_isDesktopModeDevOptionSupported));

        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isFalse();
    }

    @DisableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION})
    @Test
    public void canEnterDesktopMode_DWFlagDisabled_configDevOptionOn_flagOverrideOn_returnsTrue()
            throws Exception {
        doReturn(true).when(mMockResources).getBoolean(
                eq(R.bool.config_isDesktopModeDevOptionSupported));
        setFlagOverride(DesktopModeFlags.ToggleOverride.OVERRIDE_ON);

        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isTrue();
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION)
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    public void canEnterDesktopMode_DWFlagEnabled_configsOff_returnsFalse() {
        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isFalse();
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION)
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    public void canEnterDesktopMode_DWFlagEnabled_configDesktopModeOff_returnsFalse() {
        doReturn(true).when(mMockResources).getBoolean(
                eq(R.bool.config_isDesktopModeDevOptionSupported));

        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isFalse();
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION)
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    public void canEnterDesktopMode_DWFlagEnabled_configDesktopModeOn_returnsTrue() {
        doReturn(true).when(mMockResources).getBoolean(eq(R.bool.config_isDesktopModeSupported));

        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isTrue();
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION)
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    public void canEnterDesktopMode_DWFlagEnabled_configsOff_disableDeviceRestrictions_returnsTrue()
            throws Exception {
        disableEnforceDeviceRestriction();

        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isTrue();
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION)
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @Test
    public void canEnterDesktopMode_DWFlagEnabled_configDevOptionOn_flagOverrideOn_returnsTrue() {
        doReturn(true).when(mMockResources).getBoolean(
                eq(R.bool.config_isDesktopModeDevOptionSupported)
        );
        setFlagOverride(DesktopModeFlags.ToggleOverride.OVERRIDE_ON);

        assertThat(DesktopModeHelper.canEnterDesktopMode(mMockContext)).isTrue();
    }

    @Test
    public void isDeviceEligibleForDesktopMode_configDEModeOn_returnsTrue() {
        doReturn(true).when(mMockResources).getBoolean(eq(R.bool.config_isDesktopModeSupported));

        assertThat(DesktopModeHelper.isDeviceEligibleForDesktopMode(mMockContext)).isTrue();
    }

    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION)
    @Test
    public void isDeviceEligibleForDesktopMode_supportFlagOff_returnsFalse() {
        assertThat(DesktopModeHelper.isDeviceEligibleForDesktopMode(mMockContext)).isFalse();
    }

    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION)
    @Test
    public void isDeviceEligibleForDesktopMode_supportFlagOn_returnsFalse() {
        assertThat(DesktopModeHelper.isDeviceEligibleForDesktopMode(mMockContext)).isFalse();
    }

    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_MODE_THROUGH_DEV_OPTION)
    @Test
    public void isDeviceEligibleForDesktopMode_supportFlagOn_configDevOptModeOn_returnsTrue() {
        doReturn(true).when(mMockResources).getBoolean(
                eq(R.bool.config_isDesktopModeDevOptionSupported)
        );

        assertThat(DesktopModeHelper.isDeviceEligibleForDesktopMode(mMockContext)).isTrue();
    }

    private void resetEnforceDeviceRestriction() throws Exception {
        setEnforceDeviceRestriction(true);
    }

    private void disableEnforceDeviceRestriction() throws Exception {
        setEnforceDeviceRestriction(false);
    }

    private void setEnforceDeviceRestriction(boolean value) throws Exception {
        Field deviceRestriction = DesktopModeHelper.class.getDeclaredField(
                "ENFORCE_DEVICE_RESTRICTIONS");
        deviceRestriction.setAccessible(true);
        deviceRestriction.setBoolean(/* obj= */ null, /* z= */ value);
    }

    private void resetDesktopModeFlagsCache() throws Exception {
        Field cachedToggleOverride = DesktopModeFlags.class.getDeclaredField(
                "sCachedToggleOverride");
        cachedToggleOverride.setAccessible(true);
        cachedToggleOverride.set(/* obj= */ null, /* value= */ null);
    }

    private void resetFlagOverride() {
        Settings.Global.putString(mContext.getContentResolver(),
                DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES, null);
    }

    private void setFlagOverride(DesktopModeFlags.ToggleOverride override) {
        Settings.Global.putInt(mContext.getContentResolver(),
                DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES, override.getSetting());
    }
}
