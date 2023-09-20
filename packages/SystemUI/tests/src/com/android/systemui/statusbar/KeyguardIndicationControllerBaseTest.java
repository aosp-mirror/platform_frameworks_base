/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_DEFAULT;

import static com.android.systemui.flags.Flags.KEYGUARD_TALKBACK_FIX;
import static com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED;
import static com.android.systemui.keyguard.ScreenLifecycle.SCREEN_ON;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyResourcesManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Looper;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.testing.TestableLooper;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.FaceHelpMessageDeferral;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dock.DockManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.keyguard.KeyguardIndication;
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory;
import com.android.systemui.keyguard.util.IndicationHelper;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.After;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class KeyguardIndicationControllerBaseTest extends SysuiTestCase {

    protected static final String ORGANIZATION_NAME = "organization";

    protected static final ComponentName DEVICE_OWNER_COMPONENT = new ComponentName(
            "com.android.foo",
            "bar");

    protected static final int TEST_STRING_RES = R.string.keyguard_indication_trust_unlocked;

    protected String mDisclosureWithOrganization;
    protected String mDisclosureGeneric;
    protected String mFinancedDisclosureWithOrganization;

    @Mock
    protected DevicePolicyManager mDevicePolicyManager;
    @Mock
    protected DevicePolicyResourcesManager mDevicePolicyResourcesManager;
    @Mock
    protected ViewGroup mIndicationArea;
    @Mock
    protected KeyguardStateController mKeyguardStateController;
    @Mock
    protected KeyguardIndicationTextView mIndicationAreaBottom;
    @Mock
    protected BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    protected StatusBarStateController mStatusBarStateController;
    @Mock
    protected KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    protected UserManager mUserManager;
    @Mock
    protected IBatteryStats mIBatteryStats;
    @Mock
    protected DockManager mDockManager;
    @Mock
    protected KeyguardIndicationRotateTextViewController mRotateTextViewController;
    @Mock
    protected FalsingManager mFalsingManager;
    @Mock
    protected LockPatternUtils mLockPatternUtils;
    @Mock
    protected KeyguardBypassController mKeyguardBypassController;
    @Mock
    protected AccessibilityManager mAccessibilityManager;
    @Mock
    protected FaceHelpMessageDeferral mFaceHelpMessageDeferral;
    @Mock
    protected AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock
    protected ScreenLifecycle mScreenLifecycle;
    @Mock
    protected AuthController mAuthController;
    @Mock
    protected AlarmManager mAlarmManager;
    @Mock
    protected UserTracker mUserTracker;
    @Captor
    protected ArgumentCaptor<DockManager.AlignmentStateListener> mAlignmentListener;
    @Captor
    protected ArgumentCaptor<StatusBarStateController.StateListener> mStatusBarStateListenerCaptor;
    @Captor
    protected ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;
    @Captor
    protected ArgumentCaptor<KeyguardIndication> mKeyguardIndicationCaptor;
    @Captor
    protected ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardUpdateMonitorCallbackCaptor;
    @Captor
    protected ArgumentCaptor<KeyguardStateController.Callback>
            mKeyguardStateControllerCallbackCaptor;
    @Captor
    protected ArgumentCaptor<ScreenLifecycle.Observer> mScreenObserverCaptor;
    protected KeyguardStateController.Callback mKeyguardStateControllerCallback;
    protected KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback;
    protected StatusBarStateController.StateListener mStatusBarStateListener;
    protected ScreenLifecycle.Observer mScreenObserver;
    protected BroadcastReceiver mBroadcastReceiver;
    protected IndicationHelper mIndicationHelper;
    protected FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    protected TestableLooper mTestableLooper;
    protected final int mCurrentUserId = 1;

    protected KeyguardIndicationTextView mTextView; // AOD text

    protected KeyguardIndicationController mController;
    protected WakeLockFake.Builder mWakeLockBuilder;
    protected WakeLockFake mWakeLock;
    protected Instrumentation mInstrumentation;
    protected FakeFeatureFlags mFlags;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mTestableLooper = TestableLooper.get(this);
        mTextView = new KeyguardIndicationTextView(mContext);
        mTextView.setAnimationsEnabled(false);

        // TODO(b/259908270): remove
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                DevicePolicyManager.ADD_ISFINANCED_DEVICE_FLAG, "true",
                /* makeDefault= */ false);
        mContext.addMockSystemService(Context.DEVICE_POLICY_SERVICE, mDevicePolicyManager);
        mContext.addMockSystemService(UserManager.class, mUserManager);
        mContext.addMockSystemService(Context.TRUST_SERVICE, mock(TrustManager.class));
        mContext.addMockSystemService(Context.FINGERPRINT_SERVICE, mock(FingerprintManager.class));
        mDisclosureWithOrganization = mContext.getString(R.string.do_disclosure_with_name,
                ORGANIZATION_NAME);
        mDisclosureGeneric = mContext.getString(R.string.do_disclosure_generic);
        mFinancedDisclosureWithOrganization = mContext.getString(
                R.string.do_financed_disclosure_with_name, ORGANIZATION_NAME);

        when(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        when(mScreenLifecycle.getScreenState()).thenReturn(SCREEN_ON);
        when(mKeyguardUpdateMonitor.isUserUnlocked(anyInt())).thenReturn(true);

        when(mIndicationArea.findViewById(R.id.keyguard_indication_text_bottom))
                .thenReturn(mIndicationAreaBottom);
        when(mIndicationArea.findViewById(R.id.keyguard_indication_text)).thenReturn(mTextView);

        when(mDevicePolicyManager.getResources()).thenReturn(mDevicePolicyResourcesManager);
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                .thenReturn(DEVICE_OWNER_COMPONENT);
        when(mDevicePolicyManager.isFinancedDevice()).thenReturn(false);
        // TODO(b/259908270): remove
        when(mDevicePolicyManager.getDeviceOwnerType(DEVICE_OWNER_COMPONENT))
                .thenReturn(DEVICE_OWNER_TYPE_DEFAULT);

        when(mDevicePolicyResourcesManager.getString(anyString(), any()))
                .thenReturn(mDisclosureGeneric);
        when(mDevicePolicyResourcesManager.getString(anyString(), any(), anyString()))
                .thenReturn(mDisclosureWithOrganization);
        when(mUserTracker.getUserId()).thenReturn(mCurrentUserId);

        mIndicationHelper = new IndicationHelper(mKeyguardUpdateMonitor);

        mWakeLock = new WakeLockFake();
        mWakeLockBuilder = new WakeLockFake.Builder(mContext);
        mWakeLockBuilder.setWakeLock(mWakeLock);
    }

    @After
    public void tearDown() throws Exception {
        mTextView.setAnimationsEnabled(true);
        if (mController != null) {
            mController.destroy();
            mController = null;
        }
    }

    protected void createController() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mFlags = new FakeFeatureFlags();
        mFlags.set(KEYGUARD_TALKBACK_FIX, true);
        mFlags.set(LOCKSCREEN_WALLPAPER_DREAM_ENABLED, false);
        mController = new KeyguardIndicationController(
                mContext,
                mTestableLooper.getLooper(),
                mWakeLockBuilder,
                mKeyguardStateController, mStatusBarStateController, mKeyguardUpdateMonitor,
                mDockManager, mBroadcastDispatcher, mDevicePolicyManager, mIBatteryStats,
                mUserManager, mExecutor, mExecutor, mFalsingManager,
                mAuthController, mLockPatternUtils, mScreenLifecycle,
                mKeyguardBypassController, mAccessibilityManager,
                mFaceHelpMessageDeferral, mock(KeyguardLogger.class),
                mAlternateBouncerInteractor,
                mAlarmManager,
                mUserTracker,
                mock(BouncerMessageInteractor.class),
                mFlags,
                mIndicationHelper,
                KeyguardInteractorFactory.create(mFlags).getKeyguardInteractor()
        );
        mController.init();
        mController.setIndicationArea(mIndicationArea);
        verify(mStatusBarStateController).addCallback(mStatusBarStateListenerCaptor.capture());
        mStatusBarStateListener = mStatusBarStateListenerCaptor.getValue();
        verify(mBroadcastDispatcher).registerReceiver(mBroadcastReceiverCaptor.capture(), any());
        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
        mController.mRotateTextViewController = mRotateTextViewController;
        mController.setStatusBarKeyguardViewManager(mStatusBarKeyguardViewManager);
        clearInvocations(mIBatteryStats);

        verify(mKeyguardStateController).addCallback(
                mKeyguardStateControllerCallbackCaptor.capture());
        mKeyguardStateControllerCallback = mKeyguardStateControllerCallbackCaptor.getValue();

        verify(mKeyguardUpdateMonitor).registerCallback(
                mKeyguardUpdateMonitorCallbackCaptor.capture());
        mKeyguardUpdateMonitorCallback = mKeyguardUpdateMonitorCallbackCaptor.getValue();

        verify(mScreenLifecycle).addObserver(mScreenObserverCaptor.capture());
        mScreenObserver = mScreenObserverCaptor.getValue();

        mExecutor.runAllReady();
        reset(mRotateTextViewController);
    }
}
