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

import static android.os.Build.HW_TIMEOUT_MULTIPLIER;
import static android.provider.Settings.Secure.VOLUME_HUSH_MUTE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.STATE_ON;
import static android.view.WindowManagerPolicyConstants.FLAG_INTERACTIVE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyLong;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.description;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_ASSISTANT;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_GLOBAL_ACTIONS;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_GO_TO_VOICE_ASSIST;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_NOTHING;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_SHUT_OFF;
import static com.android.server.policy.PhoneWindowManager.LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM;
import static com.android.server.policy.PhoneWindowManager.POWER_VOLUME_UP_BEHAVIOR_MUTE;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.withSettings;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.NotificationManager;
import android.app.SearchManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.media.AudioManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.os.test.TestLooper;
import android.service.dreams.DreamManagerInternal;
import android.telecom.TelecomManager;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillManagerInternal;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.policy.KeyInterceptionInfo;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.GestureLauncherService;
import com.android.server.LocalServices;
import com.android.server.input.InputManagerInternal;
import com.android.server.input.KeyboardMetricsCollector.KeyboardLogEvent;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.policy.keyguard.KeyguardServiceDelegate;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.testutils.OffsettableClock;
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
import org.mockito.quality.Strictness;

import java.util.function.Supplier;

class TestPhoneWindowManager {
    private static final long TEST_SINGLE_KEY_DELAY_MILLIS
            = SingleKeyGestureDetector.MULTI_PRESS_TIMEOUT + 1000L * HW_TIMEOUT_MULTIPLIER;
    private static final String TEST_BROWSER_ROLE_PACKAGE_NAME = "com.browser";
    private static final String TEST_SMS_ROLE_PACKAGE_NAME = "com.sms";

    private PhoneWindowManager mPhoneWindowManager;
    private Context mContext;

    @Mock private WindowManagerInternal mWindowManagerInternal;
    @Mock private ActivityManagerInternal mActivityManagerInternal;
    @Mock ActivityTaskManagerInternal mActivityTaskManagerInternal;
    @Mock IActivityManager mActivityManagerService;
    @Mock private InputManagerInternal mInputManagerInternal;
    @Mock private InputManager mInputManager;
    @Mock private SensorPrivacyManager mSensorPrivacyManager;
    @Mock private DreamManagerInternal mDreamManagerInternal;
    @Mock private PowerManagerInternal mPowerManagerInternal;
    @Mock private DisplayManagerInternal mDisplayManagerInternal;
    @Mock private AppOpsManager mAppOpsManager;
    @Mock private DisplayManager mDisplayManager;
    @Mock private PackageManager mPackageManager;
    @Mock private TelecomManager mTelecomManager;
    @Mock private NotificationManager mNotificationManager;
    @Mock private Vibrator mVibrator;
    @Mock private VibratorInfo mVibratorInfo;
    @Mock private PowerManager mPowerManager;
    @Mock private WindowManagerPolicy.WindowManagerFuncs mWindowManagerFuncsImpl;
    @Mock private InputMethodManagerInternal mInputMethodManagerInternal;
    @Mock private UserManagerInternal mUserManagerInternal;
    @Mock private AudioManagerInternal mAudioManagerInternal;
    @Mock private SearchManager mSearchManager;
    @Mock private RoleManager mRoleManager;
    @Mock private AccessibilityManager mAccessibilityManager;

    @Mock private Display mDisplay;
    @Mock private DisplayRotation mDisplayRotation;
    @Mock private DisplayPolicy mDisplayPolicy;
    @Mock private WindowManagerPolicy.ScreenOnListener mScreenOnListener;
    @Mock private GestureLauncherService mGestureLauncherService;
    @Mock private GlobalActions mGlobalActions;
    @Mock private AccessibilityShortcutController mAccessibilityShortcutController;

    @Mock private StatusBarManagerInternal mStatusBarManagerInternal;

    @Mock private KeyguardServiceDelegate mKeyguardServiceDelegate;

    @Mock
    private PhoneWindowManager.ButtonOverridePermissionChecker mButtonOverridePermissionChecker;
    @Mock private WindowWakeUpPolicy mWindowWakeUpPolicy;

