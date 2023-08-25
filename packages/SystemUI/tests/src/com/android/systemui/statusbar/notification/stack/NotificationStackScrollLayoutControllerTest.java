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

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_ALL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.metrics.LogMaker;
import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.controls.ui.KeyguardMediaController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.OnMenuEventListener;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider;
import com.android.systemui.statusbar.notification.collection.provider.SeenNotificationsProviderImpl;
import com.android.systemui.statusbar.notification.collection.provider.VisibilityLocationProviderDelegator;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.NotifStats;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController.NotificationPanelEvent;
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
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

import java.util.Optional;

/**
 * Tests for {@link NotificationStackScrollLayoutController}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class NotificationStackScrollLayoutControllerTest extends SysuiTestCase {

    @Mock private NotificationGutsManager mNotificationGutsManager;
    @Mock private NotificationVisibilityProvider mVisibilityProvider;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
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
    @Mock private NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private DumpManager mDumpManager;
    @Mock private Resources mResources;
    @Mock(answer = Answers.RETURNS_SELF)
    private NotificationSwipeHelper.Builder mNotificationSwipeHelperBuilder;
    @Mock private NotificationSwipeHelper mNotificationSwipeHelper;
    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private ScrimController mScrimController;
    @Mock private GroupExpansionManager mGroupExpansionManager;
    @Mock private SectionHeaderController mSilentHeaderController;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private NotifPipelineFlags mNotifPipelineFlags;
    @Mock private NotifCollection mNotifCollection;
    @Mock private UiEventLogger mUiEventLogger;
    @Mock private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private VisibilityLocationProviderDelegator mVisibilityLocationProviderDelegator;
    @Mock private ShadeController mShadeController;
    @Mock private InteractionJankMonitor mJankMonitor;
    @Mock private StackStateLogger mStackLogger;
    @Mock private NotificationStackScrollLogger mLogger;
    @Mock private NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private NotificationTargetsHelper mNotificationTargetsHelper;
    @Mock private SecureSettings mSecureSettings;
    @Mock private NotificationIconAreaController mIconAreaController;
    @Mock private ActivityStarter mActivityStarter;

    @Captor
    private ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerArgumentCaptor;

    private final SeenNotificationsProviderImpl mSeenNotificationsProvider =
            new SeenNotificationsProviderImpl();

    private NotificationStackScrollLayoutController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mNotificationSwipeHelperBuilder.build()).thenReturn(mNotificationSwipeHelper);
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
    public void testUpdateEmptyShadeView_bouncerShowing_hideEmptyView() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        when(mCentralSurfaces.isBouncerShowing()).thenReturn(true);
        setupShowEmptyShadeViewState(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ false,
                /* areNotificationsHiddenInShade= */ false);
    }

    @Test
    public void testUpdateEmptyShadeView_bouncerNotShowing_showEmptyView() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(false);
        initController(/* viewIsAttached= */ true);

        when(mCentralSurfaces.isBouncerShowing()).thenReturn(false);
        setupShowEmptyShadeViewState(true);
        reset(mNotificationStackScrollLayout);
        mController.updateShowEmptyShadeView();
        verify(mNotificationStackScrollLayout).updateEmptyShadeView(
                /* visible= */ true,
                /* areNotificationsHiddenInShade= */ false);
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
    public void testSetNotifStats_updatesHasFilteredOutSeenNotifications() {
        initController(/* viewIsAttached= */ true);
        mSeenNotificationsProvider.setHasFilteredOutSeenNotifications(true);
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
    public void updateImportantForAccessibility_noChild_onKeyGuard_notImportantForA11y() {
        // GIVEN: Controller is attached, active notifications is empty,
        // and mNotificationStackScrollLayout.onKeyguard() is true
        initController(/* viewIsAttached= */ true);
        when(mNotificationStackScrollLayout.onKeyguard()).thenReturn(true);
        mController.getNotifStackController().setNotifStats(NotifStats.getEmpty());

        // WHEN: call updateImportantForAccessibility
        mController.updateImportantForAccessibility();

        // THEN: mNotificationStackScrollLayout should not be important for A11y
        verify(mNotificationStackScrollLayout)
                .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Test
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

        // WHEN: call updateImportantForAccessibility
        mController.updateImportantForAccessibility();

        // THEN: mNotificationStackScrollLayout should be important for A11y
        verify(mNotificationStackScrollLayout)
                .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Test
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

        // WHEN: call updateImportantForAccessibility
        mController.updateImportantForAccessibility();

        // THEN: mNotificationStackScrollLayout should be important for A11y
        verify(mNotificationStackScrollLayout)
                .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Test
    public void updateImportantForAccessibility_noChild_notOnKeyGuard_importantForA11y() {
        // GIVEN: Controller is attached, active notifications is empty,
        // and mNotificationStackScrollLayout.onKeyguard() is false
        initController(/* viewIsAttached= */ true);
        when(mNotificationStackScrollLayout.onKeyguard()).thenReturn(false);
        mController.getNotifStackController().setNotifStats(NotifStats.getEmpty());

        // WHEN: call updateImportantForAccessibility
        mController.updateImportantForAccessibility();

        // THEN: mNotificationStackScrollLayout should be important for A11y
        verify(mNotificationStackScrollLayout)
                .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    private LogMaker logMatcher(int category, int type) {
        return argThat(new LogMatcher(category, type));
    }

    private void setupShowEmptyShadeViewState(boolean toShow) {
        if (toShow) {
            when(mSysuiStatusBarStateController.getCurrentOrUpcomingState()).thenReturn(SHADE);
            mController.setQsFullScreen(false);
            mController.getView().removeAllViews();
        } else {
            when(mSysuiStatusBarStateController.getCurrentOrUpcomingState()).thenReturn(KEYGUARD);
            mController.setQsFullScreen(true);
            mController.getView().addContainerView(mock(ExpandableNotificationRow.class));
        }
    }

    private void initController(boolean viewIsAttached) {
        when(mNotificationStackScrollLayout.isAttachedToWindow()).thenReturn(viewIsAttached);

        mController = new NotificationStackScrollLayoutController(
                mNotificationStackScrollLayout,
                true,
                mNotificationGutsManager,
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
                mZenModeController,
                mNotificationLockscreenUserManager,
                Optional.<NotificationListViewModel>empty(),
                mMetricsLogger,
                mDumpManager,
                new FalsingCollectorFake(),
                new FalsingManagerFake(),
                mResources,
                mNotificationSwipeHelperBuilder,
                mCentralSurfaces,
                mScrimController,
                mGroupExpansionManager,
                mSilentHeaderController,
                mNotifPipeline,
                mNotifPipelineFlags,
                mNotifCollection,
                mLockscreenShadeTransitionController,
                mUiEventLogger,
                mRemoteInputManager,
                mVisibilityLocationProviderDelegator,
                mSeenNotificationsProvider,
                mShadeController,
                mJankMonitor,
                mStackLogger,
                mLogger,
                mNotificationStackSizeCalculator,
                mIconAreaController,
                mFeatureFlags,
                mNotificationTargetsHelper,
                mSecureSettings,
                mock(NotificationDismissibilityProvider.class),
                mActivityStarter
        );
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
