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

import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager.GoToSleepReason;
import android.os.PowerManager.WakeReason;
import android.util.proto.ProtoOutputStream;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.animation.Animation;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;

class TestWindowManagerPolicy implements WindowManagerPolicy {

    boolean mKeyguardShowingAndNotOccluded = false;
    boolean mOkToAnimate = true;
    boolean mIsUserSetupComplete = false;

    TestWindowManagerPolicy() {
    }

    @Override
    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutKeyReceiver) {
    }

    @Override
    public void init(Context context, WindowManagerFuncs windowManagerFuncs) {
    }

    public void setDefaultDisplay(DisplayContentInfo displayContentInfo) {
    }

    @Override
    public int checkAddPermission(int type, boolean isRoundedCornerOverlay, String packageName,
            int[] outAppOp) {
        return 0;
    }

    @Override
    public void adjustConfigurationLw(Configuration config, int keyboardPresence,
            int navigationPresence) {
    }

    @Override
    public boolean isKeyguardHostWindow(WindowManager.LayoutParams attrs) {
        return attrs.type == TYPE_NOTIFICATION_SHADE;
    }

    @Override
    public Animation createHiddenByKeyguardExit(boolean onWallpaper,
            boolean goingToNotificationShade, boolean subtleAnimation) {
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
    public long interceptKeyBeforeDispatching(IBinder focusedToken, KeyEvent event,
            int policyFlags) {
        return 0;
    }

    @Override
    public KeyEvent dispatchUnhandledKey(IBinder focusedToken, KeyEvent event, int policyFlags) {
        return null;
    }

    @Override
    public void setTopFocusedDisplay(int displayId) {
    }

    @Override
    public void setAllowLockscreenWhenOn(int displayId, boolean allow) {
    }

    @Override
    public void startedWakingUp(@WakeReason int wakeReason) {
    }

    @Override
    public void finishedWakingUp(@WakeReason int wakeReason) {
    }

    @Override
    public void startedGoingToSleep(@GoToSleepReason int sleepReason) {
    }

    @Override
    public void finishedGoingToSleep(@GoToSleepReason int sleepReason) {
    }

    @Override
    public void onPowerGroupWakefulnessChanged(int groupId, int wakefulness,
            @GoToSleepReason int pmSleepReason, int globalWakefulness) {
    }

    @Override
    public void screenTurningOn(int displayId, ScreenOnListener screenOnListener) {
    }

    @Override
    public void screenTurnedOn(int displayId) {
    }

    @Override
    public void screenTurningOff(int displayId, ScreenOffListener screenOffListener) {
    }

    @Override
    public void screenTurnedOff(int displayId) {
    }

    @Override
    public boolean isScreenOn() {
        return true;
    }

    @Override
    public boolean okToAnimate(boolean ignoreScreenOn) {
        return mOkToAnimate;
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
    public boolean isKeyguardShowing() {
        return mKeyguardShowingAndNotOccluded;
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
    public void userActivity(int displayGroupId, int event) {
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
        return mIsUserSetupComplete;
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
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
    }

    @Override
    public void dump(String prefix, PrintWriter writer, String[] args) {
    }

    @Override
    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
    }

    @Override
    public int applyKeyguardOcclusionChange(boolean keyguardOccludingStarted) {
        return 0;
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
}