    @Mock private IBinder mInputToken;
    @Mock private IBinder mImeTargetWindowToken;

    private StaticMockitoSession mMockitoSession;
    private OffsettableClock mClock = new OffsettableClock();
    private TestLooper mTestLooper = new TestLooper(() -> mClock.now());
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private boolean mIsTalkBackEnabled;
    private boolean mIsTalkBackShortcutGestureEnabled;

    private Intent mBrowserIntent;
    private Intent mSmsIntent;

    private int mKeyEventPolicyFlags = FLAG_INTERACTIVE;

    private class TestTalkbackShortcutController extends TalkbackShortcutController {
        TestTalkbackShortcutController(Context context) {
            super(context);
        }

        @Override
        boolean toggleTalkback(int currentUserId) {
            mIsTalkBackEnabled = !mIsTalkBackEnabled;
            return mIsTalkBackEnabled;
        }

        @Override
        boolean isTalkBackShortcutGestureEnabled() {
            return mIsTalkBackShortcutGestureEnabled;
        }
    }

    private class TestInjector extends PhoneWindowManager.Injector {
        TestInjector(Context context, WindowManagerPolicy.WindowManagerFuncs funcs) {
            super(context, funcs);
        }

        @Override
        Looper getLooper() {
            return mTestLooper.getLooper();
        }

        AccessibilityShortcutController getAccessibilityShortcutController(
                Context context, Handler handler, int initialUserId) {
            return mAccessibilityShortcutController;
        }

        Supplier<GlobalActions> getGlobalActionsFactory() {
            return () -> mGlobalActions;
        }

        KeyguardServiceDelegate getKeyguardServiceDelegate() {
            return mKeyguardServiceDelegate;
        }

        IActivityManager getActivityManagerService() {
            return mActivityManagerService;
        }

        PhoneWindowManager.ButtonOverridePermissionChecker getButtonOverridePermissionChecker() {
            return mButtonOverridePermissionChecker;
        }

        TalkbackShortcutController getTalkbackShortcutController() {
            return new TestTalkbackShortcutController(mContext);
        }

        WindowWakeUpPolicy getWindowWakeUpPolicy() {
            return mWindowWakeUpPolicy;
        }
    }

    /**
     * {@link TestPhoneWindowManager}'s constructor.
     *
     * @param context The {@Context} to be used in any Context-related actions.
     * @param supportSettingsUpdate {@code true} if this object should read and listen to provider
     *      settings values.
     */
    TestPhoneWindowManager(Context context, boolean supportSettingsUpdate) {
        MockitoAnnotations.initMocks(this);
        mHandler = new Handler(mTestLooper.getLooper());
        mContext = mockingDetails(context).isSpy() ? context : spy(context);
        setUp(supportSettingsUpdate);
        mTestLooper.dispatchAll();
    }

