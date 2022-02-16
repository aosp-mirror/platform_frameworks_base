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

package com.android.systemui.statusbar;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowInsetsController.BEHAVIOR_DEFAULT;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.ComponentName;
import android.graphics.Rect;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.fingerprint.IUdfpsHbmListener;
import android.os.Bundle;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue.Callbacks;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@SmallTest
public class CommandQueueTest extends SysuiTestCase {

    private CommandQueue mCommandQueue;
    private Callbacks mCallbacks;
    private static final int SECONDARY_DISPLAY = 1;

    @Before
    public void setup() {
        mCommandQueue = new CommandQueue(mContext);
        mCallbacks = mock(Callbacks.class);
        mCommandQueue.addCallback(mCallbacks);
        verify(mCallbacks).disable(anyInt(), eq(0), eq(0), eq(false));
    }

    @After
    public void tearDown() {
        verifyNoMoreInteractions(mCallbacks);
    }

    @Test
    public void testIcon() {
        String slot = "testSlot";
        StatusBarIcon icon = mock(StatusBarIcon.class);
        mCommandQueue.setIcon(slot, icon);
        waitForIdleSync();
        verify(mCallbacks).setIcon(eq(slot), eq(icon));

        mCommandQueue.removeIcon(slot);
        waitForIdleSync();
        verify(mCallbacks).removeIcon(eq(slot));
    }

    @Test
    public void testDisable() {
        int state1 = 14;
        int state2 = 42;
        mCommandQueue.disable(DEFAULT_DISPLAY, state1, state2);
        waitForIdleSync();
        verify(mCallbacks).disable(eq(DEFAULT_DISPLAY), eq(state1), eq(state2), eq(true));
    }

    @Test
    public void testDisableForSecondaryDisplay() {
        int state1 = 14;
        int state2 = 42;
        mCommandQueue.disable(SECONDARY_DISPLAY, state1, state2);
        waitForIdleSync();
        verify(mCallbacks).disable(eq(SECONDARY_DISPLAY), eq(state1), eq(state2), eq(true));
    }

    @Test
    public void testExpandNotifications() {
        mCommandQueue.animateExpandNotificationsPanel();
        waitForIdleSync();
        verify(mCallbacks).animateExpandNotificationsPanel();
    }

    @Test
    public void testCollapsePanels() {
        mCommandQueue.animateCollapsePanels();
        waitForIdleSync();
        verify(mCallbacks).animateCollapsePanels(eq(0), eq(false));
    }

    @Test
    public void testExpandSettings() {
        String panel = "some_panel";
        mCommandQueue.animateExpandSettingsPanel(panel);
        waitForIdleSync();
        verify(mCallbacks).animateExpandSettingsPanel(eq(panel));
    }

    @Test
    public void testOnSystemBarAttributesChanged() {
        doTestOnSystemBarAttributesChanged(DEFAULT_DISPLAY, 1,
                new AppearanceRegion[]{new AppearanceRegion(2, new Rect())}, false,
                BEHAVIOR_DEFAULT, false);
    }

    @Test
    public void testOnSystemBarAttributesChangedForSecondaryDisplay() {
        doTestOnSystemBarAttributesChanged(SECONDARY_DISPLAY, 1,
                new AppearanceRegion[]{new AppearanceRegion(2, new Rect())}, false,
                BEHAVIOR_DEFAULT, false);
    }

