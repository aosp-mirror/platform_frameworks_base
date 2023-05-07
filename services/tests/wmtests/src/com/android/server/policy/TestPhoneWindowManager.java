/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.provider.Settings.Secure.VOLUME_HUSH_MUTE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.STATE_ON;
import static android.view.WindowManagerPolicyConstants.FLAG_INTERACTIVE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyLong;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_ASSISTANT;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_GLOBAL_ACTIONS;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_GO_TO_VOICE_ASSIST;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_NOTHING;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_SHUT_OFF;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM;
import static com.android.server.policy.PhoneWindowManager.POWER_VOLUME_UP_BEHAVIOR_MUTE;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.media.AudioManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.Vibrator;
import android.service.dreams.DreamManagerInternal;
import android.telecom.TelecomManager;
import android.util.FeatureFlagUtils;
import android.view.Display;
import android.view.KeyEvent;
import android.view.autofill.AutofillManagerInternal;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.server.GestureLauncherService;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.vr.VrManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.DisplayPolicy;
import com.android.server.wm.DisplayRotation;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.AppTransitionListener;

import junit.framework.Assert;

import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockSettings;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

class TestPhoneWindowManager {
    private static final long SHORTCUT_KEY_DELAY_MILLIS = 150;

    private PhoneWindowManager mPhoneWindowManager;
    private Context mContext;

    @Mock private WindowManagerInternal mWindowManagerInternal;
    @Mock private ActivityManagerInternal mActivityManagerInternal;
    @Mock private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    @Mock private InputManagerInternal mInputManagerInternal;
    @Mock private DreamManagerInternal mDreamManagerInternal;
    @Mock private PowerManagerInternal mPowerManagerInternal;
    @Mock private DisplayManagerInternal mDisplayManagerInternal;
    @Mock private AppOpsManager mAppOpsManager;
    @Mock private DisplayManager mDisplayManager;
    @Mock private PackageManager mPackageManager;
    @Mock private TelecomManager mTelecomManager;
    @Mock private NotificationManager mNotificationManager;
    @Mock private Vibrator mVibrator;
    @Mock private PowerManager mPowerManager;
    @Mock private WindowManagerPolicy.WindowManagerFuncs mWindowManagerFuncsImpl;
    @Mock private InputMethodManagerInternal mInputMethodManagerInternal;
    @Mock private UserManagerInternal mUserManagerInternal;
    @Mock private AudioManagerInternal mAudioManagerInternal;
    @Mock private SearchManager mSearchManager;

    @Mock private Display mDisplay;
    @Mock private DisplayRotation mDisplayRotation;
    @Mock private DisplayPolicy mDisplayPolicy;
    @Mock private WindowManagerPolicy.ScreenOnListener mScreenOnListener;
    @Mock private GestureLauncherService mGestureLauncherService;
    @Mock private GlobalActions mGlobalActions;
    @Mock private AccessibilityShortcutController mAccessibilityShortcutController;

    @Mock private StatusBarManagerInternal mStatusBarManagerInternal;

    private StaticMockitoSession mMockitoSession;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private class TestInjector extends PhoneWindowManager.Injector {
        TestInjector(Context context, WindowManagerPolicy.WindowManagerFuncs funcs) {
            super(context, funcs);
        }

        AccessibilityShortcutController getAccessibilityShortcutController(
                Context context, Handler handler, int initialUserId) {
            return mAccessibilityShortcutController;
        }

        Supplier<GlobalActions> getGlobalActionsFactory() {
            return () -> mGlobalActions;
        }
    }