    private void setUp(boolean supportSettingsUpdate) {
        // Use stubOnly() to reduce memory usage if it doesn't need verification.
        final MockSettings spyStubOnly = withSettings().stubOnly()
                .defaultAnswer(CALLS_REAL_METHODS);
        // Return mocked services: LocalServices.getService
        mMockitoSession = mockitoSession()
                .mockStatic(LocalServices.class, spyStubOnly)
                .mockStatic(FrameworkStatsLog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mPhoneWindowManager = spy(new PhoneWindowManager());

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
        doReturn(mInputManager).when(mContext).getSystemService(eq(InputManager.class));
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(mSensorPrivacyManager).when(mContext).getSystemService(
                eq(SensorPrivacyManager.class));
        doReturn(mSearchManager).when(mContext).getSystemService(eq(SearchManager.class));
        doReturn(mRoleManager).when(mContext).getSystemService(eq(RoleManager.class));
        doReturn(mAccessibilityManager).when(mContext).getSystemService(
                eq(AccessibilityManager.class));
        doReturn(false).when(mAccessibilityManager).isEnabled();
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
        doReturn(mVibratorInfo).when(mVibrator).getInfo();
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
        if (supportSettingsUpdate) {
            doAnswer(inv -> {
                // Make any call to updateSettings run synchronously for tests.
                mPhoneWindowManager.updateSettings(null);
                return null;
            }).when(mPhoneWindowManager).updateSettings(any(Handler.class));
        } else {
            doNothing().when(mPhoneWindowManager).updateSettings(any());
        }
        doNothing().when(mPhoneWindowManager).screenTurningOn(anyInt(), any());
        doNothing().when(mPhoneWindowManager).screenTurnedOn(anyInt());
        doNothing().when(mPhoneWindowManager).startedWakingUp(anyInt(), anyInt());
        doNothing().when(mPhoneWindowManager).finishedWakingUp(anyInt(), anyInt());
        doNothing().when(mPhoneWindowManager).lockNow(any());

        doReturn(mImeTargetWindowToken)
                .when(mWindowManagerInternal).getTargetWindowTokenFromInputToken(mInputToken);

        mPhoneWindowManager.init(new TestInjector(mContext, mWindowManagerFuncsImpl));
        mPhoneWindowManager.systemReady();
        mPhoneWindowManager.systemBooted();

        overrideLaunchAccessibility();
        doReturn(false).when(mPhoneWindowManager).keyguardOn();
        doNothing().when(mContext).startActivityAsUser(any(), any());
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        KeyInterceptionInfo interceptionInfo = new KeyInterceptionInfo(0, 0, null, 0);
        doReturn(interceptionInfo)
                .when(mWindowManagerInternal).getKeyInterceptionInfoFromToken(any());

        doReturn(true).when(mRoleManager).isRoleAvailable(eq(RoleManager.ROLE_BROWSER));
        doReturn(true).when(mRoleManager).isRoleAvailable(eq(RoleManager.ROLE_SMS));
        doReturn(TEST_BROWSER_ROLE_PACKAGE_NAME).when(mRoleManager).getDefaultApplication(
                eq(RoleManager.ROLE_BROWSER));
        doReturn(TEST_SMS_ROLE_PACKAGE_NAME).when(mRoleManager).getDefaultApplication(
                eq(RoleManager.ROLE_SMS));
        mBrowserIntent = new Intent(Intent.ACTION_MAIN);
        mBrowserIntent.setPackage(TEST_BROWSER_ROLE_PACKAGE_NAME);
        mSmsIntent = new Intent(Intent.ACTION_MAIN);
        mSmsIntent.setPackage(TEST_SMS_ROLE_PACKAGE_NAME);
        doReturn(mBrowserIntent).when(mPackageManager).getLaunchIntentForPackage(
                eq(TEST_BROWSER_ROLE_PACKAGE_NAME));
        doReturn(mSmsIntent).when(mPackageManager).getLaunchIntentForPackage(
                eq(TEST_SMS_ROLE_PACKAGE_NAME));

        Mockito.reset(mContext);
    }

    void tearDown() {
        LocalServices.removeServiceForTest(InputMethodManagerInternal.class);
        Mockito.reset(mPhoneWindowManager);
        mMockitoSession.finishMocking();
    }

    void dispatchAllPendingEvents() {
        mTestLooper.dispatchAll();
    }

    // Override accessibility setting and perform function.
    private void overrideLaunchAccessibility() {
        doReturn(true).when(mAccessibilityShortcutController)
                .isAccessibilityShortcutAvailable(anyBoolean());
        doNothing().when(mAccessibilityShortcutController).performAccessibilityShortcut();
    }

    int interceptKeyBeforeQueueing(KeyEvent event) {
        return mPhoneWindowManager.interceptKeyBeforeQueueing(event, mKeyEventPolicyFlags);
    }

    long interceptKeyBeforeDispatching(KeyEvent event) {
        return mPhoneWindowManager.interceptKeyBeforeDispatching(mInputToken, event,
                mKeyEventPolicyFlags);
    }

    void dispatchUnhandledKey(KeyEvent event) {
        mPhoneWindowManager.dispatchUnhandledKey(mInputToken, event, FLAG_INTERACTIVE);
    }

    /**
     * Provide access to the SettingsObserver so that tests can manually trigger Settings changes.
     */
    ContentObserver getSettingsObserver() {
        return mPhoneWindowManager.mSettingsObserver;
    }

    long getCurrentTime() {
        return mClock.now();
    }

    void moveTimeForward(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
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

    void overrideShouldEarlyShortPressOnStemPrimary(boolean shouldEarlyShortPress) {
        mPhoneWindowManager.mShouldEarlyShortPressOnStemPrimary = shouldEarlyShortPress;
    }

    void overrideTalkbackShortcutGestureEnabled(boolean enabled) {
        mIsTalkBackShortcutGestureEnabled = enabled;
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
                setupAssistForLaunch();
                mPhoneWindowManager.mLongPressOnPowerAssistantTimeoutMs = 500;
                break;
        }
    }

    void overrideLongPressOnHomeBehavior(int behavior) {
        mPhoneWindowManager.mLongPressOnHomeBehavior = behavior;
    }

    void overriderDoubleTapOnHomeBehavior(int behavior) {
        mPhoneWindowManager.mDoubleTapOnHomeBehavior = behavior;
    }

    void overrideSettingsKeyBehavior(int behavior) {
        mPhoneWindowManager.mSettingsKeyBehavior = behavior;
    }

    void overrideCanStartDreaming(boolean canDream) {
        doReturn(canDream).when(mDreamManagerInternal).canStartDreaming(anyBoolean());
    }

    void overrideIsDreaming(boolean isDreaming) {
        doReturn(isDreaming).when(mDreamManagerInternal).isDreaming();
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

    void overrideIsUserSetupComplete(boolean isCompleted) {
        doReturn(isCompleted).when(mPhoneWindowManager).isUserSetupComplete();
    }

    void setKeyguardServiceDelegateIsShowing(boolean isShowing) {
        doReturn(isShowing).when(mKeyguardServiceDelegate).isShowing();
    }

    void setupAssistForLaunch() {
        doNothing().when(mPhoneWindowManager).sendCloseSystemWindows();
        doReturn(true).when(mPhoneWindowManager).isUserSetupComplete();
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());
        doReturn(mSearchManager).when(mContext).getSystemService(eq(SearchManager.class));
    }

    void overrideSearchManager(SearchManager searchManager) {
        doReturn(searchManager).when(mContext).getSystemService(eq(SearchManager.class));
    }

    void assumeResolveActivityNotNull() {
        ResolveInfo resolveInfo = new ResolveInfo();
        doReturn(resolveInfo).when(mPackageManager).resolveActivity(any(), anyInt());
        doReturn(mPackageManager).when(mContext).getPackageManager();
    }

    void overrideKeyEventSource(int vendorId, int productId, int deviceBus) {
        InputDevice device = new InputDevice.Builder()
                .setId(1)
                .setVendorId(vendorId)
                .setProductId(productId)
                .setDeviceBus(deviceBus)
                .setSources(InputDevice.SOURCE_KEYBOARD)
                .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                .build();
        doReturn(mInputManager).when(mContext).getSystemService(eq(InputManager.class));
        doReturn(device).when(mInputManager).getInputDevice(anyInt());
    }

    void overrideInjectKeyEvent() {
        doReturn(true).when(mInputManager).injectInputEvent(any(KeyEvent.class), anyInt());
    }

    void overrideSearchKeyBehavior(int behavior) {
        mPhoneWindowManager.mSearchKeyBehavior = behavior;
    }

    void overrideEnableBugReportTrigger(boolean enable) {
        mPhoneWindowManager.mEnableBugReportKeyboardShortcut = enable;
    }

    void overrideStartActivity() {
        doNothing().when(mContext).startActivityAsUser(any(), any());
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());
    }

