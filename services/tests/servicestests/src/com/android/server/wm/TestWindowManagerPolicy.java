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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;

import android.os.PowerSaveState;
import org.mockito.invocation.InvocationOnMock;

import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Display;
import android.view.IWindowManager;
import android.view.InputChannel;
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
    boolean keyguardShowingAndNotOccluded = false;

    private Runnable mRunnableWhenAddingSplashScreen;

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
                final PowerManagerInternal pm =
                        LocalServices.getService(PowerManagerInternal.class);
                PowerSaveState state = new PowerSaveState.Builder().build();
                doReturn(state).when(pm).getLowPowerState(anyInt());
            }
            if (LocalServices.getService(ActivityManagerInternal.class) == null) {
                LocalServices.addService(ActivityManagerInternal.class,
                        mock(ActivityManagerInternal.class));
                final ActivityManagerInternal am =
                        LocalServices.getService(ActivityManagerInternal.class);
                doAnswer((InvocationOnMock invocationOnMock) -> {
                    final Runnable runnable = invocationOnMock.<Runnable>getArgument(0);
                    if (runnable != null) {
                        runnable.run();
                    }
                    return null;
                }).when(am).notifyKeyguardFlagsChanged(any());
            }

            InputManagerService ims = mock(InputManagerService.class);
            // InputChannel is final and can't be mocked.
            InputChannel[] input = InputChannel.openInputChannelPair(TAG_WM);
            if (input != null && input.length > 1) {
                doReturn(input[1]).when(ims).monitorInput(anyString());
            }

            sWm = WindowManagerService.main(context, ims, true, false,
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
        return attrs.type == TYPE_STATUS_BAR;
    }

    @Override
    public boolean canBeHiddenByKeyguardLw(WindowState win) {
        return false;
    }

    /**
     * Sets a runnable to run when adding a splash screen which gets executed after the window has
     * been added but before returning the surface.
     */
    void setRunnableWhenAddingSplashScreen(Runnable r) {
        mRunnableWhenAddingSplashScreen = r;
    }

    @Override
    public StartingSurface addSplashScreen(IBinder appToken, String packageName, int theme,
            CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes, int icon,
            int logo, int windowFlags, Configuration overrideConfig, int displayId) {
        final com.android.server.wm.WindowState window;
        final AppWindowToken atoken;
        synchronized (sWm.mWindowMap) {
            atoken = sWm.mRoot.getAppWindowToken(appToken);
            window = WindowTestsBase.createWindow(null, TYPE_APPLICATION_STARTING, atoken,
                    "Starting window");
            atoken.startingWindow = window;
        }
        if (mRunnableWhenAddingSplashScreen != null) {
            mRunnableWhenAddingSplashScreen.run();
            mRunnableWhenAddingSplashScreen = null;
        }
        return () -> {
            synchronized (sWm.mWindowMap) {
                atoken.removeChild(window);
                atoken.startingWindow = null;
            }
        };
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
    public void screenTurningOff(ScreenOffListener screenOffListener) {

    }

    @Override
    public void screenTurnedOff() {

    }

    @Override
    public boolean isScreenOn() {
        return true;
    }

    @Override
    public boolean okToAnimate() {
        return true;
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
        return keyguardShowingAndNotOccluded;
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
        return keyguardShowingAndNotOccluded;
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
    public boolean rotationHasCompatibleMetricsLw(int orientation, int rotation) {
        return true;
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
    public int getNavBarPosition() {
        return NAV_BAR_BOTTOM;
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
    public void setPipVisibilityLw(boolean visible) {

    }

    @Override
    public void setRecentsVisibilityLw(boolean visible) {

    }

    @Override
    public void onSystemUiStarted() {
    }

    @Override
    public boolean canDismissBootAnimation() {
        return true;
    }
}