    TestPhoneWindowManager(Context context) {
        MockitoAnnotations.initMocks(this);
        mHandlerThread = new HandlerThread("fake window manager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mHandler.runWithScissors(()-> setUp(context),  0 /* timeout */);
    }

    private void setUp(Context context) {
        mPhoneWindowManager = spy(new PhoneWindowManager());
        mContext = spy(context);

        // Use stubOnly() to reduce memory usage if it doesn't need verification.
        final MockSettings spyStubOnly = withSettings().stubOnly()
                .defaultAnswer(CALLS_REAL_METHODS);
        // Return mocked services: LocalServices.getService
        mMockitoSession = mockitoSession()
                .mockStatic(LocalServices.class, spyStubOnly)
                .startMocking();

        doReturn(mWindowManagerInternal).when(
                () -> LocalServices.getService(eq(WindowManagerInternal.class)));
        doReturn(mActivityManagerInternal).when(
                () -> LocalServices.getService(eq(ActivityManagerInternal.class)));
        doReturn(mActivityTaskManagerInternal).when(
                () -> LocalServices.getService(eq(ActivityTaskManagerInternal.class)));
        doReturn(mInputManagerInternal).when(
                () -> LocalServices.getService(eq(InputManagerInternal.class)));
        doReturn(mDreamManagerInternal).when(
                () -> LocalServices.getService(eq(DreamManagerInternal.class)));
        doReturn(mPowerManagerInternal).when(
                () -> LocalServices.getService(eq(PowerManagerInternal.class)));
        doReturn(mDisplayManagerInternal).when(
                () -> LocalServices.getService(eq(DisplayManagerInternal.class)));
        doReturn(mGestureLauncherService).when(
                () -> LocalServices.getService(eq(GestureLauncherService.class)));
        doReturn(mUserManagerInternal).when(
                () -> LocalServices.getService(eq(UserManagerInternal.class)));
        doReturn(null).when(() -> LocalServices.getService(eq(VrManagerInternal.class)));
        doReturn(null).when(() -> LocalServices.getService(eq(AutofillManagerInternal.class)));
        LocalServices.removeServiceForTest(InputMethodManagerInternal.class);
        LocalServices.addService(InputMethodManagerInternal.class, mInputMethodManagerInternal);

        doReturn(mAppOpsManager).when(mContext).getSystemService(eq(AppOpsManager.class));
        doReturn(mDisplayManager).when(mContext).getSystemService(eq(DisplayManager.class));
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(false).when(mPackageManager).hasSystemFeature(any());
        try {
            doThrow(new PackageManager.NameNotFoundException("test")).when(mPackageManager)
                    .getActivityInfo(any(), anyInt());
            doReturn(new String[] { "testPackage" }).when(mPackageManager)
                    .canonicalToCurrentPackageNames(any());
        } catch (PackageManager.NameNotFoundException ignored) { }

        doReturn(false).when(mTelecomManager).isInCall();
        doReturn(false).when(mTelecomManager).isRinging();
        doReturn(mTelecomManager).when(mPhoneWindowManager).getTelecommService();
        doNothing().when(mNotificationManager).silenceNotificationSound();
        doReturn(mNotificationManager).when(mPhoneWindowManager).getNotificationService();
        doReturn(mVibrator).when(mContext).getSystemService(eq(Context.VIBRATOR_SERVICE));

        final PowerManager.WakeLock wakeLock = mock(PowerManager.WakeLock.class);
        doReturn(wakeLock).when(mPowerManager).newWakeLock(anyInt(), anyString());
        doReturn(mPowerManager).when(mContext).getSystemService(eq(Context.POWER_SERVICE));
        doReturn(true).when(mPowerManager).isInteractive();

        doReturn(mDisplay).when(mDisplayManager).getDisplay(eq(DEFAULT_DISPLAY));
        doReturn(STATE_ON).when(mDisplay).getState();
        doReturn(true).when(mDisplayPolicy).isAwake();
        doNothing().when(mDisplayPolicy).takeScreenshot(anyInt(), anyInt());
        doReturn(mDisplayPolicy).when(mDisplayRotation).getDisplayPolicy();
        doReturn(mScreenOnListener).when(mDisplayPolicy).getScreenOnListener();
        mPhoneWindowManager.setDefaultDisplay(new WindowManagerPolicy.DisplayContentInfo() {
            @Override
            public DisplayRotation getDisplayRotation() {
                return mDisplayRotation;
            }
            @Override
            public Display getDisplay() {
                return mDisplay;
            }
        });

        doNothing().when(mPhoneWindowManager).initializeHdmiState();
        doNothing().when(mPhoneWindowManager).updateSettings();
        doNothing().when(mPhoneWindowManager).screenTurningOn(anyInt(), any());
        doNothing().when(mPhoneWindowManager).screenTurnedOn(anyInt());
        doNothing().when(mPhoneWindowManager).startedWakingUp(anyInt(), anyInt());
        doNothing().when(mPhoneWindowManager).finishedWakingUp(anyInt(), anyInt());
        doNothing().when(mPhoneWindowManager).lockNow(any());

        mPhoneWindowManager.init(new TestInjector(mContext, mWindowManagerFuncsImpl));
        mPhoneWindowManager.systemReady();
        mPhoneWindowManager.systemBooted();

        overrideLaunchAccessibility();
        doReturn(false).when(mPhoneWindowManager).keyguardOn();
        doNothing().when(mContext).startActivityAsUser(any(), any());
        Mockito.reset(mContext);
    }

    void tearDown() {
        mHandlerThread.quitSafely();
        LocalServices.removeServiceForTest(InputMethodManagerInternal.class);
        Mockito.reset(mPhoneWindowManager);
        mMockitoSession.finishMocking();
    }

    // Override accessibility setting and perform function.
    private void overrideLaunchAccessibility() {
        doReturn(true).when(mAccessibilityShortcutController)
                .isAccessibilityShortcutAvailable(anyBoolean());
        doNothing().when(mAccessibilityShortcutController).performAccessibilityShortcut();
    }

    int interceptKeyBeforeQueueing(KeyEvent event) {
        return mPhoneWindowManager.interceptKeyBeforeQueueing(event, FLAG_INTERACTIVE);
    }

    long interceptKeyBeforeDispatching(KeyEvent event) {
        return mPhoneWindowManager.interceptKeyBeforeDispatching(null /*focusedToken*/,
                event, FLAG_INTERACTIVE);
    }

    void dispatchUnhandledKey(KeyEvent event) {
        mPhoneWindowManager.dispatchUnhandledKey(null /*focusedToken*/, event, FLAG_INTERACTIVE);
    }

    void waitForIdle() {
        mHandler.runWithScissors(() -> { }, 0 /* timeout */);
    }

    /**
     * Below functions will override the setting or the policy behavior.
     */
    void overridePowerVolumeUp(int behavior) {
        mPhoneWindowManager.mPowerVolUpBehavior = behavior;

        // override mRingerToggleChord as mute so we could trigger the behavior.
        if (behavior == POWER_VOLUME_UP_BEHAVIOR_MUTE) {
            mPhoneWindowManager.mRingerToggleChord = VOLUME_HUSH_MUTE;
            doReturn(mAudioManagerInternal).when(
                    () -> LocalServices.getService(eq(AudioManagerInternal.class)));
        }
    }

    void overrideShortPressOnPower(int behavior) {
        mPhoneWindowManager.mShortPressOnPowerBehavior = behavior;
    }

     // Override assist perform function.
    void overrideLongPressOnPower(int behavior) {
        mPhoneWindowManager.mLongPressOnPowerBehavior = behavior;

        switch (behavior) {
            case LONG_PRESS_POWER_NOTHING:
            case LONG_PRESS_POWER_GLOBAL_ACTIONS:
            case LONG_PRESS_POWER_SHUT_OFF:
            case LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM:
            case LONG_PRESS_POWER_GO_TO_VOICE_ASSIST:
                break;
            case LONG_PRESS_POWER_ASSISTANT:
                doNothing().when(mPhoneWindowManager).sendCloseSystemWindows();
                doReturn(true).when(mPhoneWindowManager).isUserSetupComplete();
                doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());
                doReturn(mSearchManager).when(mContext)
                        .getSystemService(eq(Context.SEARCH_SERVICE));
                mPhoneWindowManager.mLongPressOnPowerAssistantTimeoutMs = 500;
                break;
        }
    }