    void overrideSendBroadcast() {
        doNothing().when(mContext).sendBroadcastAsUser(any(), any(), any());
    }

    void overrideUserSetupComplete() {
        doReturn(true).when(mPhoneWindowManager).isUserSetupComplete();
    }

    void overrideStemPressTargetActivity(ComponentName component) {
        mPhoneWindowManager.mPrimaryShortPressTargetActivity = component;
    }

    void overrideFocusedWindowButtonOverridePermission(boolean granted) {
        doReturn(granted)
                .when(mButtonOverridePermissionChecker).canAppOverrideSystemKey(any(), anyInt());
    }

    void overrideKeyEventPolicyFlags(int flags) {
        mKeyEventPolicyFlags = flags;
    }

    /**
     * Below functions will check the policy behavior could be invoked.
     */
    void assertTakeScreenshotCalled() {
        mTestLooper.dispatchAll();
        verify(mDisplayPolicy).takeScreenshot(anyInt(), anyInt());
    }

    void assertTakeScreenshotNotCalled() {
        mTestLooper.dispatchAll();
        verify(mDisplayPolicy, never()).takeScreenshot(anyInt(), anyInt());
    }

    void assertShowGlobalActionsCalled() {
        mTestLooper.dispatchAll();
        verify(mPhoneWindowManager).showGlobalActions();
        verify(mGlobalActions).showDialog(anyBoolean(), anyBoolean());
        verify(mPowerManager).userActivity(anyLong(), anyBoolean());
    }

