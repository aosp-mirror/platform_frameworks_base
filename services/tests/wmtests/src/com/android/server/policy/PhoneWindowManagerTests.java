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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManagerGlobal.ADD_OKAY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.PowerManager;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.keyguard.KeyguardServiceDelegate;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.DisplayPolicy;
import com.android.server.wm.DisplayRotation;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test class for {@link PhoneWindowManager}.
 *
 * Build/Install/Run:
 * atest WmTests:PhoneWindowManagerTests
 */
@SmallTest
public class PhoneWindowManagerTests {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    PhoneWindowManager mPhoneWindowManager;
    private ActivityTaskManagerInternal mAtmInternal;
    private StatusBarManagerInternal mStatusBarManagerInternal;
    private Context mContext;

    @Before
    public void setUp() {
        mPhoneWindowManager = spy(new PhoneWindowManager());
        spyOn(ActivityManager.getService());
        mContext = getInstrumentation().getTargetContext();
        spyOn(mContext);
        mAtmInternal = mock(ActivityTaskManagerInternal.class);
        LocalServices.addService(ActivityTaskManagerInternal.class, mAtmInternal);
        mPhoneWindowManager.mActivityTaskManagerInternal = mAtmInternal;
        LocalServices.addService(WindowManagerInternal.class, mock(WindowManagerInternal.class));
        mStatusBarManagerInternal = mock(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarManagerInternal);
        mPhoneWindowManager.mKeyguardDelegate = mock(KeyguardServiceDelegate.class);
    }

    @After
    public void tearDown() {
        reset(ActivityManager.getService());
        reset(mContext);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
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
    public void testScreenTurnedOff() {
        mSetFlagsRule.enableFlags(com.android.window.flags.Flags
                .FLAG_SKIP_SLEEPING_WHEN_SWITCHING_DISPLAY);
        doNothing().when(mPhoneWindowManager).updateSettings(any());
        doNothing().when(mPhoneWindowManager).initializeHdmiState();
        final boolean[] isScreenTurnedOff = { false };
        final DisplayPolicy displayPolicy = mock(DisplayPolicy.class);
        doAnswer(invocation -> isScreenTurnedOff[0] = true).when(displayPolicy).screenTurnedOff();
        doAnswer(invocation -> !isScreenTurnedOff[0]).when(displayPolicy).isScreenOnEarly();
        doAnswer(invocation -> !isScreenTurnedOff[0]).when(displayPolicy).isScreenOnFully();

        mPhoneWindowManager.mDefaultDisplayPolicy = displayPolicy;
        mPhoneWindowManager.mDefaultDisplayRotation = mock(DisplayRotation.class);
        final ActivityTaskManagerInternal.SleepTokenAcquirer tokenAcquirer =
                mock(ActivityTaskManagerInternal.SleepTokenAcquirer.class);
        doReturn(tokenAcquirer).when(mAtmInternal).createSleepTokenAcquirer(anyString());
        final PowerManager pm = mock(PowerManager.class);
        doReturn(true).when(pm).isInteractive();
        doReturn(pm).when(mContext).getSystemService(eq(Context.POWER_SERVICE));

        mContext.getMainThreadHandler().runWithScissors(() -> mPhoneWindowManager.init(
                new PhoneWindowManager.Injector(mContext,
                        mock(WindowManagerPolicy.WindowManagerFuncs.class))), 0);
        assertThat(isScreenTurnedOff[0]).isFalse();
        assertThat(mPhoneWindowManager.mIsGoingToSleepDefaultDisplay).isFalse();

        // Skip sleep-token for non-sleep-screen-off.
        clearInvocations(tokenAcquirer);
        mPhoneWindowManager.screenTurnedOff(DEFAULT_DISPLAY, true /* isSwappingDisplay */);
        verify(tokenAcquirer, never()).acquire(anyInt(), anyBoolean());
        assertThat(isScreenTurnedOff[0]).isTrue();

        // Apply sleep-token for sleep-screen-off.
        mPhoneWindowManager.startedGoingToSleep(DEFAULT_DISPLAY, 0 /* reason */);
        assertThat(mPhoneWindowManager.mIsGoingToSleepDefaultDisplay).isTrue();
        mPhoneWindowManager.screenTurnedOff(DEFAULT_DISPLAY, true /* isSwappingDisplay */);
        verify(tokenAcquirer).acquire(eq(DEFAULT_DISPLAY), eq(true));

        mPhoneWindowManager.finishedGoingToSleep(DEFAULT_DISPLAY, 0 /* reason */);
        assertThat(mPhoneWindowManager.mIsGoingToSleepDefaultDisplay).isFalse();

        // Simulate unexpected reversed order: screenTurnedOff -> startedGoingToSleep. The sleep
        // token can still be acquired.
        isScreenTurnedOff[0] = false;
        clearInvocations(tokenAcquirer);
        mPhoneWindowManager.screenTurnedOff(DEFAULT_DISPLAY, true /* isSwappingDisplay */);
        verify(tokenAcquirer, never()).acquire(anyInt(), anyBoolean());
        assertThat(displayPolicy.isScreenOnEarly()).isFalse();
        assertThat(displayPolicy.isScreenOnFully()).isFalse();
        mPhoneWindowManager.startedGoingToSleep(DEFAULT_DISPLAY, 0 /* reason */);
        verify(tokenAcquirer).acquire(eq(DEFAULT_DISPLAY), eq(false));
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

    @Test
    public void userSwitching_keyboardShortcutHelperDismissed() {
        mPhoneWindowManager.setSwitchingUser(true);

        verify(mStatusBarManagerInternal).dismissKeyboardShortcutsMenu();
    }

    @Test
    public void userNotSwitching_keyboardShortcutHelperDismissed() {
        mPhoneWindowManager.setSwitchingUser(false);

        verify(mStatusBarManagerInternal, never()).dismissKeyboardShortcutsMenu();
    }

    private void mockStartDockOrHome() throws Exception {
        doNothing().when(ActivityManager.getService()).stopAppSwitches();
        when(mAtmInternal.startHomeOnDisplay(
                anyInt(), anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(false);
        mPhoneWindowManager.mUserManagerInternal = mock(UserManagerInternal.class);
    }
}
