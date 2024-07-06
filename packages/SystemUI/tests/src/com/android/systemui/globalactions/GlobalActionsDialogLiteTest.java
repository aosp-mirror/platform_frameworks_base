/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.globalactions;

import static android.content.pm.UserInfo.FLAG_ADMIN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Handler;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.testing.TestableLooper;
import android.view.GestureDetector;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.globalactions.domain.interactor.GlobalActionsInteractor;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.RingerModeLiveData;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.settings.FakeGlobalSettings;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class GlobalActionsDialogLiteTest extends SysuiTestCase {
    private GlobalActionsDialogLite mGlobalActionsDialogLite;

    @Mock private GlobalActions.GlobalActionsManager mWindowManagerFuncs;
    @Mock private AudioManager mAudioManager;
    @Mock private IDreamManager mDreamManager;
    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private TelephonyListenerManager mTelephonyListenerManager;
    private GlobalSettings mGlobalSettings;
    private SecureSettings mSecureSettings;
    @Mock private Resources mResources;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private ActivityStarter mActivityStarter;
    @Mock private UserTracker mUserTracker;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private UserManager mUserManager;
    @Mock private TrustManager mTrustManager;
    @Mock private IActivityManager mActivityManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private SysuiColorExtractor mColorExtractor;
    @Mock private IStatusBarService mStatusBarService;
    @Mock private LightBarController mLightBarController;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private StatusBarWindowController mStatusBarWindowController;
    @Mock private IWindowManager mWindowManager;
    @Mock private Executor mBackgroundExecutor;
    @Mock private UiEventLogger mUiEventLogger;
    @Mock private RingerModeTracker mRingerModeTracker;
    @Mock private RingerModeLiveData mRingerModeLiveData;
    @Mock private PackageManager mPackageManager;
    @Mock private Handler mHandler;
    @Mock private UserContextProvider mUserContextProvider;
    @Mock private VibratorHelper mVibratorHelper;
    @Mock private ShadeController mShadeController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock private SelectedUserInteractor mSelectedUserInteractor;
    @Mock private OnBackInvokedDispatcher mOnBackInvokedDispatcher;
    @Captor private ArgumentCaptor<OnBackInvokedCallback> mOnBackInvokedCallback;

    private TestableLooper mTestableLooper;
    private KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    private GlobalActionsInteractor mInteractor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();

        when(mRingerModeTracker.getRingerMode()).thenReturn(mRingerModeLiveData);
        when(mUserContextProvider.getUserContext()).thenReturn(mContext);
        when(mResources.getConfiguration()).thenReturn(
                getContext().getResources().getConfiguration());

        mGlobalSettings = new FakeGlobalSettings();
        mSecureSettings = new FakeSettings();
        mInteractor = mKosmos.getGlobalActionsInteractor();

        mGlobalActionsDialogLite = new GlobalActionsDialogLite(mContext,
                mWindowManagerFuncs,
                mAudioManager,
                mDreamManager,
                mDevicePolicyManager,
                mLockPatternUtils,
                mBroadcastDispatcher,
                mTelephonyListenerManager,
                mGlobalSettings,
                mSecureSettings,
                mVibratorHelper,
                mResources,
                mConfigurationController,
                mActivityStarter,
                mUserTracker,
                mKeyguardStateController,
                mUserManager,
                mTrustManager,
                mActivityManager,
                null,
                mMetricsLogger,
                mColorExtractor,
                mStatusBarService,
                mLightBarController,
                mNotificationShadeWindowController,
                mStatusBarWindowController,
                mWindowManager,
                mBackgroundExecutor,
                mUiEventLogger,
                mRingerModeTracker,
                mHandler,
                mPackageManager,
                mShadeController,
                mKeyguardUpdateMonitor,
                mDialogTransitionAnimator,
                mSelectedUserInteractor,
                mInteractor);
        mGlobalActionsDialogLite.setZeroDialogPressDelayForTesting();

        ColorExtractor.GradientColors backdropColors = new ColorExtractor.GradientColors();
        backdropColors.setMainColor(Color.BLACK);
        when(mColorExtractor.getNeutralColors()).thenReturn(backdropColors);
        when(mSelectedUserInteractor.getSelectedUserId()).thenReturn(0);
    }

    @Test
    public void testShouldLogShow() {
        mGlobalActionsDialogLite.onShow(null);
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_POWER_MENU_OPEN);
    }

    @Test
    public void testShouldLogDismiss() {
        mGlobalActionsDialogLite.onDismiss(mGlobalActionsDialogLite.mDialog);
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_POWER_MENU_CLOSE);
    }

    @Test
    public void testShouldLogClose_backButton() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();
        dialog.onBackPressed();
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_CLOSE_BACK);
    }

    @Test
    public void testPredictiveBackCallbackRegisteredAndUnregistered() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();

        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();
        dialog.setBackDispatcherOverride(mOnBackInvokedDispatcher);
        dialog.create();
        mTestableLooper.processAllMessages();
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT), any());
        dialog.onDetachedFromWindow();
        mTestableLooper.processAllMessages();
        verify(mOnBackInvokedDispatcher).unregisterOnBackInvokedCallback(any());
    }

    /**
     * This specific test case appears to be flaky.
     * b/249136797 tracks the task of root-causing and fixing it.
     */
    @FlakyTest
    @Test
    public void testPredictiveBackInvocationDismissesDialog() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();

        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();
        dialog.create();
        dialog.show();
        mTestableLooper.processAllMessages();
        dialog.getWindow().injectInputEvent(
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        dialog.getWindow().injectInputEvent(
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_CLOSE_BACK);
        assertThat(dialog.isShowing()).isFalse();
    }

    @Test
    public void testSingleTap_logAndDismiss() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();

        GestureDetector.SimpleOnGestureListener gestureListener = spy(dialog.mGestureListener);
        gestureListener.onSingleTapUp(null);
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE);
    }

    @Test
    public void testSwipeDownLockscreen_logAndOpenQS() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        doReturn(true).when(mKeyguardStateController).isShowing();
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();

        GestureDetector.SimpleOnGestureListener gestureListener = spy(dialog.mGestureListener);
        MotionEvent start = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent end = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 500, 0);
        gestureListener.onFling(start, end, 0, 1000);
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE);
        verify(mShadeController).animateExpandQs();
    }

    @Test
    public void testSwipeDown_logAndOpenNotificationShade() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        doReturn(false).when(mKeyguardStateController).isShowing();
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();

        GestureDetector.SimpleOnGestureListener gestureListener = spy(dialog.mGestureListener);
        MotionEvent start = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent end = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 500, 0);
        gestureListener.onFling(start, end, 0, 1000);
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE);
        verify(mShadeController).animateExpandShade();
    }

    @Test
    public void testSwipeDown_pastStatusBarHeight_shadeNotOpened() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        doReturn(false).when(mKeyguardStateController).isShowing();
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();

        doReturn(100).when(mStatusBarWindowController).getStatusBarHeight();

        GestureDetector.SimpleOnGestureListener gestureListener = spy(dialog.mGestureListener);
        // WHEN the start y is larger than the status bar height
        MotionEvent start = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 200, 0);
        MotionEvent end = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 500, 0);
        gestureListener.onFling(start, end, 0, 1000);

        // THEN the shade isn't opened
        verify(mShadeController, never()).animateExpandShade();
    }

    @Test
    public void testShouldLogBugreportPress() throws InterruptedException {
        GlobalActionsDialogLite.BugReportAction bugReportAction =
                mGlobalActionsDialogLite.makeBugReportActionForTesting();
        bugReportAction.onPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_BUGREPORT_PRESS);
    }

    @Test
    public void testShouldLogBugreportLongPress() {
        GlobalActionsDialogLite.BugReportAction bugReportAction =
                mGlobalActionsDialogLite.makeBugReportActionForTesting();
        bugReportAction.onLongPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_BUGREPORT_LONG_PRESS);
    }

    @Test
    public void testShouldLogEmergencyDialerPress() {
        GlobalActionsDialogLite.EmergencyDialerAction emergencyDialerAction =
                mGlobalActionsDialogLite.makeEmergencyDialerActionForTesting();
        emergencyDialerAction.onPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_EMERGENCY_DIALER_PRESS);
    }

    @Test
    public void testShouldLogScreenshotPress() {
        GlobalActionsDialogLite.ScreenshotAction screenshotAction =
                mGlobalActionsDialogLite.makeScreenshotActionForTesting();
        screenshotAction.onPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_SCREENSHOT_PRESS);
    }

    @Test
    public void testShouldShowScreenshot() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.integer.config_navBarInteractionMode,
                WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON);

        GlobalActionsDialogLite.ScreenshotAction screenshotAction =
                mGlobalActionsDialogLite.makeScreenshotActionForTesting();
        assertThat(screenshotAction.shouldShow()).isTrue();
    }

    @Test
    public void testShouldNotShowScreenshot() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.integer.config_navBarInteractionMode,
                WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON);

        GlobalActionsDialogLite.ScreenshotAction screenshotAction =
                mGlobalActionsDialogLite.makeScreenshotActionForTesting();
        assertThat(screenshotAction.shouldShow()).isFalse();
    }

    private void verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent event) {
        mTestableLooper.processAllMessages();
        verify(mUiEventLogger, times(1))
                .log(event);
    }

    @SafeVarargs
    private static <T> void assertItemsOfType(List<T> stuff, Class<? extends T>... classes) {
        assertThat(stuff).hasSize(classes.length);
        for (int i = 0; i < stuff.size(); i++) {
            assertThat(stuff.get(i)).isInstanceOf(classes[i]);
        }
    }

    private static <T> void assertNoItemsOfType(List<T> stuff, Class<? extends T> klass) {
        for (int i = 0; i < stuff.size(); i++) {
            assertThat(stuff.get(i)).isNotInstanceOf(klass);
        }
    }

    private static <T> void assertOneItemOfType(List<T> stuff, Class<? extends T> klass) {
        List<?> classes = stuff.stream().map((item) -> item.getClass()).toList();
        assertThat(classes).containsNoDuplicates();
        assertThat(classes).contains(klass);
    }

    @Test
    public void testCreateActionItems_lockdownEnabled_doesShowLockdown() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayEmergency();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        mGlobalActionsDialogLite.createActionItems();

        assertItemsOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialogLite.EmergencyAction.class,
                GlobalActionsDialogLite.LockDownAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class);
        assertThat(mGlobalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(mGlobalActionsDialogLite.mPowerItems).isEmpty();
    }

    @Test
    public void testCreateActionItems_lockdownDisabled_doesNotShowLockdown() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayEmergency();
        // make sure lockdown action will NOT be shown
        doReturn(false).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                // lockdown action not allowed
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        mGlobalActionsDialogLite.createActionItems();

        assertItemsOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialogLite.EmergencyAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class);
        assertThat(mGlobalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(mGlobalActionsDialogLite.mPowerItems).isEmpty();
    }

    @Test
    public void testCreateActionItems_emergencyDisabled_doesNotShowEmergency() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        // make sure emergency action will NOT be shown
        doReturn(false).when(mGlobalActionsDialogLite).shouldDisplayEmergency();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                // emergency action not allowed
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        mGlobalActionsDialogLite.createActionItems();

        assertItemsOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialogLite.LockDownAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class);
        assertThat(mGlobalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(mGlobalActionsDialogLite.mPowerItems).isEmpty();
    }

    @Test
    public void testShouldLogLockdownPress() {
        GlobalActionsDialogLite.LockDownAction lockDownAction =
                mGlobalActionsDialogLite.new LockDownAction();
        lockDownAction.onPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_LOCKDOWN_PRESS);
    }

    @Test
    public void testShouldLogShutdownPress() {
        GlobalActionsDialogLite.ShutDownAction shutDownAction =
                mGlobalActionsDialogLite.new ShutDownAction();
        shutDownAction.onPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_SHUTDOWN_PRESS);
    }

    @Test
    public void testShouldLogShutdownLongPress() {
        GlobalActionsDialogLite.ShutDownAction shutDownAction =
                mGlobalActionsDialogLite.new ShutDownAction();
        shutDownAction.onLongPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_SHUTDOWN_LONG_PRESS);
    }

    @Test
    public void testShouldLogRebootPress() {
        GlobalActionsDialogLite.RestartAction restartAction =
                mGlobalActionsDialogLite.new RestartAction();
        restartAction.onPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_REBOOT_PRESS);
    }

    @Test
    public void testShouldLogRebootLongPress() {
        GlobalActionsDialogLite.RestartAction restartAction =
                mGlobalActionsDialogLite.new RestartAction();
        restartAction.onLongPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_REBOOT_LONG_PRESS);
    }

    @Test
    public void testOnLockScreen_disableSmartLock() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        int expectedUser = 100;
        doReturn(expectedUser).when(mSelectedUserInteractor).getSelectedUserId();
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        doReturn(false).when(mKeyguardStateController).isShowing();
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();

        // When entering power menu from lockscreen, with smart lock enabled
        when(mKeyguardUpdateMonitor.getUserHasTrust(anyInt())).thenReturn(true);
        mGlobalActionsDialogLite.showOrHideDialog(true, true, null /* view */);

        // Then smart lock will be disabled
        verify(mLockPatternUtils).requireCredentialEntry(eq(expectedUser));

        // hide dialog again
        mGlobalActionsDialogLite.showOrHideDialog(true, true, null /* view */);
    }

    @Test
    public void testBugreportAction_whenDebugMode_shouldOfferBugreportButtonBeforeProvisioning() {
        UserInfo currentUser = mockCurrentUser(FLAG_ADMIN);

        when(mGlobalActionsDialogLite.getCurrentUser()).thenReturn(currentUser);
        mSecureSettings.putIntForUser(Settings.Secure.BUGREPORT_IN_POWER_MENU, 1,
                currentUser.id);

        GlobalActionsDialogLite.BugReportAction bugReportAction =
                mGlobalActionsDialogLite.makeBugReportActionForTesting();
        assertThat(bugReportAction.showBeforeProvisioning()).isTrue();
    }

    @Test
    public void testBugreportAction_whenUserIsNotAdmin_noBugReportActionBeforeProvisioning() {
        UserInfo currentUser = mockCurrentUser(0);

        when(mGlobalActionsDialogLite.getCurrentUser()).thenReturn(currentUser);
        mSecureSettings.putIntForUser(Settings.Secure.BUGREPORT_IN_POWER_MENU, 1,
                currentUser.id);

        GlobalActionsDialogLite.BugReportAction bugReportAction =
                mGlobalActionsDialogLite.makeBugReportActionForTesting();
        assertThat(bugReportAction.showBeforeProvisioning()).isFalse();
    }

    @Test
    public void testInteractor_onShow() {
        mGlobalActionsDialogLite.onShow(null);
        assertThat(mInteractor.isVisible().getValue()).isTrue();
    }

    @Test
    public void testInteractor_onDismiss() {
        mGlobalActionsDialogLite.onDismiss(mGlobalActionsDialogLite.mDialog);
        assertThat(mInteractor.isVisible().getValue()).isFalse();
    }

    @Test
    public void testShouldLogSystemUpdatePress() {
        GlobalActionsDialogLite.SystemUpdateAction systemUpdateAction =
                mGlobalActionsDialogLite.new SystemUpdateAction();
        systemUpdateAction.onPress();
        verifyLogPosted(GlobalActionsDialogLite.GlobalActionsEvent.GA_SYSTEM_UPDATE_PRESS);
    }

    @Test
    public void testCreateActionItems_systemUpdateEnabled_doesShowSystemUpdate() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(5).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayEmergency();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_SYSTEM_UPDATE
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        mGlobalActionsDialogLite.createActionItems();

        assertItemsOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialogLite.EmergencyAction.class,
                GlobalActionsDialogLite.LockDownAction.class,
                GlobalActionsDialogLite.ShutDownAction.class,
                GlobalActionsDialogLite.RestartAction.class,
                GlobalActionsDialogLite.SystemUpdateAction.class);
        assertThat(mGlobalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(mGlobalActionsDialogLite.mPowerItems).isEmpty();
    }

    @Test
    public void testCreateActionItems_systemUpdateDisabled_doesntShowSystemUpdateAction() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(5).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayEmergency();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        mGlobalActionsDialogLite.createActionItems();

        assertNoItemsOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SystemUpdateAction.class);
        assertThat(mGlobalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(mGlobalActionsDialogLite.mPowerItems).isEmpty();
    }

    @Test
    public void testCreateActionItems_systemUpdateEnabled_locked_showsSystemUpdate() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(5).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayEmergency();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_SYSTEM_UPDATE
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();

        // Show dialog with keyguard showing
        mGlobalActionsDialogLite.showOrHideDialog(true, true, null);

        assertOneItemOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SystemUpdateAction.class);

        // Hide dialog
        mGlobalActionsDialogLite.showOrHideDialog(true, true, null);
    }

    @Test
    public void testCreateActionItems_systemUpdateEnabled_notProvisioned_noSystemUpdate() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(5).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayEmergency();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        String[] actions = {
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_RESTART,
                GlobalActionsDialogLite.GLOBAL_ACTION_KEY_SYSTEM_UPDATE
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();

        // Show dialog with keyguard showing
        mGlobalActionsDialogLite.showOrHideDialog(false, false, null);

        assertNoItemsOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialogLite.SystemUpdateAction.class);

        // Hide dialog
        mGlobalActionsDialogLite.showOrHideDialog(false, false, null);
    }

    private UserInfo mockCurrentUser(int flags) {
        return new UserInfo(10, "A User", flags);

    }
}