    void assertVolumeMute() {
        mTestLooper.dispatchAll();
        verify(mAudioManagerInternal).silenceRingerModeInternal(eq("volume_hush"));
    }

    void assertAccessibilityKeychordCalled() {
        mTestLooper.dispatchAll();
        verify(mAccessibilityShortcutController).performAccessibilityShortcut();
    }

    void assertDreamRequest() {
        mTestLooper.dispatchAll();
        verify(mDreamManagerInternal).requestDream();
    }

    void assertPowerSleep() {
        mTestLooper.dispatchAll();
        verify(mPowerManager).goToSleep(anyLong(), anyInt(), anyInt());
    }

    void assertPowerWakeUp() {
        mTestLooper.dispatchAll();
        verify(mWindowWakeUpPolicy)
                .wakeUpFromKey(anyLong(), eq(KeyEvent.KEYCODE_POWER), anyBoolean());
    }

    void assertNoPowerSleep() {
        mTestLooper.dispatchAll();
        verify(mPowerManager, never()).goToSleep(anyLong(), anyInt(), anyInt());
    }

    void assertCameraLaunch() {
        mTestLooper.dispatchAll();
        // GestureLauncherService should receive interceptPowerKeyDown twice.
        verify(mGestureLauncherService, times(2))
                .interceptPowerKeyDown(any(), anyBoolean(), any());
    }

    void assertSearchManagerLaunchAssist() {
        mTestLooper.dispatchAll();
        verify(mSearchManager).launchAssist(any());
    }

