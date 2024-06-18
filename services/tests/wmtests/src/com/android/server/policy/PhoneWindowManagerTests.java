/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.policy;

import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerGlobal.ADD_OKAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test class for {@link PhoneWindowManager}.
 *
 * Build/Install/Run:
 *  atest WmTests:PhoneWindowManagerTests
 */
@SmallTest
public class PhoneWindowManagerTests {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    PhoneWindowManager mPhoneWindowManager;

    @Before
    public void setUp() {
        mPhoneWindowManager = spy(new PhoneWindowManager());
        spyOn(ActivityManager.getService());
    }

    @After
    public void tearDown() {
        reset(ActivityManager.getService());
    }

    @Test
    public void testShouldNotStartDockOrHomeWhenSetup() throws Exception {
        mockStartDockOrHome();
        doReturn(false).when(mPhoneWindowManager).isUserSetupComplete();

        mPhoneWindowManager.startDockOrHome(
                0 /* displayId */, false /* fromHomeKey */, false /* awakenFromDreams */);

        verify(mPhoneWindowManager, never()).createHomeDockIntent();
    }

    @Test
    public void testShouldStartDockOrHomeAfterSetup() throws Exception {
        mockStartDockOrHome();
        doReturn(true).when(mPhoneWindowManager).isUserSetupComplete();

        mPhoneWindowManager.startDockOrHome(
                0 /* displayId */, false /* fromHomeKey */, false /* awakenFromDreams */);

        verify(mPhoneWindowManager).createHomeDockIntent();
    }

    @Test
    public void testCheckAddPermission_withoutAccessibilityOverlay_noAccessibilityAppOpLogged() {
        mSetFlagsRule.enableFlags(android.view.contentprotection.flags.Flags
                .FLAG_CREATE_ACCESSIBILITY_OVERLAY_APP_OP_ENABLED);
        int[] outAppOp = new int[1];
        assertEquals(ADD_OKAY, mPhoneWindowManager.checkAddPermission(TYPE_WALLPAPER,
                /* isRoundedCornerOverlay= */ false, "test.pkg", outAppOp));
        assertThat(outAppOp[0]).isEqualTo(AppOpsManager.OP_NONE);
    }

    @Test
    public void testCheckAddPermission_withAccessibilityOverlay() {
        mSetFlagsRule.enableFlags(android.view.contentprotection.flags.Flags
                .FLAG_CREATE_ACCESSIBILITY_OVERLAY_APP_OP_ENABLED);
        int[] outAppOp = new int[1];
        assertEquals(ADD_OKAY, mPhoneWindowManager.checkAddPermission(TYPE_ACCESSIBILITY_OVERLAY,
                /* isRoundedCornerOverlay= */ false, "test.pkg", outAppOp));
        assertThat(outAppOp[0]).isEqualTo(AppOpsManager.OP_CREATE_ACCESSIBILITY_OVERLAY);
    }

    @Test
    public void testCheckAddPermission_withAccessibilityOverlay_flagDisabled() {
        mSetFlagsRule.disableFlags(android.view.contentprotection.flags.Flags
                .FLAG_CREATE_ACCESSIBILITY_OVERLAY_APP_OP_ENABLED);
        int[] outAppOp = new int[1];
        assertEquals(ADD_OKAY, mPhoneWindowManager.checkAddPermission(TYPE_ACCESSIBILITY_OVERLAY,
                /* isRoundedCornerOverlay= */ false, "test.pkg", outAppOp));
        assertThat(outAppOp[0]).isEqualTo(AppOpsManager.OP_NONE);
    }

    private void mockStartDockOrHome() throws Exception {
        doNothing().when(ActivityManager.getService()).stopAppSwitches();
        ActivityTaskManagerInternal mMockActivityTaskManagerInternal =
                mock(ActivityTaskManagerInternal.class);
        when(mMockActivityTaskManagerInternal.startHomeOnDisplay(
                anyInt(), anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(false);
        mPhoneWindowManager.mActivityTaskManagerInternal = mMockActivityTaskManagerInternal;
        mPhoneWindowManager.mUserManagerInternal = mock(UserManagerInternal.class);
    }
}
