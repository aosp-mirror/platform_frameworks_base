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

package com.android.systemui.shade;

import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;
import static kotlinx.coroutines.test.TestCoroutineDispatchersKt.StandardTestDispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository;
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository;
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.qs.QSFragmentLegacy;
import com.android.systemui.res.R;
import com.android.systemui.scene.domain.interactor.SceneInteractor;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.shade.data.repository.FakeShadeRepository;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.shade.domain.interactor.ShadeInteractorImpl;
import com.android.systemui.shade.domain.interactor.ShadeInteractorLegacyImpl;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.QsFrameTranslateController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository;
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository;
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController;
import com.android.systemui.statusbar.policy.data.repository.FakeUserSetupRepository;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.user.domain.interactor.UserSwitcherInteractor;
import com.android.systemui.util.kotlin.JavaAdapter;

import dagger.Lazy;

import kotlinx.coroutines.test.TestScope;

import org.junit.After;
import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class QuickSettingsControllerImplBaseTest extends SysuiTestCase {
    protected static final float QS_FRAME_START_X = 0f;
    protected static final int QS_FRAME_WIDTH = 1000;
    protected static final int QS_FRAME_TOP = 0;
    protected static final int QS_FRAME_BOTTOM = 1000;
    protected static final int DEFAULT_HEIGHT = 1000;
    // In split shade min = max
    protected static final int DEFAULT_MIN_HEIGHT_SPLIT_SHADE = DEFAULT_HEIGHT;
    protected static final int DEFAULT_MIN_HEIGHT = 300;

    protected QuickSettingsControllerImpl mQsController;

    protected KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    protected TestScope mTestScope = mKosmos.getTestScope();

    @Mock protected Resources mResources;
    @Mock protected KeyguardBottomAreaView mQsFrame;
    @Mock protected KeyguardStatusBarView mKeyguardStatusBar;
    @Mock protected QS mQs;
    @Mock protected QSFragmentLegacy mQSFragment;
    @Mock protected Lazy<NotificationPanelViewController> mPanelViewControllerLazy;
    @Mock protected NotificationPanelViewController mNotificationPanelViewController;
    @Mock protected NotificationPanelView mPanelView;
    @Mock protected ViewGroup mQsHeader;
    @Mock protected ViewParent mPanelViewParent;
    @Mock protected QsFrameTranslateController mQsFrameTranslateController;
    @Mock protected PulseExpansionHandler mPulseExpansionHandler;
    @Mock protected NotificationRemoteInputManager mNotificationRemoteInputManager;
    @Mock protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock protected LightBarController mLightBarController;
    @Mock protected NotificationStackScrollLayoutController
            mNotificationStackScrollLayoutController;
    @Mock protected LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock protected NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock protected ShadeHeaderController mShadeHeaderController;
    @Mock protected StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock protected DozeParameters mDozeParameters;
    @Mock protected KeyguardStateController mKeyguardStateController;
    @Mock protected KeyguardBypassController mKeyguardBypassController;
    @Mock protected KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock protected ScrimController mScrimController;
    @Mock protected MediaDataManager mMediaDataManager;
    @Mock protected MediaHierarchyManager mMediaHierarchyManager;
    @Mock protected AmbientState mAmbientState;
    @Mock protected RecordingController mRecordingController;
    @Mock protected FalsingManager mFalsingManager;
    @Mock protected AccessibilityManager mAccessibilityManager;
    @Mock protected LockscreenGestureLogger mLockscreenGestureLogger;
    @Mock protected MetricsLogger mMetricsLogger;
    @Mock protected FeatureFlags mFeatureFlags;
    @Mock protected ShadeLogger mShadeLogger;
    @Mock protected DumpManager mDumpManager;
    @Mock protected UiEventLogger mUiEventLogger;
    @Mock protected CastController mCastController;
    @Mock protected UserSwitcherInteractor mUserSwitcherInteractor;
    @Mock protected SelectedUserInteractor mSelectedUserInteractor;
    @Mock protected LargeScreenHeaderHelper mLargeScreenHeaderHelper;

    protected FakeDisableFlagsRepository mDisableFlagsRepository =
            new FakeDisableFlagsRepository();
    protected FakeKeyguardRepository mKeyguardRepository = new FakeKeyguardRepository();
    protected FakeShadeRepository mShadeRepository = new FakeShadeRepository();

    protected SysuiStatusBarStateController mStatusBarStateController;
    protected ShadeInteractor mShadeInteractor;

    protected ActiveNotificationsInteractor mActiveNotificationsInteractor;

    protected Handler mMainHandler;
    protected LockscreenShadeTransitionController.Callback mLockscreenShadeTransitionCallback;

    protected FragmentHostManager.FragmentListener mFragmentListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mPanelViewControllerLazy.get()).thenReturn(mNotificationPanelViewController);
        mStatusBarStateController = mKosmos.getStatusBarStateController();

        mKosmos.getFakeDeviceProvisioningRepository().setDeviceProvisioned(true);
        FakeConfigurationRepository configurationRepository = new FakeConfigurationRepository();

        PowerInteractor powerInteractor = mKosmos.getPowerInteractor();

        SceneInteractor sceneInteractor = mKosmos.getSceneInteractor();

        KeyguardTransitionInteractor keyguardTransitionInteractor =
                mKosmos.getKeyguardTransitionInteractor();
        KeyguardInteractor keyguardInteractor = new KeyguardInteractor(
                mKeyguardRepository,
                powerInteractor,
                new FakeKeyguardBouncerRepository(),
                new ConfigurationInteractor(configurationRepository),
                mShadeRepository,
                keyguardTransitionInteractor,
                () -> sceneInteractor,
                () -> mKosmos.getFromGoneTransitionInteractor(),
                () -> mKosmos.getFromLockscreenTransitionInteractor(),
                () -> mKosmos.getFromOccludedTransitionInteractor(),
                () -> mKosmos.getSharedNotificationContainerInteractor(),
                mTestScope);

        ResourcesSplitShadeStateController splitShadeStateController =
                new ResourcesSplitShadeStateController();

        DeviceEntryUdfpsInteractor deviceEntryUdfpsInteractor =
                mock(DeviceEntryUdfpsInteractor.class);
        when(deviceEntryUdfpsInteractor.isUdfpsSupported()).thenReturn(
                MutableStateFlow(false));

        mShadeInteractor = new ShadeInteractorImpl(
                mTestScope.getBackgroundScope(),
                mKosmos.getDeviceProvisioningInteractor(),
                mDisableFlagsRepository,
                mDozeParameters,
                mKeyguardRepository,
                keyguardTransitionInteractor,
                powerInteractor,
                new FakeUserSetupRepository(),
                mUserSwitcherInteractor,
                new ShadeInteractorLegacyImpl(
                        mTestScope.getBackgroundScope(),
                        mKeyguardRepository,
                        new SharedNotificationContainerInteractor(
                                configurationRepository,
                                mContext,
                                () -> splitShadeStateController,
                                () -> mShadeInteractor,
                                keyguardInteractor,
                                deviceEntryUdfpsInteractor,
                                () -> mLargeScreenHeaderHelper),
                        mShadeRepository
                ),
                mKosmos.getShadeModeInteractor());

        mActiveNotificationsInteractor = new ActiveNotificationsInteractor(
                        new ActiveNotificationListRepository(),
                        StandardTestDispatcher(/* scheduler = */ null, /* name = */ null)
                );

        KeyguardStatusView keyguardStatusView = new KeyguardStatusView(mContext);
        keyguardStatusView.setId(R.id.keyguard_status_view);

        when(mResources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_transition_distance)).thenReturn(DEFAULT_HEIGHT);
        when(mPanelView.getResources()).thenReturn(mResources);
        when(mPanelView.getContext()).thenReturn(getContext());
        when(mPanelView.findViewById(R.id.keyguard_header)).thenReturn(mKeyguardStatusBar);
        when(mNotificationStackScrollLayoutController.getHeight()).thenReturn(1000);
        when(mPanelView.findViewById(R.id.qs_frame)).thenReturn(mQsFrame);
        when(mQsFrame.getX()).thenReturn(QS_FRAME_START_X);
        when(mQsFrame.getWidth()).thenReturn(QS_FRAME_WIDTH);
        when(mQsHeader.getTop()).thenReturn(QS_FRAME_TOP);
        when(mQsHeader.getBottom()).thenReturn(QS_FRAME_BOTTOM);
        when(mQs.getHeaderTop()).thenReturn(QS_FRAME_TOP);
        when(mQs.getHeaderBottom()).thenReturn(QS_FRAME_BOTTOM);
        when(mPanelView.getY()).thenReturn((float) QS_FRAME_TOP);
        when(mPanelView.getHeight()).thenReturn(QS_FRAME_BOTTOM);
        when(mPanelView.findViewById(R.id.keyguard_status_view))
                .thenReturn(mock(KeyguardStatusView.class));
        when(mQs.getView()).thenReturn(mPanelView);
        when(mQSFragment.getView()).thenReturn(mPanelView);

        when(mNotificationRemoteInputManager.isRemoteInputActive())
                .thenReturn(false);

        when(mPanelView.getParent()).thenReturn(mPanelViewParent);
        when(mQs.getHeader()).thenReturn(mQsHeader);
        when(mQSFragment.getHeader()).thenReturn(mQsHeader);

        doAnswer(invocation -> {
            mLockscreenShadeTransitionCallback = invocation.getArgument(0);
            return null;
        }).when(mLockscreenShadeTransitionController).addCallback(any());


        mMainHandler = new Handler(Looper.getMainLooper());

        mQsController = new QuickSettingsControllerImpl(
                mPanelViewControllerLazy,
                mPanelView,
                mQsFrameTranslateController,
                mPulseExpansionHandler,
                mNotificationRemoteInputManager,
                mStatusBarKeyguardViewManager,
                mLightBarController,
                mNotificationStackScrollLayoutController,
                mLockscreenShadeTransitionController,
                mNotificationShadeDepthController,
                mShadeHeaderController,
                mStatusBarTouchableRegionManager,
                mKeyguardStateController,
                mKeyguardBypassController,
                mScrimController,
                mMediaDataManager,
                mMediaHierarchyManager,
                mAmbientState,
                mRecordingController,
                mFalsingManager,
                mAccessibilityManager,
                mLockscreenGestureLogger,
                mMetricsLogger,
                () -> mKosmos.getInteractionJankMonitor(),
                mShadeLogger,
                mDumpManager,
                mock(DeviceEntryFaceAuthInteractor.class),
                mShadeRepository,
                mShadeInteractor,
                mActiveNotificationsInteractor,
                new JavaAdapter(mTestScope.getBackgroundScope()),
                mCastController,
                splitShadeStateController,
                () -> mKosmos.getCommunalTransitionViewModel(),
                () -> mLargeScreenHeaderHelper
        );
        mQsController.init();

        mFragmentListener = mQsController.getQsFragmentListener();
    }

    @After
    public void tearDown() {
        mMainHandler.removeCallbacksAndMessages(null);
    }
}
