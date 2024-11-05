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

package com.android.systemui.globalactions;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.nearby.NearbyManager;
import android.net.platform.flags.Flags;
import android.os.PowerManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ShutdownUiTest extends SysuiTestCase {

    ShutdownUi mShutdownUi;
    @Mock
    NearbyManager mNearbyManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(mContext);
        mShutdownUi = new ShutdownUi(mContext, mNearbyManager);
    }

    @Test
    public void getRebootMessage_update() {
        int messageId = mShutdownUi.getRebootMessage(true, PowerManager.REBOOT_RECOVERY_UPDATE);
        assertEquals(messageId, R.string.reboot_to_update_reboot);
    }

    @Test
    public void getRebootMessage_rebootDefault() {
        int messageId = mShutdownUi.getRebootMessage(true, "anything-else");
        assertEquals(messageId, R.string.reboot_to_reset_message);
    }

    @Test
    public void getRebootMessage_shutdown() {
        int messageId = mShutdownUi.getRebootMessage(false, "anything-else");
        assertEquals(messageId, R.string.shutdown_progress);
    }

    @Test
    public void getReasonMessage_update() {
        String message = mShutdownUi.getReasonMessage(PowerManager.REBOOT_RECOVERY_UPDATE);
        assertEquals(message, mContext.getString(R.string.reboot_to_update_title));
    }

    @Test
    public void getReasonMessage_rebootDefault() {
        String message = mShutdownUi.getReasonMessage(PowerManager.REBOOT_RECOVERY);
        assertEquals(message, mContext.getString(R.string.reboot_to_reset_title));
    }

    @Test
    public void getRebootMessage_defaultToNone() {
        String message = mShutdownUi.getReasonMessage("anything-else");
        assertNull(message);
    }

    @EnableFlags(Flags.FLAG_POWERED_OFF_FINDING_PLATFORM)
    @Test
    public void getDialog_whenPowerOffFindingModeEnabled_returnsFinderDialog() {
        when(mNearbyManager.getPoweredOffFindingMode()).thenReturn(
                NearbyManager.POWERED_OFF_FINDING_MODE_ENABLED);

        int actualLayout = mShutdownUi.getShutdownDialogContent(false);

        int expectedLayout = com.android.systemui.res.R.layout.shutdown_dialog_finder_active;
        assertEquals(actualLayout, expectedLayout);
    }

    @DisableFlags(Flags.FLAG_POWERED_OFF_FINDING_PLATFORM)
    @Test
    public void getDialog_whenPowerOffFindingModeEnabledFlagDisabled_returnsFinderDialog() {
        when(mNearbyManager.getPoweredOffFindingMode()).thenReturn(
                NearbyManager.POWERED_OFF_FINDING_MODE_ENABLED);

        int actualLayout = mShutdownUi.getShutdownDialogContent(false);

        int expectedLayout = R.layout.shutdown_dialog;
        assertEquals(actualLayout, expectedLayout);
    }

    @EnableFlags(Flags.FLAG_POWERED_OFF_FINDING_PLATFORM)
    @Test
    public void getDialog_whenPowerOffFindingModeDisabled_returnsDefaultDialog() {
        when(mNearbyManager.getPoweredOffFindingMode()).thenReturn(
                NearbyManager.POWERED_OFF_FINDING_MODE_DISABLED);

        int actualLayout = mShutdownUi.getShutdownDialogContent(false);

        int expectedLayout = R.layout.shutdown_dialog;
        assertEquals(actualLayout, expectedLayout);
    }

    @EnableFlags(Flags.FLAG_POWERED_OFF_FINDING_PLATFORM)
    @Test
    public void getDialog_whenPowerOffFindingModeEnabledAndIsReboot_returnsDefaultDialog() {
        when(mNearbyManager.getPoweredOffFindingMode()).thenReturn(
                NearbyManager.POWERED_OFF_FINDING_MODE_ENABLED);

        int actualLayout = mShutdownUi.getShutdownDialogContent(true);

        int expectedLayout = R.layout.shutdown_dialog;
        assertEquals(actualLayout, expectedLayout);
    }

    /**
     * Main looper required here because showShutdown UI creates a dialog which instantiates a
     * handler that needs to be on the main thread.
     */
    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    @Test
    public void showShutdownUi_loadsShutdownTextColorAndAlpha() {
        this.allowTestableLooperAsMainThread();

        Resources mockResources = spy(mContext.getResources());
        when(mContext.getResources()).thenReturn(mockResources);

        mShutdownUi.showShutdownUi(false, "test");

        verify(mockResources).getFloat(
                eq(com.android.systemui.res.R.dimen.shutdown_scrim_behind_alpha));
        verify(mockResources).getColor(
                eq(com.android.systemui.res.R.color.global_actions_shutdown_ui_text),
                any());
    }
}