    void overrideCanStartDreaming(boolean canDream) {
        doReturn(canDream).when(mDreamManagerInternal).canStartDreaming(anyBoolean());
    }

    void overrideDisplayState(int state) {
        doReturn(state).when(mDisplay).getState();
        doReturn(state == STATE_ON).when(mDisplayPolicy).isAwake();
        Mockito.reset(mPowerManager);
    }

    void overrideIncallPowerBehavior(int behavior) {
        mPhoneWindowManager.mIncallPowerBehavior = behavior;
        setPhoneCallIsInProgress();
    }

    void prepareBrightnessDecrease(float currentBrightness) {
        doReturn(0.0f).when(mPowerManager)
                .getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM);
        doReturn(1.0f).when(mPowerManager)
                .getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM);
        doReturn(currentBrightness).when(mDisplayManager)
                .getBrightness(0);
    }

    void verifyNewBrightness(float newBrightness) {
        verify(mDisplayManager).setBrightness(Mockito.eq(0),
                AdditionalMatchers.eq(newBrightness, 0.001f));
    }

    void setPhoneCallIsInProgress() {
        // Let device has an ongoing phone call.
        doReturn(false).when(mTelecomManager).isRinging();
        doReturn(true).when(mTelecomManager).isInCall();
        doReturn(true).when(mTelecomManager).endCall();
    }

    void overrideTogglePanel() {
        // Can't directly mock on IStatusbarService, use spyOn and override the specific api.
        mPhoneWindowManager.getStatusBarService();
        spyOn(mPhoneWindowManager.mStatusBarService);
        try {
            doNothing().when(mPhoneWindowManager.mStatusBarService).togglePanel();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    void overrideStatusBarManagerInternal() {
        doReturn(mStatusBarManagerInternal).when(
                () -> LocalServices.getService(eq(StatusBarManagerInternal.class)));
    }

    void overrideLaunchHome() {
        doNothing().when(mPhoneWindowManager).launchHomeFromHotKey(anyInt());
    }

    /**
     * Below functions will check the policy behavior could be invoked.
     */
    void assertTakeScreenshotCalled() {
        waitForIdle();
        verify(mDisplayPolicy, timeout(SHORTCUT_KEY_DELAY_MILLIS))
                .takeScreenshot(anyInt(), anyInt());
    }

    void assertShowGlobalActionsCalled() {
        waitForIdle();
        verify(mPhoneWindowManager).showGlobalActions();
        verify(mGlobalActions, timeout(SHORTCUT_KEY_DELAY_MILLIS))
                .showDialog(anyBoolean(), anyBoolean());
        verify(mPowerManager, timeout(SHORTCUT_KEY_DELAY_MILLIS))
                .userActivity(anyLong(), anyBoolean());
    }

    void assertVolumeMute() {
        waitForIdle();
        verify(mAudioManagerInternal, timeout(SHORTCUT_KEY_DELAY_MILLIS))
                .silenceRingerModeInternal(eq("volume_hush"));
    }

    void assertAccessibilityKeychordCalled() {
        waitForIdle();
        verify(mAccessibilityShortcutController,
                timeout(SHORTCUT_KEY_DELAY_MILLIS)).performAccessibilityShortcut();
    }

    void assertDreamRequest() {
        waitForIdle();
        verify(mDreamManagerInternal).requestDream();
    }

    void assertPowerSleep() {
        waitForIdle();
        verify(mPowerManager,
                timeout(SHORTCUT_KEY_DELAY_MILLIS)).goToSleep(anyLong(), anyInt(), anyInt());
    }

    void assertPowerWakeUp() {
        waitForIdle();
        verify(mPowerManager,
                timeout(SHORTCUT_KEY_DELAY_MILLIS)).wakeUp(anyLong(), anyInt(), anyString());
    }

    void assertNoPowerSleep() {
        waitForIdle();
        verify(mPowerManager, never()).goToSleep(anyLong(), anyInt(), anyInt());
    }

    void assertCameraLaunch() {
        waitForIdle();
        // GestureLauncherService should receive interceptPowerKeyDown twice.
        verify(mGestureLauncherService, times(2))
                .interceptPowerKeyDown(any(), anyBoolean(), any());
    }

    void assertAssistLaunch() {
        waitForIdle();
        verify(mSearchManager, timeout(SHORTCUT_KEY_DELAY_MILLIS)).launchAssist(any());
    }

    void assertLaunchCategory(String category) {
        waitForIdle();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        try {
            verify(mContext).startActivityAsUser(intentCaptor.capture(), any());
            Assert.assertTrue(intentCaptor.getValue().getSelector().hasCategory(category));
        } catch (Throwable t) {
            throw new AssertionError("failed to assert " + category, t);
        }
        // Reset verifier for next call.
        Mockito.reset(mContext);
    }

    void assertShowRecentApps() {
        waitForIdle();
        verify(mStatusBarManagerInternal).showRecentApps(anyBoolean());
    }

    void assertSwitchKeyboardLayout(int direction) {
        waitForIdle();
        if (FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SETTINGS_NEW_KEYBOARD_UI)) {
            verify(mInputMethodManagerInternal).switchKeyboardLayout(eq(direction));
            verify(mWindowManagerFuncsImpl, never()).switchKeyboardLayout(anyInt(), anyInt());
        } else {
            verify(mWindowManagerFuncsImpl).switchKeyboardLayout(anyInt(), eq(direction));
            verify(mInputMethodManagerInternal, never()).switchKeyboardLayout(anyInt());
        }
    }

    void assertTakeBugreport() {
        waitForIdle();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendOrderedBroadcastAsUser(intentCaptor.capture(), any(), any(), any(),
                any(), anyInt(), any(), any());
        Assert.assertTrue(intentCaptor.getValue().getAction() == Intent.ACTION_BUG_REPORT);
    }

    void assertTogglePanel() throws RemoteException {
        waitForIdle();
        verify(mPhoneWindowManager.mStatusBarService).togglePanel();
    }

    void assertToggleShortcutsMenu() {
        waitForIdle();
        verify(mStatusBarManagerInternal).toggleKeyboardShortcutsMenu(anyInt());
    }

    void assertToggleCapsLock() {
        waitForIdle();
        verify(mInputManagerInternal).toggleCapsLock(anyInt());
    }

    void assertWillNotLockAfterAppTransitionFinished() {
        Assert.assertFalse(mPhoneWindowManager.mLockAfterAppTransitionFinished);
    }

    void assertLockedAfterAppTransitionFinished() {
        ArgumentCaptor<AppTransitionListener> transitionCaptor =
                ArgumentCaptor.forClass(AppTransitionListener.class);
        verify(mWindowManagerInternal).registerAppTransitionListener(
                transitionCaptor.capture());
        transitionCaptor.getValue().onAppTransitionFinishedLocked(any());
        verify(mPhoneWindowManager).lockNow(null);
    }

    void assertGoToHomescreen() {
        waitForIdle();
        verify(mPhoneWindowManager).launchHomeFromHotKey(anyInt());
    }
}
