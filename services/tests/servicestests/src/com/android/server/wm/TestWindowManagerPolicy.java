/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_BOOT_PROGRESS;
import static android.view.WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_DRAG;
import static android.view.WindowManager.LayoutParams.TYPE_DREAM;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_POINTER;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_QS_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static org.mockito.Mockito.mock;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.os.PowerManagerInternal;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.server.input.InputManagerService;
import com.android.server.LocalServices;

import java.io.PrintWriter;

class TestWindowManagerPolicy implements WindowManagerPolicy {
    private static final String TAG = "TestWindowManagerPolicy";

    private static WindowManagerService sWm = null;

    int rotationToReport = 0;

    static synchronized WindowManagerService getWindowManagerService(Context context) {
        if (sWm == null) {
            // We only want to do this once for the test process as we don't want WM to try to
            // register a bunch of local services again.
            if (LocalServices.getService(DisplayManagerInternal.class) == null) {
                LocalServices.addService(DisplayManagerInternal.class,
                        mock(DisplayManagerInternal.class));
            }
            if (LocalServices.getService(PowerManagerInternal.class) == null) {
                LocalServices.addService(PowerManagerInternal.class,
                        mock(PowerManagerInternal.class));
            }
            sWm = WindowManagerService.main(context, mock(InputManagerService.class), true, false,
                    false, new TestWindowManagerPolicy());
        }
        return sWm;
    }

