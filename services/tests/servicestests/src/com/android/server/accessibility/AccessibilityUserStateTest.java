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

package com.android.server.accessibility;

import static android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD;
import static android.content.pm.PackageManager.FEATURE_WINDOW_MAGNIFICATION;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.accessibility.AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
import static android.view.accessibility.AccessibilityManager.STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED;
import static android.view.accessibility.AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;

import static com.android.server.accessibility.AccessibilityUserState.doesShortcutTargetsStringContain;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.testing.DexmakerShareClassLoaderRule;
import android.util.ArraySet;
import android.view.Display;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for AccessibilityUserState */
public class AccessibilityUserStateTest {

    private static final ComponentName COMPONENT_NAME =
            new ComponentName("com.android.server.accessibility", "AccessibilityUserStateTest");
    private static final ComponentName COMPONENT_NAME1 =
            new ComponentName("com.android.server.accessibility",
                    "com.android.server.accessibility.AccessibilityUserStateTest1");
    private static final ComponentName COMPONENT_NAME2 =
            new ComponentName("com.android.server.accessibility",
                    "com.android.server.accessibility.AccessibilityUserStateTest2");

    // Values of setting key SHOW_IME_WITH_HARD_KEYBOARD
    private static final int STATE_HIDE_IME = 0;
    private static final int STATE_SHOW_IME = 1;

    private static final int USER_ID = 42;

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;

    // Mock package-private class AccessibilityServiceConnection
    @Rule public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock private AccessibilityServiceInfo mMockServiceInfo;

    @Mock private AccessibilityServiceConnection mMockConnection;

    @Mock private AccessibilityUserState.ServiceInfoChangeListener mMockListener;

    @Mock private PackageManager mMockPackageManager;

    @Mock private Context mMockContext;

    private MockContentResolver mMockResolver;

    private AccessibilityUserState mUserState;

    private int mFocusStrokeWidthDefaultValue;
    private int mFocusColorDefaultValue;

