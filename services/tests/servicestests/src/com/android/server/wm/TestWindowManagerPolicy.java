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

import com.android.internal.policy.IShortcutService;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Display;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;

import java.io.PrintWriter;

public class TestWindowManagerPolicy implements WindowManagerPolicy {

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
        return 0;
    }

    @Override
    public int subWindowTypeToLayerLw(int type) {
        return 0;
    }

    @Override
    public int getMaxWallpaperLayer() {
        return 0;
    }

    @Override
    public int getNonDecorDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode) {
        return 0;
    }

    @Override
    public int getNonDecorDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode) {
        return 0;
    }

    @Override
    public int getConfigDisplayWidth(int fullWidth, int fullHeight, int rotation, int uiMode) {
        return 0;
    }

    @Override
    public int getConfigDisplayHeight(int fullWidth, int fullHeight, int rotation, int uiMode) {
        return 0;
    }

    @Override
    public boolean isForceHiding(WindowManager.LayoutParams attrs) {
        return false;
    }

    @Override
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs) {
        return false;
    }

    @Override
    public boolean canBeForceHidden(WindowState win,
            WindowManager.LayoutParams attrs) {
        return false;
    }

    @Override
    public WindowState getWinShowWhenLockedLw() {
        return null;
    }

    @Override
    public View addStartingWindow(IBinder appToken, String packageName, int theme,
            CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes, int icon,
            int logo, int windowFlags, Configuration overrideConfig) {
        return null;
    }

    @Override
    public void removeStartingWindow(IBinder appToken, View window) {

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
    public Animation createForceHideEnterAnimation(boolean onWallpaper,
            boolean goingToNotificationShade) {
        return null;
    }

    @Override
    public Animation createForceHideWallpaperExitAnimation(boolean goingToNotificationShade) {
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
            WindowManager.LayoutParams attrs, WindowState attached) {

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
    public boolean isKeyguardShowingOrOccluded() {
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
    public void dismissKeyguardLw() {

    }

    @Override
    public void notifyActivityDrawnForKeyguardLw() {

    }

    @Override
    public boolean isKeyguardDrawnLw() {
        return false;
    }

    @Override
    public int rotationForOrientationLw(int orientation,
            int lastRotation) {
        return 0;
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
}