    void assertLaunchCategory(String category) {
        mTestLooper.dispatchAll();
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

    void assertLaunchRole(String role) {
        mTestLooper.dispatchAll();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        try {
            verify(mContext).startActivityAsUser(intentCaptor.capture(), any());
            switch (role) {
                case RoleManager.ROLE_BROWSER:
                    Assert.assertEquals(intentCaptor.getValue(), mBrowserIntent);
                    break;
                case RoleManager.ROLE_SMS:
                    Assert.assertEquals(intentCaptor.getValue(), mSmsIntent);
                    break;
                default:
                    throw new AssertionError("Role " + role + " not supported in tests.");
            }
        } catch (Throwable t) {
            throw new AssertionError("failed to assert " + role, t);
        }
        // Reset verifier for next call.
        Mockito.reset(mContext);
    }


    void assertShowRecentApps() {
        mTestLooper.dispatchAll();
        verify(mStatusBarManagerInternal).showRecentApps(anyBoolean());
    }

    void assertStatusBarStartAssist() {
        mTestLooper.dispatchAll();
        verify(mStatusBarManagerInternal).startAssist(any());
    }

    void assertSwitchKeyboardLayout(int direction, int displayId) {
        mTestLooper.dispatchAll();
        verify(mInputMethodManagerInternal).onSwitchKeyboardLayoutShortcut(eq(direction),
                eq(displayId), eq(mImeTargetWindowToken));
    }

    void assertTakeBugreport(boolean wasCalled) throws RemoteException {
        mTestLooper.dispatchAll();
        if (wasCalled) {
            verify(mActivityManagerService).requestInteractiveBugReport();
        } else {
            verify(mActivityManagerService, never()).requestInteractiveBugReport();
        }

    }

    void assertTogglePanel() throws RemoteException {
        mTestLooper.dispatchAll();
        verify(mPhoneWindowManager.mStatusBarService).togglePanel();
    }

    void assertToggleShortcutsMenu() {
        mTestLooper.dispatchAll();
        verify(mStatusBarManagerInternal).toggleKeyboardShortcutsMenu(anyInt());
    }

    void assertToggleCapsLock() {
        mTestLooper.dispatchAll();
        verify(mInputManagerInternal).toggleCapsLock(anyInt());
    }

    void assertLockedAfterAppTransitionFinished() {
        ArgumentCaptor<AppTransitionListener> transitionCaptor =
                ArgumentCaptor.forClass(AppTransitionListener.class);
        verify(mWindowManagerInternal).registerAppTransitionListener(
                transitionCaptor.capture());
        final IBinder token = mock(IBinder.class);
        transitionCaptor.getValue().onAppTransitionFinishedLocked(token);
        verify(mPhoneWindowManager).lockNow(null);
    }

    void assertDidNotLockAfterAppTransitionFinished() {
        ArgumentCaptor<AppTransitionListener> transitionCaptor =
                ArgumentCaptor.forClass(AppTransitionListener.class);
        verify(mWindowManagerInternal).registerAppTransitionListener(
                transitionCaptor.capture());
        final IBinder token = mock(IBinder.class);
        transitionCaptor.getValue().onAppTransitionFinishedLocked(token);
        verify(mPhoneWindowManager, never()).lockNow(null);
    }

    void assertGoToHomescreen() {
        mTestLooper.dispatchAll();
        verify(mPhoneWindowManager).launchHomeFromHotKey(anyInt());
    }

    void assertOpenAllAppView() {
        moveTimeForward(TEST_SINGLE_KEY_DELAY_MILLIS);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, timeout(TEST_SINGLE_KEY_DELAY_MILLIS))
                .startActivityAsUser(intentCaptor.capture(), isNull(), any(UserHandle.class));
        Assert.assertEquals(Intent.ACTION_ALL_APPS, intentCaptor.getValue().getAction());
    }

    void assertNotOpenAllAppView() {
        mTestLooper.dispatchAll();
        verify(mContext, after(TEST_SINGLE_KEY_DELAY_MILLIS).never())
                .startActivityAsUser(any(Intent.class), any(), any(UserHandle.class));
    }

    void assertActivityTargetLaunched(ComponentName targetActivity) {
        moveTimeForward(TEST_SINGLE_KEY_DELAY_MILLIS);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, timeout(TEST_SINGLE_KEY_DELAY_MILLIS))
                .startActivityAsUser(intentCaptor.capture(), isNull(), any(UserHandle.class));
        Assert.assertEquals(targetActivity, intentCaptor.getValue().getComponent());
    }

    void assertShortcutLogged(int vendorId, int productId, KeyboardLogEvent logEvent,
            int expectedKey, int expectedModifierState, int deviceBus, String errorMsg) {
        mTestLooper.dispatchAll();
        verify(() -> FrameworkStatsLog.write(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED,
                        vendorId, productId, logEvent.getIntValue(), new int[]{expectedKey},
                        expectedModifierState, deviceBus), description(errorMsg));
    }

    void assertSwitchToTask(int persistentId) throws RemoteException {
        mTestLooper.dispatchAll();
        verify(mActivityManagerService,
                timeout(TEST_SINGLE_KEY_DELAY_MILLIS)).startActivityFromRecents(eq(persistentId),
                isNull());
    }

    void assertTalkBack(boolean expectEnabled) {
        mTestLooper.dispatchAll();
        Assert.assertEquals(expectEnabled, mIsTalkBackEnabled);
    }
}
