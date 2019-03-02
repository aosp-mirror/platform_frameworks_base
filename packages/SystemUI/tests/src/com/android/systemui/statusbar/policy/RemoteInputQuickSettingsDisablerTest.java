/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static junit.framework.TestCase.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RemoteInputQuickSettingsDisablerTest extends SysuiTestCase {

    private CommandQueue mCommandQueue;
    private RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mCommandQueue = mock(CommandQueue.class);
        mContext.putComponent(CommandQueue.class, mCommandQueue);

        mRemoteInputQuickSettingsDisabler = new RemoteInputQuickSettingsDisabler(mContext);
    }

    @Test
    public void shouldEnableQuickSetting_afterDeactiviate() {
        mRemoteInputQuickSettingsDisabler.setRemoteInputActive(Boolean.TRUE);
        mRemoteInputQuickSettingsDisabler.setRemoteInputActive(Boolean.FALSE);
        assertFalse(mRemoteInputQuickSettingsDisabler.mRemoteInputActive);
        verify(mCommandQueue, atLeastOnce()).recomputeDisableFlags(anyBoolean());
    }

    @Test
    public void shouldDisableQuickSetting_afteActiviate() {
        mRemoteInputQuickSettingsDisabler.setRemoteInputActive(Boolean.FALSE);
        mRemoteInputQuickSettingsDisabler.setRemoteInputActive(Boolean.TRUE);
        assertTrue(mRemoteInputQuickSettingsDisabler.mRemoteInputActive);
        verify(mCommandQueue, atLeastOnce()).recomputeDisableFlags(anyBoolean());
    }

    @Test
    public void testChangeToLandscape() {
        Configuration c = new Configuration(mContext.getResources().getConfiguration());
        c.orientation = Configuration.ORIENTATION_PORTRAIT;
        mRemoteInputQuickSettingsDisabler.onConfigChanged(c);
        c.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mRemoteInputQuickSettingsDisabler.onConfigChanged(c);
        assertTrue(mRemoteInputQuickSettingsDisabler.misLandscape);
        verify(mCommandQueue, atLeastOnce()).recomputeDisableFlags(anyBoolean());
    }

    @Test
    public void testChangeToPortrait() {
        Configuration c = new Configuration(mContext.getResources().getConfiguration());
        c.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mRemoteInputQuickSettingsDisabler.onConfigChanged(c);
        c.orientation = Configuration.ORIENTATION_PORTRAIT;
        mRemoteInputQuickSettingsDisabler.onConfigChanged(c);
        assertFalse(mRemoteInputQuickSettingsDisabler.misLandscape);
        verify(mCommandQueue, atLeastOnce()).recomputeDisableFlags(anyBoolean());
    }

}