    @Before
    public void setUp() {
        final Resources resources = InstrumentationRegistry.getContext().getResources();

        MockitoAnnotations.initMocks(this);
        FakeSettingsProvider.clearSettingsProvider();
        mMockResolver = new MockContentResolver();
        mMockResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mMockContext.getContentResolver()).thenReturn(mMockResolver);
        when(mMockServiceInfo.getComponentName()).thenReturn(COMPONENT_NAME);
        when(mMockConnection.getServiceInfo()).thenReturn(mMockServiceInfo);
        when(mMockContext.getResources()).thenReturn(resources);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.hasSystemFeature(FEATURE_WINDOW_MAGNIFICATION)).thenReturn(true);

        mFocusStrokeWidthDefaultValue =
                resources.getDimensionPixelSize(R.dimen.accessibility_focus_highlight_stroke_width);
        mFocusColorDefaultValue = resources.getColor(R.color.accessibility_focus_highlight_color);

        mUserState = new AccessibilityUserState(USER_ID, mMockContext, mMockListener);
    }

    @After
    public void tearDown() {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Test
    public void onSwitchToAnotherUser_userStateClearedNonDefaultValues() {
        mUserState.getBoundServicesLocked().add(mMockConnection);
        mUserState.getBindingServicesLocked().add(COMPONENT_NAME);
        mUserState.setLastSentClientStateLocked(
                STATE_FLAG_ACCESSIBILITY_ENABLED
                        | STATE_FLAG_TOUCH_EXPLORATION_ENABLED
                        | STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED);
        mUserState.setNonInteractiveUiTimeoutLocked(30);
        mUserState.setInteractiveUiTimeoutLocked(30);
        mUserState.mEnabledServices.add(COMPONENT_NAME);
        mUserState.mTouchExplorationGrantedServices.add(COMPONENT_NAME);
        mUserState.mAccessibilityShortcutKeyTargets.add(COMPONENT_NAME.flattenToString());
        mUserState.mAccessibilityButtonTargets.add(COMPONENT_NAME.flattenToString());
        mUserState.setTargetAssignedToAccessibilityButton(COMPONENT_NAME.flattenToString());
        mUserState.setTouchExplorationEnabledLocked(true);
        mUserState.setMagnificationSingleFingerTripleTapEnabledLocked(true);
        mUserState.setMagnificationTwoFingerTripleTapEnabledLocked(true);
        mUserState.setAutoclickEnabledLocked(true);
        mUserState.setUserNonInteractiveUiTimeoutLocked(30);
        mUserState.setUserInteractiveUiTimeoutLocked(30);
        mUserState.setMagnificationModeLocked(TEST_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mUserState.setFocusAppearanceLocked(20, Color.BLUE);

        mUserState.onSwitchToAnotherUserLocked();

        verify(mMockConnection).unbindLocked();
        assertTrue(mUserState.getBoundServicesLocked().isEmpty());
        assertTrue(mUserState.getBindingServicesLocked().isEmpty());
        assertEquals(-1, mUserState.getLastSentClientStateLocked());
        assertEquals(0, mUserState.getNonInteractiveUiTimeoutLocked());
        assertEquals(0, mUserState.getInteractiveUiTimeoutLocked());
        assertTrue(mUserState.mEnabledServices.isEmpty());
        assertTrue(mUserState.mTouchExplorationGrantedServices.isEmpty());
        assertTrue(mUserState.mAccessibilityShortcutKeyTargets.isEmpty());
        assertTrue(mUserState.mAccessibilityButtonTargets.isEmpty());
        assertNull(mUserState.getTargetAssignedToAccessibilityButton());
        assertFalse(mUserState.isTouchExplorationEnabledLocked());
        assertFalse(mUserState.isMagnificationSingleFingerTripleTapEnabledLocked());
        assertFalse(mUserState.isMagnificationTwoFingerTripleTapEnabledLocked());
        assertFalse(mUserState.isAutoclickEnabledLocked());
        assertEquals(0, mUserState.getUserNonInteractiveUiTimeoutLocked());
        assertEquals(0, mUserState.getUserInteractiveUiTimeoutLocked());
        assertEquals(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN,
                mUserState.getMagnificationModeLocked(TEST_DISPLAY));
        assertEquals(mFocusStrokeWidthDefaultValue, mUserState.getFocusStrokeWidthLocked());
        assertEquals(mFocusColorDefaultValue, mUserState.getFocusColorLocked());
        assertTrue(mUserState.isMagnificationFollowTypingEnabled());
        assertFalse(mUserState.isAlwaysOnMagnificationEnabled());
    }

    @Test
    public void addService_connectionAlreadyAdded_notAddAgain() {
        mUserState.getBoundServicesLocked().add(mMockConnection);

        mUserState.addServiceLocked(mMockConnection);

        verify(mMockListener, never()).onServiceInfoChangedLocked(any());
    }

    @Test
    public void addService_connectionNotYetAddedToBoundService_addAndNotifyServices() {
        when(mMockConnection.getComponentName()).thenReturn(COMPONENT_NAME);

        mUserState.addServiceLocked(mMockConnection);

        assertTrue(mUserState.getBoundServicesLocked().contains(mMockConnection));
        assertEquals(mMockConnection, mUserState.mComponentNameToServiceMap.get(COMPONENT_NAME));
        verify(mMockListener).onServiceInfoChangedLocked(eq(mUserState));
    }

    @Test
    // addServiceLocked only calls addWindowTokensForAllDisplays when
    // FLAG_ADD_WINDOW_TOKEN_WITHOUT_LOCK is off, so skip the test if it is on.
    @RequiresFlagsDisabled(Flags.FLAG_ADD_WINDOW_TOKEN_WITHOUT_LOCK)
    public void addService_flagDisabled_addsWindowTokens() {
        when(mMockConnection.getComponentName()).thenReturn(COMPONENT_NAME);

        mUserState.addServiceLocked(mMockConnection);

        verify(mMockConnection).addWindowTokensForAllDisplays();
    }

    @Test
    public void reconcileSoftKeyboardMode_whenStateNotMatchSettings_setBothDefault() {
        // When soft kb show mode is hidden in settings and is auto in state.
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                SHOW_MODE_HIDDEN, USER_ID);

        mUserState.reconcileSoftKeyboardModeWithSettingsLocked();

        assertEquals(SHOW_MODE_AUTO, mUserState.getSoftKeyboardShowModeLocked());
        assertEquals(SHOW_MODE_AUTO, getSecureIntForUser(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, USER_ID));
        assertNull(mUserState.getServiceChangingSoftKeyboardModeLocked());
    }

    @Test
    public void
            reconcileSoftKeyboardMode_stateIgnoreHardKb_settingsShowImeHardKb_setAutoOverride() {
        // When show mode is ignore hard kb without original hard kb value
        // and show ime with hard kb is hide
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                SHOW_MODE_IGNORE_HARD_KEYBOARD, USER_ID);
        mUserState.setSoftKeyboardModeLocked(SHOW_MODE_IGNORE_HARD_KEYBOARD, COMPONENT_NAME);
        putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                STATE_HIDE_IME, USER_ID);

        mUserState.reconcileSoftKeyboardModeWithSettingsLocked();

        assertEquals(SHOW_MODE_AUTO | SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN,
                getSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, USER_ID));
        assertNull(mUserState.getServiceChangingSoftKeyboardModeLocked());
    }

    @Test
    public void removeService_serviceChangingSoftKeyboardMode_removeAndSetSoftKbModeAuto() {
        mUserState.setServiceChangingSoftKeyboardModeLocked(COMPONENT_NAME);
        mUserState.mComponentNameToServiceMap.put(COMPONENT_NAME, mMockConnection);
        mUserState.setSoftKeyboardModeLocked(SHOW_MODE_HIDDEN, COMPONENT_NAME);

        mUserState.removeServiceLocked(mMockConnection);

        assertFalse(mUserState.getBoundServicesLocked().contains(mMockConnection));
        verify(mMockConnection).onRemoved();
        assertEquals(SHOW_MODE_AUTO, mUserState.getSoftKeyboardShowModeLocked());
        assertNull(mUserState.mComponentNameToServiceMap.get(COMPONENT_NAME));
        verify(mMockListener).onServiceInfoChangedLocked(eq(mUserState));
    }

    @Test
    public void serviceDisconnected_removeServiceAndAddToCrashed() {
        when(mMockConnection.getComponentName()).thenReturn(COMPONENT_NAME);
        mUserState.addServiceLocked(mMockConnection);

        mUserState.serviceDisconnectedLocked(mMockConnection);

        assertFalse(mUserState.getBoundServicesLocked().contains(mMockConnection));
        assertTrue(mUserState.getCrashedServicesLocked().contains(COMPONENT_NAME));
    }

    @Test
    public void setSoftKeyboardMode_withInvalidShowMode_shouldKeepDefaultAuto() {
        final int invalidShowMode = SHOW_MODE_HIDDEN | SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE;

        assertFalse(mUserState.setSoftKeyboardModeLocked(invalidShowMode, null));

        assertEquals(SHOW_MODE_AUTO, mUserState.getSoftKeyboardShowModeLocked());
    }

    @Test
    public void setSoftKeyboardMode_newModeSameWithCurrentState_returnTrue() {
        when(mMockConnection.getComponentName()).thenReturn(COMPONENT_NAME);
        mUserState.addServiceLocked(mMockConnection);

        assertTrue(mUserState.setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null));
    }

    @Test
    public void setSoftKeyboardMode_withIgnoreHardKb_whenHardKbOverridden_returnFalseAdNoChange() {
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                SHOW_MODE_AUTO | SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN, USER_ID);

        assertFalse(mUserState.setSoftKeyboardModeLocked(SHOW_MODE_IGNORE_HARD_KEYBOARD, null));

        assertEquals(SHOW_MODE_AUTO, mUserState.getSoftKeyboardShowModeLocked());
    }

    @Test
    public void
            setSoftKeyboardMode_withIgnoreHardKb_whenShowImeWithHardKb_setOriginalHardKbValue() {
        putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, STATE_SHOW_IME, USER_ID);

        assertTrue(mUserState.setSoftKeyboardModeLocked(SHOW_MODE_IGNORE_HARD_KEYBOARD, null));

        assertEquals(SHOW_MODE_IGNORE_HARD_KEYBOARD | SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE,
                getSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, USER_ID));
    }

    @Test
    public void setSoftKeyboardMode_whenCurrentIgnoreHardKb_shouldSetShowImeWithHardKbValue() {
        mUserState.setSoftKeyboardModeLocked(SHOW_MODE_IGNORE_HARD_KEYBOARD, COMPONENT_NAME);
        putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, STATE_HIDE_IME, USER_ID);
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                SHOW_MODE_IGNORE_HARD_KEYBOARD | SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE, USER_ID);

        assertTrue(mUserState.setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null));

        assertEquals(STATE_SHOW_IME, getSecureIntForUser(
                Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, USER_ID));
    }

    @Test
    public void setSoftKeyboardMode_withRequester_shouldUpdateInternalStateAndSettingsAsIs() {
        assertTrue(mUserState.setSoftKeyboardModeLocked(SHOW_MODE_HIDDEN, COMPONENT_NAME));

        assertEquals(SHOW_MODE_HIDDEN, mUserState.getSoftKeyboardShowModeLocked());
        assertEquals(SHOW_MODE_HIDDEN, getSecureIntForUser(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, USER_ID));
        assertEquals(COMPONENT_NAME, mUserState.getServiceChangingSoftKeyboardModeLocked());
    }

    @Test
    public void setSoftKeyboardMode_shouldNotifyBoundService() {
        mUserState.addServiceLocked(mMockConnection);

        assertTrue(mUserState.setSoftKeyboardModeLocked(SHOW_MODE_HIDDEN, COMPONENT_NAME));

        verify(mMockConnection).notifySoftKeyboardShowModeChangedLocked(eq(SHOW_MODE_HIDDEN));
    }

    @Test
    public void doesShortcutTargetsStringContain_returnFalse() {
        assertFalse(doesShortcutTargetsStringContain(null, null));
        assertFalse(doesShortcutTargetsStringContain(null,
                COMPONENT_NAME.flattenToShortString()));
        assertFalse(doesShortcutTargetsStringContain(new ArraySet<>(), null));

        final ArraySet<String> shortcutTargets = new ArraySet<>();
        shortcutTargets.add(COMPONENT_NAME.flattenToString());
        assertFalse(doesShortcutTargetsStringContain(shortcutTargets,
                COMPONENT_NAME1.flattenToString()));
    }

    @Test
    public void isAssignedToShortcutLocked_withDifferentTypeComponentString_returnTrue() {
        final ArraySet<String> shortcutTargets = new ArraySet<>();
        shortcutTargets.add(COMPONENT_NAME1.flattenToShortString());
        shortcutTargets.add(COMPONENT_NAME2.flattenToString());

        assertTrue(doesShortcutTargetsStringContain(shortcutTargets,
                COMPONENT_NAME1.flattenToString()));
        assertTrue(doesShortcutTargetsStringContain(shortcutTargets,
                COMPONENT_NAME2.flattenToShortString()));
    }

    @Test
    public void isShortcutTargetInstalledLocked_returnTrue() {
        mUserState.mInstalledServices.add(mMockServiceInfo);
        assertTrue(mUserState.isShortcutTargetInstalledLocked(COMPONENT_NAME.flattenToString()));
    }

    @Test
    public void isShortcutTargetInstalledLocked_invalidTarget_returnFalse() {
        final ComponentName invalidTarget =
                new ComponentName("com.android.server.accessibility", "InvalidTarget");
        assertFalse(mUserState.isShortcutTargetInstalledLocked(invalidTarget.flattenToString()));
    }

    @Test
    public void setWindowMagnificationMode_returnExpectedMagnificationMode() {
        assertEquals(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN,
                mUserState.getMagnificationModeLocked(TEST_DISPLAY));

        mUserState.setMagnificationModeLocked(TEST_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        assertEquals(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                mUserState.getMagnificationModeLocked(TEST_DISPLAY));
    }

    @Test
    public void setMagnificationFollowTypingEnabled_defaultTrueAndThenDisable_returnFalse() {
        assertTrue(mUserState.isMagnificationFollowTypingEnabled());

        mUserState.setMagnificationFollowTypingEnabled(false);

        assertFalse(mUserState.isMagnificationFollowTypingEnabled());
    }

    @Test
    public void setAlwaysOnMagnificationEnabled_defaultFalseAndSetTrue_returnTrue() {
        assertFalse(mUserState.isAlwaysOnMagnificationEnabled());

        mUserState.setAlwaysOnMagnificationEnabled(true);

        assertTrue(mUserState.isAlwaysOnMagnificationEnabled());
    }

    @Test
    public void setFocusAppearanceData_returnExpectedFocusAppearanceData() {
        final int focusStrokeWidthValue = 100;
        final int focusColorValue = Color.BLUE;

        assertEquals(mFocusStrokeWidthDefaultValue, mUserState.getFocusStrokeWidthLocked());
        assertEquals(mFocusColorDefaultValue, mUserState.getFocusColorLocked());

        mUserState.setFocusAppearanceLocked(focusStrokeWidthValue, focusColorValue);

        assertEquals(focusStrokeWidthValue, mUserState.getFocusStrokeWidthLocked());
        assertEquals(focusColorValue, mUserState.getFocusColorLocked());

    }

    private int getSecureIntForUser(String key, int userId) {
        return Settings.Secure.getIntForUser(mMockResolver, key, -1, userId);
    }

    private void putSecureIntForUser(String key, int value, int userId) {
        Settings.Secure.putIntForUser(mMockResolver, key, value, userId);
    }
}
