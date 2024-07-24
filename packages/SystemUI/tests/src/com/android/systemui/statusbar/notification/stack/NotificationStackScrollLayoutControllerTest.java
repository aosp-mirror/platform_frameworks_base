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

package com.android.systemui.statusbar.notification.stack;

import static com.android.server.notification.Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING;
import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_ALL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import static kotlinx.coroutines.flow.FlowKt.emptyFlow;
import static kotlinx.coroutines.test.TestCoroutineDispatchersKt.StandardTestDispatcher;

import android.metrics.LogMaker;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.media.controls.ui.controller.KeyguardMediaController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.OnMenuEventListener;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlags;
import com.android.systemui.scene.ui.view.WindowRootView;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider;
import com.android.systemui.statusbar.notification.collection.provider.VisibilityLocationProviderDelegator;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.NotifStats;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository;
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor;
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor;
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController.NotificationPanelEvent;
import com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper.NotificationCallback;
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor;
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.NotificationListViewBinder;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.inject.Provider;

/**
 * Tests for {@link NotificationStackScrollLayoutController}.
 */
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner.class)
public class NotificationStackScrollLayoutControllerTest extends SysuiTestCase {

    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();
    @Mock private NotificationGutsManager mNotificationGutsManager;
    @Mock private NotificationsController mNotificationsController;
    @Mock private NotificationVisibilityProvider mVisibilityProvider;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private NotificationRoundnessManager mNotificationRoundnessManager;
    @Mock private TunerService mTunerService;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private DynamicPrivacyController mDynamicPrivacyController;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private NotificationStackScrollLayout mNotificationStackScrollLayout;
    @Mock private ZenModeController mZenModeController;
    @Mock private KeyguardMediaController mKeyguardMediaController;
    @Mock private SysuiStatusBarStateController mSysuiStatusBarStateController;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private PowerInteractor mPowerInteractor;
    @Mock private PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @Mock private NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private ColorUpdateLogger mColorUpdateLogger;
    @Mock private DumpManager mDumpManager;
    @Mock(answer = Answers.RETURNS_SELF)
    private NotificationSwipeHelper.Builder mNotificationSwipeHelperBuilder;
    @Mock private NotificationSwipeHelper mNotificationSwipeHelper;
    @Mock private ScrimController mScrimController;
    @Mock private GroupExpansionManager mGroupExpansionManager;
    @Mock private SectionHeaderController mSilentHeaderController;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private NotifCollection mNotifCollection;
    @Mock private UiEventLogger mUiEventLogger;
    @Mock private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private VisibilityLocationProviderDelegator mVisibilityLocationProviderDelegator;
    @Mock private ShadeController mShadeController;
    @Mock private SceneContainerFlags mSceneContainerFlags;
    @Mock private Provider<WindowRootView> mWindowRootView;
    @Mock private NotificationStackAppearanceInteractor mNotificationStackAppearanceInteractor;
    @Mock private InteractionJankMonitor mJankMonitor;
    private final StackStateLogger mStackLogger = new StackStateLogger(logcatLogBuffer(),
            logcatLogBuffer());
    private final NotificationStackScrollLogger mLogger = new NotificationStackScrollLogger(
            logcatLogBuffer(), logcatLogBuffer(), logcatLogBuffer());
    @Mock private NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    @Mock private NotificationTargetsHelper mNotificationTargetsHelper;
    @Mock private SecureSettings mSecureSettings;
    @Mock private ActivityStarter mActivityStarter;
    @Mock private KeyguardTransitionRepository mKeyguardTransitionRepo;
    @Mock private NotificationListViewBinder mViewBinder;
    @Mock
    private SensitiveNotificationProtectionController mSensitiveNotificationProtectionController;

    @Captor
    private ArgumentCaptor<Runnable> mSensitiveStateListenerArgumentCaptor;

    @Captor
    private ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerArgumentCaptor;


    private final ActiveNotificationListRepository mActiveNotificationsRepository =
            new ActiveNotificationListRepository();

    private final ActiveNotificationsInteractor mActiveNotificationsInteractor =
            new ActiveNotificationsInteractor(mActiveNotificationsRepository,
                    StandardTestDispatcher(/* scheduler = */ null, /* name = */ null));

