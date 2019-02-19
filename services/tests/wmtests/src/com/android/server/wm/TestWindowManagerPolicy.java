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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager.WakeReason;
import android.os.RemoteException;
import android.util.proto.ProtoOutputStream;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.animation.Animation;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.function.Supplier;

class TestWindowManagerPolicy implements WindowManagerPolicy {
    private final Supplier<WindowManagerService> mWmSupplier;

    int mRotationToReport = 0;
    boolean mKeyguardShowingAndNotOccluded = false;

    private Runnable mRunnableWhenAddingSplashScreen;

    TestWindowManagerPolicy(Supplier<WindowManagerService> wmSupplier) {
        mWmSupplier = wmSupplier;
    }

    @Override
    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutKeyReceiver)
            throws RemoteException {
    }

    @Override
    public void init(Context context, IWindowManager windowManager,
            WindowManagerFuncs windowManagerFuncs) {
    }

    public void setDefaultDisplay(DisplayContentInfo displayContentInfo) {
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
    public void adjustConfigurationLw(Configuration config, int keyboardPresence,
            int navigationPresence) {
    }

    @Override
    public int getMaxWallpaperLayer() {
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
        final WindowManagerService wm = mWmSupplier.get();
        synchronized (wm.mGlobalLock) {
            atoken = wm.mRoot.getAppWindowToken(appToken);
            IWindow iWindow = mock(IWindow.class);
            doReturn(mock(IBinder.class)).when(iWindow).asBinder();
            window = WindowTestsBase.createWindow(null, TYPE_APPLICATION_STARTING, atoken,
                    "Starting window", 0 /* ownerId */, false /* internalWindows */, wm,
                    mock(Session.class), iWindow);
            atoken.startingWindow = window;
        }
        if (mRunnableWhenAddingSplashScreen != null) {
            mRunnableWhenAddingSplashScreen.run();
            mRunnableWhenAddingSplashScreen = null;
        }
        return () -> {
            synchronized (wm.mGlobalLock) {
                atoken.removeChild(window);
                atoken.startingWindow = null;
            }
        };
    }

    @Override
    public void setKeyguardCandidateLw(WindowState win) {
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
    public int interceptMotionBeforeQueueingNonInteractive(int displayId, long whenNanos,
            int policyFlags) {
        return 0;
    }

    @Override
    public long interceptKeyBeforeDispatching(WindowState win, KeyEvent event, int policyFlags) {
        return 0;
    }

    @Override
    public KeyEvent dispatchUnhandledKey(WindowState win, KeyEvent event, int policyFlags) {
        return null;
    }

    @Override
    public void setTopFocusedDisplay(int displayId) {
    }

    @Override
    public void applyKeyguardPolicyLw(WindowState win, WindowState imeTarget) {
    }

    @Override
    public void setAllowLockscreenWhenOn(int displayId, boolean allow) {
    }

    @Override
    public void startedWakingUp(@WakeReason int reason) {
    }

    @Override
    public void finishedWakingUp(@WakeReason int reason) {
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
        return mKeyguardShowingAndNotOccluded;
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
        return mKeyguardShowingAndNotOccluded;
    }

    @Override
    public boolean inKeyguardRestrictedKeyInputMode() {
        return false;
    }

    @Override
    public void dismissKeyguardLw(@Nullable IKeyguardDismissCallback callback,
            CharSequence message) {
    }

    @Override
    public boolean isKeyguardDrawnLw() {
        return false;
    }

    @Override
    public void onKeyguardOccludedChangedLw(boolean occluded) {
    }

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
    public boolean performHapticFeedback(int uid, String packageName, int effectId,
            boolean always, String reason) {
        return false;
    }

    @Override
    public void keepScreenOnStartedLw() {
    }

    @Override
    public void keepScreenOnStoppedLw() {
    }

    @Override
    public boolean hasNavigationBar() {
        return false;
    }

    @Override
    public void lockNow(Bundle options) {
    }

    @Override
    public void showRecentApps() {
    }

    @Override
    public void showGlobalActions() {
    }

    @Override
    public boolean isUserSetupComplete() {
        return false;
    }

    @Override
    public int getUiMode() {
        return 0;
    }

    @Override
    public void setCurrentUserLw(int newUserId) {
    }

    @Override
    public void setSwitchingUser(boolean switching) {
    }

    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
    }

    @Override
    public void dump(String prefix, PrintWriter writer, String[] args) {
    }

    @Override
    public boolean isTopLevelWindow(int windowType) {
        return false;
    }

    @Override
    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
    }

    @Override
    public void setPipVisibilityLw(boolean visible) {
    }

    @Override
    public void setRecentsVisibilityLw(boolean visible) {
    }

    @Override
    public void setNavBarVirtualKeyHapticFeedbackEnabledLw(boolean enabled) {
    }

    @Override
    public void onSystemUiStarted() {
    }

    @Override
    public boolean canDismissBootAnimation() {
        return true;
    }

    @Override
    public void requestUserActivityNotification() {
    }

    @Override
    public boolean setAodShowing(boolean aodShowing) {
        return false;
    }
}