    private void doTestOnSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, boolean isFullscreen) {
        mCommandQueue.onSystemBarAttributesChanged(displayId, appearance, appearanceRegions,
                navbarColorManagedByIme, behavior, isFullscreen);
        waitForIdleSync();
        verify(mCallbacks).onSystemBarAttributesChanged(eq(displayId), eq(appearance),
                eq(appearanceRegions), eq(navbarColorManagedByIme), eq(behavior), eq(isFullscreen));
    }

    @Test
    public void testShowTransient() {
        int[] types = new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR};
        mCommandQueue.showTransient(DEFAULT_DISPLAY, types);
        waitForIdleSync();
        verify(mCallbacks).showTransient(eq(DEFAULT_DISPLAY), eq(types));
    }

    @Test
    public void testShowTransientForSecondaryDisplay() {
        int[] types = new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR};
        mCommandQueue.showTransient(SECONDARY_DISPLAY, types);
        waitForIdleSync();
        verify(mCallbacks).showTransient(eq(SECONDARY_DISPLAY), eq(types));
    }

    @Test
    public void testAbortTransient() {
        int[] types = new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR};
        mCommandQueue.abortTransient(DEFAULT_DISPLAY, types);
        waitForIdleSync();
        verify(mCallbacks).abortTransient(eq(DEFAULT_DISPLAY), eq(types));
    }

    @Test
    public void testAbortTransientForSecondaryDisplay() {
        int[] types = new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR};
        mCommandQueue.abortTransient(SECONDARY_DISPLAY, types);
        waitForIdleSync();
        verify(mCallbacks).abortTransient(eq(SECONDARY_DISPLAY), eq(types));
    }

    @Test
    public void testShowImeButton() {
        mCommandQueue.setImeWindowStatus(DEFAULT_DISPLAY, null, 1, 2, true, false);
        waitForIdleSync();
        verify(mCallbacks).setImeWindowStatus(
                eq(DEFAULT_DISPLAY), eq(null), eq(1), eq(2), eq(true));
    }

    @Test
    public void testShowImeButtonForSecondaryDisplay() {
        mCommandQueue.setImeWindowStatus(SECONDARY_DISPLAY, null, 1, 2, true, false);
        waitForIdleSync();
        verify(mCallbacks).setImeWindowStatus(
                eq(SECONDARY_DISPLAY), eq(null), eq(1), eq(2), eq(true));
    }

    @Test
    public void testShowRecentApps() {
        mCommandQueue.showRecentApps(true);
        waitForIdleSync();
        verify(mCallbacks).showRecentApps(eq(true));
    }

    @Test
    public void testHideRecentApps() {
        mCommandQueue.hideRecentApps(true, false);
        waitForIdleSync();
        verify(mCallbacks).hideRecentApps(eq(true), eq(false));
    }

    @Test
    public void testToggleRecentApps() {
        mCommandQueue.toggleRecentApps();
        waitForIdleSync();
        verify(mCallbacks).toggleRecentApps();
    }

    @Test
    public void testPreloadRecentApps() {
        mCommandQueue.preloadRecentApps();
        waitForIdleSync();
        verify(mCallbacks).preloadRecentApps();
    }

    @Test
    public void testCancelPreloadRecentApps() {
        mCommandQueue.cancelPreloadRecentApps();
        waitForIdleSync();
        verify(mCallbacks).cancelPreloadRecentApps();
    }

    @Test
    public void testDismissKeyboardShortcuts() {
        mCommandQueue.dismissKeyboardShortcutsMenu();
        waitForIdleSync();
        verify(mCallbacks).dismissKeyboardShortcutsMenu();
    }

    @Test
    public void testToggleKeyboardShortcuts() {
        mCommandQueue.toggleKeyboardShortcutsMenu(1);
        waitForIdleSync();
        verify(mCallbacks).toggleKeyboardShortcutsMenu(eq(1));
    }

    @Test
    public void testSetWindowState() {
        mCommandQueue.setWindowState(DEFAULT_DISPLAY, 1, 2);
        waitForIdleSync();
        verify(mCallbacks).setWindowState(eq(DEFAULT_DISPLAY), eq(1), eq(2));
    }

    @Test
    public void testSetWindowStateForSecondaryDisplay() {
        mCommandQueue.setWindowState(SECONDARY_DISPLAY, 1, 2);
        waitForIdleSync();
        verify(mCallbacks).setWindowState(eq(SECONDARY_DISPLAY), eq(1), eq(2));
    }

    @Test
    public void testScreenPinRequest() {
        mCommandQueue.showScreenPinningRequest(1);
        waitForIdleSync();
        verify(mCallbacks).showScreenPinningRequest(eq(1));
    }

    @Test
    public void testAppTransitionPending() {
        mCommandQueue.appTransitionPending(DEFAULT_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).appTransitionPending(eq(DEFAULT_DISPLAY), eq(false));
    }

    @Test
    public void testAppTransitionPendingForSecondaryDisplay() {
        mCommandQueue.appTransitionPending(SECONDARY_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).appTransitionPending(eq(SECONDARY_DISPLAY), eq(false));
    }

    @Test
    public void testAppTransitionCancelled() {
        mCommandQueue.appTransitionCancelled(DEFAULT_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).appTransitionCancelled(eq(DEFAULT_DISPLAY));
    }

    @Test
    public void testAppTransitionCancelledForSecondaryDisplay() {
        mCommandQueue.appTransitionCancelled(SECONDARY_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).appTransitionCancelled(eq(SECONDARY_DISPLAY));
    }

    @Test
    public void testAppTransitionStarting() {
        mCommandQueue.appTransitionStarting(DEFAULT_DISPLAY, 1, 2);
        waitForIdleSync();
        verify(mCallbacks).appTransitionStarting(
                eq(DEFAULT_DISPLAY), eq(1L), eq(2L), eq(false));
    }

    @Test
    public void testAppTransitionStartingForSecondaryDisplay() {
        mCommandQueue.appTransitionStarting(SECONDARY_DISPLAY, 1, 2);
        waitForIdleSync();
        verify(mCallbacks).appTransitionStarting(
                eq(SECONDARY_DISPLAY), eq(1L), eq(2L), eq(false));
    }

    @Test
    public void testAppTransitionFinished() {
        mCommandQueue.appTransitionFinished(DEFAULT_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).appTransitionFinished(eq(DEFAULT_DISPLAY));
    }

    @Test
    public void testAppTransitionFinishedForSecondaryDisplay() {
        mCommandQueue.appTransitionFinished(SECONDARY_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).appTransitionFinished(eq(SECONDARY_DISPLAY));
    }

    @Test
    public void testAssistDisclosure() {
        mCommandQueue.showAssistDisclosure();
        waitForIdleSync();
        verify(mCallbacks).showAssistDisclosure();
    }

    @Test
    public void testStartAssist() {
        Bundle b = new Bundle();
        mCommandQueue.startAssist(b);
        waitForIdleSync();
        verify(mCallbacks).startAssist(eq(b));
    }

    @Test
    public void testCameraLaunchGesture() {
        mCommandQueue.onCameraLaunchGestureDetected(1);
        waitForIdleSync();
        verify(mCallbacks).onCameraLaunchGestureDetected(eq(1));
    }

    @Test
    public void testShowPipMenu() {
        mCommandQueue.showPictureInPictureMenu();
        waitForIdleSync();
        verify(mCallbacks).showPictureInPictureMenu();
    }

    @Test
    public void testAddQsTile() {
        ComponentName c = new ComponentName("testpkg", "testcls");
        mCommandQueue.addQsTile(c);
        waitForIdleSync();
        verify(mCallbacks).addQsTile(eq(c));
    }

    @Test
    public void testRemoveQsTile() {
        ComponentName c = new ComponentName("testpkg", "testcls");
        mCommandQueue.remQsTile(c);
        waitForIdleSync();
        verify(mCallbacks).remQsTile(eq(c));
    }

    @Test
    public void testClickQsTile() {
        ComponentName c = new ComponentName("testpkg", "testcls");
        mCommandQueue.clickQsTile(c);
        waitForIdleSync();
        verify(mCallbacks).clickTile(eq(c));
    }

    @Test
    public void testToggleAppSplitScreen() {
        mCommandQueue.toggleSplitScreen();
        waitForIdleSync();
        verify(mCallbacks).toggleSplitScreen();
    }

    @Test
    public void testHandleSysKey() {
        mCommandQueue.handleSystemKey(1);
        waitForIdleSync();
        verify(mCallbacks).handleSystemKey(eq(1));
    }

    @Test
    public void testOnDisplayReady() {
        mCommandQueue.onDisplayReady(DEFAULT_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).onDisplayReady(eq(DEFAULT_DISPLAY));
    }

    @Test
    public void testOnDisplayReadyForSecondaryDisplay() {
        mCommandQueue.onDisplayReady(SECONDARY_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).onDisplayReady(eq(SECONDARY_DISPLAY));
    }

    @Test
    public void testOnDisplayRemoved() {
        mCommandQueue.onDisplayRemoved(SECONDARY_DISPLAY);
        waitForIdleSync();
        verify(mCallbacks).onDisplayRemoved(eq(SECONDARY_DISPLAY));
    }

    @Test
    public void testOnRecentsAnimationStateChanged() {
        mCommandQueue.onRecentsAnimationStateChanged(true);
        waitForIdleSync();
        verify(mCallbacks).onRecentsAnimationStateChanged(eq(true));
    }

    @Test
    public void testShowAuthenticationDialog() {
        PromptInfo promptInfo = new PromptInfo();
        final IBiometricSysuiReceiver receiver = mock(IBiometricSysuiReceiver.class);
        final int[] sensorIds = {1, 2};
        final boolean credentialAllowed = true;
        final boolean requireConfirmation = true;
        final int userId = 10;
        final String packageName = "test";
        final long operationId = 1;
        final int multiSensorConfig = BiometricManager.BIOMETRIC_MULTI_SENSOR_DEFAULT;

        mCommandQueue.showAuthenticationDialog(promptInfo, receiver, sensorIds,
                credentialAllowed, requireConfirmation , userId, packageName, operationId,
                multiSensorConfig);
        waitForIdleSync();
        verify(mCallbacks).showAuthenticationDialog(eq(promptInfo), eq(receiver), eq(sensorIds),
                eq(credentialAllowed), eq(requireConfirmation), eq(userId), eq(packageName),
                eq(operationId), eq(multiSensorConfig));
    }

    @Test
    public void testOnBiometricAuthenticated() {
        mCommandQueue.onBiometricAuthenticated();
        waitForIdleSync();
        verify(mCallbacks).onBiometricAuthenticated();
    }

    @Test
    public void testOnBiometricHelp() {
        final int modality = TYPE_FACE;
        final String helpMessage = "test_help_message";
        mCommandQueue.onBiometricHelp(modality, helpMessage);
        waitForIdleSync();
        verify(mCallbacks).onBiometricHelp(eq(modality), eq(helpMessage));
    }

    @Test
    public void testOnBiometricError() {
        final int modality = 1;
        final int error = 2;
        final int vendorCode = 3;
        mCommandQueue.onBiometricError(modality, error, vendorCode);
        waitForIdleSync();
        verify(mCallbacks).onBiometricError(eq(modality), eq(error), eq(vendorCode));
    }

    @Test
    public void testHideAuthenticationDialog() {
        mCommandQueue.hideAuthenticationDialog();
        waitForIdleSync();
        verify(mCallbacks).hideAuthenticationDialog();
    }

    @Test
    public void testSetUdfpsHbmListener() {
        final IUdfpsHbmListener listener = mock(IUdfpsHbmListener.class);
        mCommandQueue.setUdfpsHbmListener(listener);
        waitForIdleSync();
        verify(mCallbacks).setUdfpsHbmListener(eq(listener));
    }

    @Test
    public void testSuppressAmbientDisplay() {
        mCommandQueue.suppressAmbientDisplay(true);
        waitForIdleSync();
        verify(mCallbacks).suppressAmbientDisplay(true);
    }

    @Test
    public void testRequestWindowMagnificationConnection() {
        mCommandQueue.requestWindowMagnificationConnection(true);
        waitForIdleSync();
        verify(mCallbacks).requestWindowMagnificationConnection(true);
    }

    @Test
    public void testSetEnableNavigationBarLumaSampling() {
        mCommandQueue.setNavigationBarLumaSamplingEnabled(1, true);
        waitForIdleSync();
        verify(mCallbacks).setNavigationBarLumaSamplingEnabled(eq(1), eq(true));
    }
}