    private final SeenNotificationsInteractor mSeenNotificationsInteractor =
            new SeenNotificationsInteractor(mActiveNotificationsRepository);

    private NotificationStackScrollLayoutController mController;

    @Before
    public void setUp() {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);

        mFeatureFlags.set(Flags.USE_REPOS_FOR_BOUNCER_SHOWING, true);

        when(mNotificationSwipeHelperBuilder.build()).thenReturn(mNotificationSwipeHelper);
        when(mKeyguardTransitionRepo.getTransitions()).thenReturn(emptyFlow());
    }

    @Test
    public void testAttach_viewAlreadyAttached() {
        initController(/* viewIsAttached= */ true);

        verify(mConfigurationController).addCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }
    @Test
    public void testAttach_viewAttachedAfterInit() {
        initController(/* viewIsAttached= */ false);
        verify(mConfigurationController, never()).addCallback(
                any(ConfigurationController.ConfigurationListener.class));

        mController.mOnAttachStateChangeListener.onViewAttachedToWindow(
                mNotificationStackScrollLayout);

        verify(mConfigurationController).addCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }

    @Test
    public void testOnDensityOrFontScaleChanged_reInflatesFooterViews() {
        initController(/* viewIsAttached= */ true);

        mController.mConfigurationListener.onDensityOrFontScaleChanged();
        verify(mNotificationStackScrollLayout).reinflateViews();
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testUpdateEmptyShadeView_notificationsVisible_zenHiding() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(true);
        initController(/* viewIsAttached= */ true);

        setupShowEmptyShadeViewState(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ true,
                /* notifVisibleInShade= */ true);

        setupShowEmptyShadeViewState(false);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ false,
                /* notifVisibleInShade= */ true);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testUpdateEmptyShadeView_notificationsHidden_zenNotHiding() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        setupShowEmptyShadeViewState(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ true,
                /* notifVisibleInShade= */ false);

        setupShowEmptyShadeViewState(false);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ false,
                /* notifVisibleInShade= */ false);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testUpdateEmptyShadeView_splitShadeMode_alwaysShowEmptyView() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());
        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();
        stateListener.onStateChanged(SHADE);
        mController.getView().removeAllViews();

        mController.setQsFullScreen(false);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ true,
                /* notifVisibleInShade= */ false);

        mController.setQsFullScreen(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ true,
                /* notifVisibleInShade= */ false);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testUpdateEmptyShadeView_bouncerShowing_flagOff_hideEmptyView() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        // WHEN the flag is off and *only* CentralSurfaces has bouncer as showing
        mFeatureFlags.set(Flags.USE_REPOS_FOR_BOUNCER_SHOWING, false);
        mController.setBouncerShowingFromCentralSurfaces(true);
        when(mPrimaryBouncerInteractor.isBouncerShowing()).thenReturn(false);

        setupShowEmptyShadeViewState(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();

        // THEN the CentralSurfaces value is used. Since the bouncer is showing, we hide the empty
        // view.
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ false,
                /* areNotificationsHiddenInShade= */ false);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testUpdateEmptyShadeView_bouncerShowing_flagOn_hideEmptyView() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        // WHEN the flag is on and *only* PrimaryBouncerInteractor has bouncer as showing
        mFeatureFlags.set(Flags.USE_REPOS_FOR_BOUNCER_SHOWING, true);
        when(mPrimaryBouncerInteractor.isBouncerShowing()).thenReturn(true);
        mController.setBouncerShowingFromCentralSurfaces(false);

        setupShowEmptyShadeViewState(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();

        // THEN the PrimaryBouncerInteractor value is used. Since the bouncer is showing, we
        // hide the empty view.
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ false,
                /* areNotificationsHiddenInShade= */ false);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testUpdateEmptyShadeView_bouncerNotShowing_flagOff_showEmptyView() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        // WHEN the flag is off and *only* CentralSurfaces has bouncer as not showing
        mFeatureFlags.set(Flags.USE_REPOS_FOR_BOUNCER_SHOWING, false);
        mController.setBouncerShowingFromCentralSurfaces(false);
        when(mPrimaryBouncerInteractor.isBouncerShowing()).thenReturn(true);

        setupShowEmptyShadeViewState(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();

        // THEN the CentralSurfaces value is used. Since the bouncer isn't showing, we can show the
        // empty view.
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ true,
                /* areNotificationsHiddenInShade= */ false);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testUpdateEmptyShadeView_bouncerNotShowing_flagOn_showEmptyView() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        // WHEN the flag is on and *only* PrimaryBouncerInteractor has bouncer as not showing
        mFeatureFlags.set(Flags.USE_REPOS_FOR_BOUNCER_SHOWING, true);
        when(mPrimaryBouncerInteractor.isBouncerShowing()).thenReturn(false);
        mController.setBouncerShowingFromCentralSurfaces(true);

        setupShowEmptyShadeViewState(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();

        // THEN the PrimaryBouncerInteractor value is used. Since the bouncer isn't showing, we
        // can show the empty view.
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ true,
                /* areNotificationsHiddenInShade= */ false);
    }

    @Test
    public void testOnUserChange_verifyNotSensitive() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        ArgumentCaptor<UserChangedListener> userChangedCaptor = ArgumentCaptor
                .forClass(UserChangedListener.class);

        verify(mNotificationLockscreenUserManager)
                .addUserChangedListener(userChangedCaptor.capture());
        reset(mNotificationStackScrollLayout);

        UserChangedListener changedListener = userChangedCaptor.getValue();
        changedListener.onUserChanged(0);
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, false);
    }

    @Test
    public void testOnUserChange_verifySensitiveProfile() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);
        initController(/* viewIsAttached= */ true);

        ArgumentCaptor<UserChangedListener> userChangedCaptor = ArgumentCaptor
                .forClass(UserChangedListener.class);

        verify(mNotificationLockscreenUserManager)
                .addUserChangedListener(userChangedCaptor.capture());
        reset(mNotificationStackScrollLayout);

        UserChangedListener changedListener = userChangedCaptor.getValue();
        changedListener.onUserChanged(0);
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnUserChange_verifyNotSensitive_screenshareNotificationHidingEnabled() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(false);

        initController(/* viewIsAttached= */ true);

        ArgumentCaptor<UserChangedListener> userChangedCaptor = ArgumentCaptor
                .forClass(UserChangedListener.class);

        verify(mNotificationLockscreenUserManager)
                .addUserChangedListener(userChangedCaptor.capture());
        reset(mNotificationStackScrollLayout);

        UserChangedListener changedListener = userChangedCaptor.getValue();
        changedListener.onUserChanged(0);
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, false);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnUserChange_verifySensitiveProfile_screenshareNotificationHidingEnabled() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(false);

        initController(/* viewIsAttached= */ true);

        ArgumentCaptor<UserChangedListener> userChangedCaptor = ArgumentCaptor
                .forClass(UserChangedListener.class);

        verify(mNotificationLockscreenUserManager)
                .addUserChangedListener(userChangedCaptor.capture());
        reset(mNotificationStackScrollLayout);

        UserChangedListener changedListener = userChangedCaptor.getValue();
        changedListener.onUserChanged(0);
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnUserChange_verifySensitiveActive_screenshareNotificationHidingEnabled() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(true);
        initController(/* viewIsAttached= */ true);

        ArgumentCaptor<UserChangedListener> userChangedCaptor = ArgumentCaptor
                .forClass(UserChangedListener.class);

        verify(mNotificationLockscreenUserManager)
                .addUserChangedListener(userChangedCaptor.capture());
        reset(mNotificationStackScrollLayout);

        UserChangedListener changedListener = userChangedCaptor.getValue();
        changedListener.onUserChanged(0);
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    public void testOnStatePostChange_verifyNotSensitive() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, false);
    }

    @Test
    public void testOnStatePostChange_verifyIfProfileIsPublic() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnStatePostChange_verifyNotSensitive_screenshareNotificationHidingEnabled() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(false);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, false);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnStatePostChange_verifyIfProfileIsPublic_screenshareNotificationHidingEnabled(
    ) {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(false);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnStatePostChange_verifyIfSensitiveActive_screenshareNotificationHidingEnabled(
    ) {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(true);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    public void testOnStatePostChange_goingFullShade_verifyNotSensitive() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSysuiStatusBarStateController.goingToFullShade()).thenReturn(true);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(true, false);
    }

    @Test
    public void testOnStatePostChange_goingFullShade_verifyIfProfileIsPublic() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);
        when(mSysuiStatusBarStateController.goingToFullShade()).thenReturn(true);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(true, true);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnStatePostChange_goingFullShade_verifyNotSensitive_screenshareHideEnabled(
    ) {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSysuiStatusBarStateController.goingToFullShade()).thenReturn(true);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(false);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(true, false);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnStatePostChange_goingFullShade_verifyProfileIsPublic_screenshareHideEnabled(
    ) {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);
        when(mSysuiStatusBarStateController.goingToFullShade()).thenReturn(true);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(false);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(true, true);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnStatePostChange_goingFullShade_verifySensitiveActive_screenshareHideEnabled(
    ) {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSysuiStatusBarStateController.goingToFullShade()).thenReturn(true);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(true);

        initController(/* viewIsAttached= */ true);
        verify(mSysuiStatusBarStateController).addCallback(
                mStateListenerArgumentCaptor.capture(), anyInt());

        StatusBarStateController.StateListener stateListener =
                mStateListenerArgumentCaptor.getValue();

        stateListener.onStatePostChange();
        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnProjectionStateChanged_verifyNotSensitive() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive())
                .thenReturn(false);

        initController(/* viewIsAttached= */ true);
        verify(mSensitiveNotificationProtectionController)
                .registerSensitiveStateListener(mSensitiveStateListenerArgumentCaptor.capture());

        mSensitiveStateListenerArgumentCaptor.getValue().run();

        verify(mNotificationStackScrollLayout).updateSensitiveness(false, false);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnProjectionStateChanged_verifyIfProfileIsPublic() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(false);

        initController(/* viewIsAttached= */ true);
        verify(mSensitiveNotificationProtectionController)
                .registerSensitiveStateListener(mSensitiveStateListenerArgumentCaptor.capture());

        mSensitiveStateListenerArgumentCaptor.getValue().run();

        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void testOnProjectionStateChanged_verifyIfSensitiveActive() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(false);
        when(mSensitiveNotificationProtectionController.isSensitiveStateActive()).thenReturn(true);

        initController(/* viewIsAttached= */ true);
        verify(mSensitiveNotificationProtectionController)
                .registerSensitiveStateListener(mSensitiveStateListenerArgumentCaptor.capture());

        mSensitiveStateListenerArgumentCaptor.getValue().run();

        verify(mNotificationStackScrollLayout).updateSensitiveness(false, true);
    }

    @Test
    public void testOnMenuShownLogging() {
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class, RETURNS_DEEP_STUBS);
        when(row.getEntry().getSbn().getLogMaker()).thenReturn(new LogMaker(
                MetricsProto.MetricsEvent.VIEW_UNKNOWN));

        ArgumentCaptor<OnMenuEventListener> onMenuEventListenerArgumentCaptor =
                ArgumentCaptor.forClass(OnMenuEventListener.class);

        initController(/* viewIsAttached= */ true);
        verify(mNotificationSwipeHelperBuilder).setOnMenuEventListener(
                onMenuEventListenerArgumentCaptor.capture());

        OnMenuEventListener onMenuEventListener = onMenuEventListenerArgumentCaptor.getValue();

        onMenuEventListener.onMenuShown(row);
        verify(row.getEntry().getSbn()).getLogMaker();  // This writes most of the log data
        verify(mMetricsLogger).write(logMatcher(MetricsProto.MetricsEvent.ACTION_REVEAL_GEAR,
                MetricsProto.MetricsEvent.TYPE_ACTION));
    }

    @Test
    public void callSwipeCallbacksDuringClearAll() {
        initController(/* viewIsAttached= */ true);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        NotificationCallback notificationCallback = mController.mNotificationCallback;

        when(mNotificationStackScrollLayout.getClearAllInProgress()).thenReturn(true);

        notificationCallback.onBeginDrag(row);
        verify(mNotificationStackScrollLayout).onSwipeBegin(row);

        notificationCallback.handleChildViewDismissed(row);
        verify(mNotificationStackScrollLayout).onSwipeEnd();
    }

    @Test
    public void callSwipeCallbacksDuringClearNotification() {
        initController(/* viewIsAttached= */ true);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        NotificationCallback notificationCallback = mController.mNotificationCallback;

        when(mNotificationStackScrollLayout.getClearAllInProgress()).thenReturn(false);

        notificationCallback.onBeginDrag(row);
        verify(mNotificationStackScrollLayout).onSwipeBegin(row);

        notificationCallback.handleChildViewDismissed(row);
        verify(mNotificationStackScrollLayout).onSwipeEnd();
    }

    @Test
    public void testOnMenuClickedLogging() {
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class, RETURNS_DEEP_STUBS);
        when(row.getEntry().getSbn().getLogMaker()).thenReturn(new LogMaker(
                MetricsProto.MetricsEvent.VIEW_UNKNOWN));

        ArgumentCaptor<OnMenuEventListener> onMenuEventListenerArgumentCaptor =
                ArgumentCaptor.forClass(OnMenuEventListener.class);

        initController(/* viewIsAttached= */ true);
        verify(mNotificationSwipeHelperBuilder).setOnMenuEventListener(
                onMenuEventListenerArgumentCaptor.capture());

        OnMenuEventListener onMenuEventListener = onMenuEventListenerArgumentCaptor.getValue();

        onMenuEventListener.onMenuClicked(row, 0, 0, mock(
                NotificationMenuRowPlugin.MenuItem.class));
        verify(row.getEntry().getSbn()).getLogMaker();  // This writes most of the log data
        verify(mMetricsLogger).write(logMatcher(MetricsProto.MetricsEvent.ACTION_TOUCH_GEAR,
                MetricsProto.MetricsEvent.TYPE_ACTION));
    }

    @Test
    public void testDismissListener() {
        ArgumentCaptor<NotificationStackScrollLayout.ClearAllListener>
                dismissListenerArgumentCaptor = ArgumentCaptor.forClass(
                NotificationStackScrollLayout.ClearAllListener.class);

        initController(/* viewIsAttached= */ true);

        verify(mNotificationStackScrollLayout).setClearAllListener(
                dismissListenerArgumentCaptor.capture());
        NotificationStackScrollLayout.ClearAllListener dismissListener =
                dismissListenerArgumentCaptor.getValue();

        dismissListener.onClearAll(ROWS_ALL);
        verify(mUiEventLogger).log(NotificationPanelEvent.fromSelection(ROWS_ALL));
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testUpdateFooter_remoteInput() {
        ArgumentCaptor<RemoteInputController.Callback> callbackCaptor =
                ArgumentCaptor.forClass(RemoteInputController.Callback.class);
        doNothing().when(mRemoteInputManager).addControllerCallback(callbackCaptor.capture());
        when(mRemoteInputManager.isRemoteInputActive()).thenReturn(false);
        initController(/* viewIsAttached= */ true);
        verify(mNotificationStackScrollLayout).setIsRemoteInputActive(false);
        RemoteInputController.Callback callback = callbackCaptor.getValue();
        callback.onRemoteInputActive(true);
        verify(mNotificationStackScrollLayout).setIsRemoteInputActive(true);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void testSetNotifStats_updatesHasFilteredOutSeenNotifications() {
        initController(/* viewIsAttached= */ true);
        mSeenNotificationsInteractor.setHasFilteredOutSeenNotifications(true);
        mController.getNotifStackController().setNotifStats(NotifStats.getEmpty());
        verify(mNotificationStackScrollLayout).setHasFilteredOutSeenNotifications(true);
        verify(mNotificationStackScrollLayout).updateFooter();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(anyBoolean(), anyBoolean());
    }

    @Test
    public void testAttach_updatesViewStatusBarState() {
        // GIVEN: Controller is attached
        initController(/* viewIsAttached= */ true);
        ArgumentCaptor<StatusBarStateController.StateListener> captor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(mSysuiStatusBarStateController).addCallback(captor.capture(), anyInt());
        StatusBarStateController.StateListener stateListener = captor.getValue();

        // WHEN: StatusBarState changes to SHADE
        when(mSysuiStatusBarStateController.getState()).thenReturn(SHADE);
        stateListener.onStateChanged(SHADE);

        // THEN: NSSL is updated with the current state
        verify(mNotificationStackScrollLayout).setStatusBarState(SHADE);

        // WHEN: Controller is detached
        mController.mOnAttachStateChangeListener
                .onViewDetachedFromWindow(mNotificationStackScrollLayout);

        // WHEN: StatusBarState changes to KEYGUARD
        when(mSysuiStatusBarStateController.getState()).thenReturn(KEYGUARD);

        // WHEN: Controller is re-attached
        mController.mOnAttachStateChangeListener
                .onViewAttachedToWindow(mNotificationStackScrollLayout);

        // THEN: NSSL is updated with the current state
        verify(mNotificationStackScrollLayout).setStatusBarState(KEYGUARD);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void updateImportantForAccessibility_noChild_onKeyGuard_notImportantForA11y() {
        // GIVEN: Controller is attached, active notifications is empty,
        // and mNotificationStackScrollLayout.onKeyguard() is true
        initController(/* viewIsAttached= */ true);
        when(mNotificationStackScrollLayout.onKeyguard()).thenReturn(true);
        mController.getNotifStackController().setNotifStats(NotifStats.getEmpty());

        // THEN: mNotificationStackScrollLayout should not be important for A11y
        verify(mNotificationStackScrollLayout)
                .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void updateImportantForAccessibility_hasChild_onKeyGuard_importantForA11y() {
        // GIVEN: Controller is attached, active notifications is not empty,
        // and mNotificationStackScrollLayout.onKeyguard() is true
        initController(/* viewIsAttached= */ true);
        when(mNotificationStackScrollLayout.onKeyguard()).thenReturn(true);
        mController.getNotifStackController().setNotifStats(
                new NotifStats(
                        /* numActiveNotifs = */ 1,
                        /* hasNonClearableAlertingNotifs = */ false,
                        /* hasClearableAlertingNotifs = */ false,
                        /* hasNonClearableSilentNotifs = */ false,
                        /* hasClearableSilentNotifs = */ false)
        );

        // THEN: mNotificationStackScrollLayout should be important for A11y
        verify(mNotificationStackScrollLayout)
                .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void updateImportantForAccessibility_hasChild_notOnKeyGuard_importantForA11y() {
        // GIVEN: Controller is attached, active notifications is not empty,
        // and mNotificationStackScrollLayout.onKeyguard() is false
        initController(/* viewIsAttached= */ true);
        when(mNotificationStackScrollLayout.onKeyguard()).thenReturn(false);
        mController.getNotifStackController().setNotifStats(
                new NotifStats(
                        /* numActiveNotifs = */ 1,
                        /* hasNonClearableAlertingNotifs = */ false,
                        /* hasClearableAlertingNotifs = */ false,
                        /* hasNonClearableSilentNotifs = */ false,
                        /* hasClearableSilentNotifs = */ false)
        );

        // THEN: mNotificationStackScrollLayout should be important for A11y
        verify(mNotificationStackScrollLayout)
                .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void updateImportantForAccessibility_noChild_notOnKeyGuard_importantForA11y() {
        // GIVEN: Controller is attached, active notifications is empty,
        // and mNotificationStackScrollLayout.onKeyguard() is false
        initController(/* viewIsAttached= */ true);
        when(mNotificationStackScrollLayout.onKeyguard()).thenReturn(false);
        mController.getNotifStackController().setNotifStats(NotifStats.getEmpty());

        // THEN: mNotificationStackScrollLayout should be important for A11y
        verify(mNotificationStackScrollLayout)
                .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void updateEmptyShadeView_onKeyguardTransitionToAod_hidesView() {
        initController(/* viewIsAttached= */ true);
        mController.onKeyguardTransitionChanged(
                new TransitionStep(
                        /* from= */ KeyguardState.GONE,
                        /* to= */ KeyguardState.AOD));
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(eq(false), anyBoolean());
    }

    @Test
    @DisableFlags(FooterViewRefactor.FLAG_NAME)
    public void updateEmptyShadeView_onKeyguardOccludedTransitionToAod_hidesView() {
        initController(/* viewIsAttached= */ true);
        mController.onKeyguardTransitionChanged(
                new TransitionStep(
                        /* from= */ KeyguardState.OCCLUDED,
                        /* to= */ KeyguardState.AOD));
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(eq(false), anyBoolean());
    }

    @Test
    @DisableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void sensitiveNotificationProtectionControllerListenerNotRegistered() {
        initController(/* viewIsAttached= */ true);
        verifyZeroInteractions(mSensitiveNotificationProtectionController);
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    public void sensitiveNotificationProtectionControllerListenerRegistered() {
        initController(/* viewIsAttached= */ true);
        verify(mSensitiveNotificationProtectionController).registerSensitiveStateListener(any());
    }

    private LogMaker logMatcher(int category, int type) {
        return argThat(new LogMatcher(category, type));
    }

    private void setupShowEmptyShadeViewState(boolean toShow) {
        if (toShow) {
            mController.onKeyguardTransitionChanged(
                    new TransitionStep(
                            /* from= */ KeyguardState.LOCKSCREEN,
                            /* to= */ KeyguardState.GONE));
            mController.setQsFullScreen(false);
            mController.getView().removeAllViews();
        } else {
            mController.onKeyguardTransitionChanged(
                    new TransitionStep(
                            /* from= */ KeyguardState.GONE,
                            /* to= */ KeyguardState.AOD));
            mController.setQsFullScreen(true);
            mController.getView().addContainerView(mock(ExpandableNotificationRow.class));
        }
    }

    private void initController(boolean viewIsAttached) {
        when(mNotificationStackScrollLayout.isAttachedToWindow()).thenReturn(viewIsAttached);
        ViewTreeObserver viewTreeObserver = mock(ViewTreeObserver.class);
        when(mNotificationStackScrollLayout.getViewTreeObserver())
                .thenReturn(viewTreeObserver);
        when(mNotificationStackScrollLayout.getContext()).thenReturn(getContext());
        mController = new NotificationStackScrollLayoutController(
                mNotificationStackScrollLayout,
                true,
                mNotificationGutsManager,
                mNotificationsController,
                mVisibilityProvider,
                mHeadsUpManager,
                mNotificationRoundnessManager,
                mTunerService,
                mDeviceProvisionedController,
                mDynamicPrivacyController,
                mConfigurationController,
                mSysuiStatusBarStateController,
                mKeyguardMediaController,
                mKeyguardBypassController,
                mPowerInteractor,
                mPrimaryBouncerInteractor,
                mKeyguardTransitionRepo,
                mZenModeController,
                mNotificationLockscreenUserManager,
                mMetricsLogger,
                mColorUpdateLogger,
                mDumpManager,
                new FalsingCollectorFake(),
                new FalsingManagerFake(),
                mNotificationSwipeHelperBuilder,
                mScrimController,
                mGroupExpansionManager,
                mSilentHeaderController,
                mNotifPipeline,
                mNotifCollection,
                mLockscreenShadeTransitionController,
                mUiEventLogger,
                mRemoteInputManager,
                mVisibilityLocationProviderDelegator,
                mActiveNotificationsInteractor,
                mSeenNotificationsInteractor,
                mViewBinder,
                mShadeController,
                mSceneContainerFlags,
                mWindowRootView,
                mNotificationStackAppearanceInteractor,
                mJankMonitor,
                mStackLogger,
                mLogger,
                mNotificationStackSizeCalculator,
                mFeatureFlags,
                mNotificationTargetsHelper,
                mSecureSettings,
                mock(NotificationDismissibilityProvider.class),
                mActivityStarter,
                new ResourcesSplitShadeStateController(),
                mSensitiveNotificationProtectionController);
    }

    static class LogMatcher implements ArgumentMatcher<LogMaker> {
        private int mCategory, mType;

        LogMatcher(int category, int type) {
            mCategory = category;
            mType = type;
        }
        public boolean matches(LogMaker l) {
            return (l.getCategory() == mCategory)
                    && (l.getType() == mType);
        }

        public String toString() {
            return String.format("LogMaker(%d, %d)", mCategory, mType);
        }
    }
}