    @Override
    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutKeyReceiver)
            throws RemoteException {

    }

    @Override
    public void init(Context context, IWindowManager windowManager,
            WindowManagerFuncs windowManagerFuncs) {

    }

    @Override
    public boolean isDefaultOrientationForced() {
        return false;
    }

    @Override
    public void setInitialDisplaySize(Display display, int width, int height, int density) {

    }

    @Override
    public void setDisplayOverscan(Display display, int left, int top, int right, int bottom) {

    }

    @Override
    public int checkAddPermission(WindowManager.LayoutParams attrs, int[] outAppOp) {
        return 0;
    }

    @Override
    public boolean checkShowToOwnerOnly(WindowManager.LayoutParams attrs) {
        return false;
    }

    @Override
    public void adjustWindowParamsLw(WindowManager.LayoutParams attrs) {

    }

    @Override
    public void adjustConfigurationLw(Configuration config, int keyboardPresence,
            int navigationPresence) {

    }

    @Override
    public int windowTypeToLayerLw(int type) {
        // TODO: figure-out a good way to keep this in-sync with PhoneWindowManager...sigh!
        if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            return 2;
        }
        switch (type) {
            case TYPE_PRESENTATION:
            case TYPE_PRIVATE_PRESENTATION:
                return 2;
            case TYPE_WALLPAPER:
                // wallpaper is at the bottom, though the window manager may move it.
                return 2;
            case TYPE_DOCK_DIVIDER:
                return 2;
            case TYPE_QS_DIALOG:
                return 2;
            case TYPE_PHONE:
                return 3;
            case TYPE_SEARCH_BAR:
            case TYPE_VOICE_INTERACTION_STARTING:
                return 4;
            case TYPE_VOICE_INTERACTION:
                // voice interaction layer is almost immediately above apps.
                return 5;
            case TYPE_INPUT_CONSUMER:
                return 6;
            case TYPE_SYSTEM_DIALOG:
                return 7;
            case TYPE_TOAST:
                // toasts and the plugged-in battery thing
                return 8;
            case TYPE_PRIORITY_PHONE:
                // SIM errors and unlock.  Not sure if this really should be in a high layer.
                return 9;
            case TYPE_DREAM:
                // used for Dreams (screensavers with TYPE_DREAM windows)
                return 10;
            case TYPE_SYSTEM_ALERT:
                // like the ANR / app crashed dialogs
                return 11;
            case TYPE_INPUT_METHOD:
                // on-screen keyboards and other such input method user interfaces go here.
                return 12;
            case TYPE_INPUT_METHOD_DIALOG:
                // on-screen keyboards and other such input method user interfaces go here.
                return 13;
            case TYPE_STATUS_BAR_SUB_PANEL:
                return 15;
            case TYPE_STATUS_BAR:
                return 16;
            case TYPE_STATUS_BAR_PANEL:
                return 17;
            case TYPE_KEYGUARD_DIALOG:
                return 18;
            case TYPE_VOLUME_OVERLAY:
                // the on-screen volume indicator and controller shown when the user
                // changes the device volume
                return 19;
            case TYPE_SYSTEM_OVERLAY:
                // the on-screen volume indicator and controller shown when the user
                // changes the device volume
                return 20;
            case TYPE_NAVIGATION_BAR:
                // the navigation bar, if available, shows atop most things
                return 21;
            case TYPE_NAVIGATION_BAR_PANEL:
                // some panels (e.g. search) need to show on top of the navigation bar
                return 22;
            case TYPE_SCREENSHOT:
                // screenshot selection layer shouldn't go above system error, but it should cover
                // navigation bars at the very least.
                return 23;
            case TYPE_SYSTEM_ERROR:
                // system-level error dialogs
                return 24;
            case TYPE_MAGNIFICATION_OVERLAY:
                // used to highlight the magnified portion of a display
                return 25;
            case TYPE_DISPLAY_OVERLAY:
                // used to simulate secondary display devices
                return 26;
            case TYPE_DRAG:
                // the drag layer: input for drag-and-drop is associated with this window,
                // which sits above all other focusable windows
                return 27;
            case TYPE_ACCESSIBILITY_OVERLAY:
                // overlay put by accessibility services to intercept user interaction
                return 28;
            case TYPE_SECURE_SYSTEM_OVERLAY:
                return 29;
            case TYPE_BOOT_PROGRESS:
                return 30;
            case TYPE_POINTER:
                // the (mouse) pointer layer
                return 31;
        }
        Log.e(TAG, "Unknown window type: " + type);
        return 2;
    }

    @Override
    public int subWindowTypeToLayerLw(int type) {
        // TODO: figure-out a good way to keep this in-sync with PhoneWindowManager...
        switch (type) {
            case TYPE_APPLICATION_PANEL:
            case TYPE_APPLICATION_ATTACHED_DIALOG:
                return 1;
            case TYPE_APPLICATION_MEDIA:
                return -2;
            case TYPE_APPLICATION_MEDIA_OVERLAY:
                return -1;
            case TYPE_APPLICATION_SUB_PANEL:
                return 2;
            case TYPE_APPLICATION_ABOVE_SUB_PANEL:
                return 3;
        }
        Log.e(TAG, "Unknown sub-window type: " + type);
        return 0;
    }

    @Override
    public int getMaxWallpaperLayer() {
        return 0;
    }

    @Override
    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode,
            int displayId) {
        return 0;
    }

    @Override
    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode,
            int displayId) {
        return 0;
    }

    @Override
    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode,
            int displayId) {
        return 0;
    }

    @Override
    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode,
            int displayId) {
        return 0;
    }

    @Override
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs) {
        return false;
    }

    @Override
    public boolean canBeHiddenByKeyguardLw(WindowState win) {
        return false;
    }

    @Override
    public StartingSurface addSplashScreen(IBinder appToken, String packageName, int theme,
            CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes, int icon,
            int logo, int windowFlags, Configuration overrideConfig, int displayId) {
        return null;
    }

    @Override
    public int prepareAddWindowLw(WindowState win,
            WindowManager.LayoutParams attrs) {
        return 0;
    }

    @Override
    public void removeWindowLw(WindowState win) {

    }

    @Override
    public int selectAnimationLw(WindowState win, int transit) {
        return 0;
    }

    @Override
    public void selectRotationAnimationLw(int[] anim) {

    }

    @Override
    public boolean validateRotationAnimationLw(int exitAnimId, int enterAnimId,
            boolean forceDefault) {
        return false;
    }

    @Override
    public Animation createHiddenByKeyguardExit(boolean onWallpaper,
            boolean goingToNotificationShade) {
        return null;
    }

    @Override
    public Animation createKeyguardWallpaperExit(boolean goingToNotificationShade) {
        return null;
    }

    @Override
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        return 0;
    }

    @Override
    public int interceptMotionBeforeQueueingNonInteractive(long whenNanos, int policyFlags) {
        return 0;
    }

    @Override
    public long interceptKeyBeforeDispatching(WindowState win, KeyEvent event,
            int policyFlags) {
        return 0;
    }

    @Override
    public KeyEvent dispatchUnhandledKey(WindowState win, KeyEvent event,
            int policyFlags) {
        return null;
    }

    @Override
    public void beginLayoutLw(boolean isDefaultDisplay, int displayWidth, int displayHeight,
            int displayRotation, int uiMode) {

    }

    @Override
    public int getSystemDecorLayerLw() {
        return 0;
    }

    @Override
    public void getContentRectLw(Rect r) {

    }

    @Override
    public void layoutWindowLw(WindowState win,
            WindowState attached) {

    }

    @Override
    public boolean getInsetHintLw(WindowManager.LayoutParams attrs, Rect taskBounds,
            int displayRotation, int displayWidth, int displayHeight, Rect outContentInsets,
            Rect outStableInsets, Rect outOutsets) {
        return false;
    }

    @Override
    public void finishLayoutLw() {

    }

    @Override
    public void beginPostLayoutPolicyLw(int displayWidth, int displayHeight) {

    }

    @Override
    public void applyPostLayoutPolicyLw(WindowState win,
            WindowManager.LayoutParams attrs, WindowState attached, WindowState imeTarget) {
    }

    @Override
    public int finishPostLayoutPolicyLw() {
        return 0;
    }

    @Override
    public boolean allowAppAnimationsLw() {
        return false;
    }

    @Override
    public int focusChangedLw(WindowState lastFocus,
            WindowState newFocus) {
        return 0;
    }

    @Override
    public void startedWakingUp() {

    }

    @Override
    public void finishedWakingUp() {

    }

    @Override
    public void startedGoingToSleep(int why) {

    }

    @Override
    public void finishedGoingToSleep(int why) {

    }

    @Override
    public void screenTurningOn(ScreenOnListener screenOnListener) {

    }

    @Override
    public void screenTurnedOn() {

    }

    @Override
    public void screenTurnedOff() {

    }

    @Override
    public boolean isScreenOn() {
        return false;
    }

    @Override
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {

    }

    @Override
    public void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered) {

    }

    @Override
    public void enableKeyguard(boolean enabled) {

    }

    @Override
    public void exitKeyguardSecurely(OnKeyguardExitResult callback) {

    }

    @Override
    public boolean isKeyguardLocked() {
        return false;
    }

    @Override
    public boolean isKeyguardSecure(int userId) {
        return false;
    }

    @Override
    public boolean isKeyguardOccluded() {
        return false;
    }

    @Override
    public boolean isKeyguardTrustedLw() {
        return false;
    }

    @Override
    public boolean isKeyguardShowingAndNotOccluded() {
        return false;
    }

    @Override
    public boolean inKeyguardRestrictedKeyInputMode() {
        return false;
    }

    @Override
    public void dismissKeyguardLw(@Nullable IKeyguardDismissCallback callback) {
    }

    @Override
    public boolean isKeyguardDrawnLw() {
        return false;
    }

    @Override
    public boolean isShowingDreamLw() {
        return false;
    }

    @Override
    public void onKeyguardOccludedChangedLw(boolean occluded) {
    }

    @Override
    public int rotationForOrientationLw(int orientation,
            int lastRotation) {
        return rotationToReport;
    }

    @Override
    public boolean rotationHasCompatibleMetricsLw(int orientation,
            int rotation) {
        return false;
    }

    @Override
    public void setRotationLw(int rotation) {

    }

    @Override
    public void setSafeMode(boolean safeMode) {

    }

    @Override
    public void systemReady() {

    }

    @Override
    public void systemBooted() {

    }

    @Override
    public void showBootMessage(CharSequence msg, boolean always) {

    }

    @Override
    public void hideBootMessages() {

    }

    @Override
    public void userActivity() {

    }

    @Override
    public void enableScreenAfterBoot() {

    }

    @Override
    public void setCurrentOrientationLw(int newOrientation) {

    }

    @Override
    public boolean performHapticFeedbackLw(WindowState win, int effectId,
            boolean always) {
        return false;
    }

    @Override
    public void keepScreenOnStartedLw() {

    }

    @Override
    public void keepScreenOnStoppedLw() {

    }

    @Override
    public int getUserRotationMode() {
        return 0;
    }

    @Override
    public void setUserRotationMode(int mode,
            int rotation) {

    }

    @Override
    public int adjustSystemUiVisibilityLw(int visibility) {
        return 0;
    }

    @Override
    public boolean hasNavigationBar() {
        return false;
    }

    @Override
    public void lockNow(Bundle options) {

    }

    @Override
    public void setLastInputMethodWindowLw(WindowState ime,
            WindowState target) {

    }

    @Override
    public void showRecentApps(boolean fromHome) {

    }

    @Override
    public void showGlobalActions() {

    }

    @Override
    public int getInputMethodWindowVisibleHeightLw() {
        return 0;
    }

    @Override
    public void setCurrentUserLw(int newUserId) {

    }

    @Override
    public void setSwitchingUser(boolean switching) {

    }

    @Override
    public void dump(String prefix, PrintWriter writer, String[] args) {

    }

    @Override
    public boolean canMagnifyWindow(int windowType) {
        return false;
    }

    @Override
    public boolean isTopLevelWindow(int windowType) {
        return false;
    }

    @Override
    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {

    }

    @Override
    public void getStableInsetsLw(int displayRotation, int displayWidth, int displayHeight,
            Rect outInsets) {

    }

    @Override
    public boolean isNavBarForcedShownLw(WindowState win) {
        return false;
    }

    @Override
    public void getNonDecorInsetsLw(int displayRotation, int displayWidth, int displayHeight,
            Rect outInsets) {

    }

    @Override
    public boolean isDockSideAllowed(int dockSide) {
        return false;
    }

    @Override
    public void onConfigurationChanged() {

    }

    @Override
    public boolean shouldRotateSeamlessly(int oldRotation, int newRotation) {
        return false;
    }

    @Override
    public void setTvPipVisibilityLw(boolean visible) {

    }

    @Override
    public void setRecentsVisibilityLw(boolean visible) {

   }
}
