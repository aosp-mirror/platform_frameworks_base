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
import android.view.LayoutInflater;

import androidx.test.filters.SmallTest;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollectorFake;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.media.KeyguardMediaController;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.OnMenuEventListener;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController.NotificationPanelEvent;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.shade.transition.ShadeTransitionController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    @Mock private SysuiColorExtractor mColorExtractor;
    @Mock private NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private Resources mResources;
    @Mock(answer = Answers.RETURNS_SELF)
    private NotificationSwipeHelper.Builder mNotificationSwipeHelperBuilder;
    @Mock private NotificationSwipeHelper mNotificationSwipeHelper;
    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private ScrimController mScrimController;
    @Mock private NotificationGroupManagerLegacy mLegacyGroupManager;
    @Mock private SectionHeaderController mSilentHeaderController;
    @Mock private NotifPipelineFlags mNotifPipelineFlags;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private NotifCollection mNotifCollection;
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private IStatusBarService mIStatusBarService;
    @Mock private UiEventLogger mUiEventLogger;
    @Mock private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock private LayoutInflater mLayoutInflater;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private VisualStabilityManager mVisualStabilityManager;
    @Mock private ShadeController mShadeController;
    @Mock private InteractionJankMonitor mJankMonitor;
    @Mock private StackStateLogger mStackLogger;
    @Mock private NotificationStackScrollLogger mLogger;
    @Mock private NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    @Mock private ShadeTransitionController mShadeTransitionController;

    @Captor
    private ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerArgumentCaptor;

    private NotificationStackScrollLayoutController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mNotificationSwipeHelperBuilder.build()).thenReturn(mNotificationSwipeHelper);
        when(mNotifPipelineFlags.isNewPipelineEnabled()).thenReturn(false);

        mController = new NotificationStackScrollLayoutController(
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
                mColorExtractor,
                mNotificationLockscreenUserManager,
                mMetricsLogger,
                new FalsingCollectorFake(),
                new FalsingManagerFake(),
                mResources,
                mNotificationSwipeHelperBuilder,
                mCentralSurfaces,
                mScrimController,
                mLegacyGroupManager,
                mLegacyGroupManager,
                mSilentHeaderController,
                mNotifPipelineFlags,
                mNotifPipeline,
                mNotifCollection,
                mEntryManager,
                mLockscreenShadeTransitionController,
                mShadeTransitionController,
                mIStatusBarService,
                mUiEventLogger,
                mLayoutInflater,
                mRemoteInputManager,
                mVisualStabilityManager,
                mShadeController,
                mJankMonitor,
                mStackLogger,
                mLogger,
                mNotificationStackSizeCalculator
        );

        when(mNotificationStackScrollLayout.isAttachedToWindow()).thenReturn(true);
    }

    @Test
    public void testAttach_viewAlreadyAttached() {
        mController.attach(mNotificationStackScrollLayout);

        verify(mConfigurationController).addCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }
    @Test
    public void testAttach_viewAttachedAfterInit() {
        when(mNotificationStackScrollLayout.isAttachedToWindow()).thenReturn(false);

        mController.attach(mNotificationStackScrollLayout);

        verify(mConfigurationController, never()).addCallback(
                any(ConfigurationController.ConfigurationListener.class));

        mController.mOnAttachStateChangeListener.onViewAttachedToWindow(
                mNotificationStackScrollLayout);

        verify(mConfigurationController).addCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }

    @Test
    public void testOnDensityOrFontScaleChanged_reInflatesFooterViews() {
        mController.attach(mNotificationStackScrollLayout);
        mController.mConfigurationListener.onDensityOrFontScaleChanged();
        verify(mNotificationStackScrollLayout).reinflateViews();
    }

    @Test
    public void testUpdateEmptyShadeView_notificationsVisible_zenHiding() {
        when(mZenModeController.areNotificationsHiddenInShade()).thenReturn(true);
        mController.attach(mNotificationStackScrollLayout);

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
        mController.attach(mNotificationStackScrollLayout);

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
        mController.attach(mNotificationStackScrollLayout);
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
    public void testOnUserChange_verifySensitiveProfile() {
        when(mNotificationLockscreenUserManager.isAnyProfilePublicMode()).thenReturn(true);

        ArgumentCaptor<UserChangedListener> userChangedCaptor = ArgumentCaptor
                .forClass(UserChangedListener.class);

        mController.attach(mNotificationStackScrollLayout);
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

        mController.attach(mNotificationStackScrollLayout);
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

        mController.attach(mNotificationStackScrollLayout);
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

        mController.attach(mNotificationStackScrollLayout);
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

        mController.attach(mNotificationStackScrollLayout);

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
        mController.attach(mNotificationStackScrollLayout);
        verify(mNotificationStackScrollLayout).setIsRemoteInputActive(false);
        RemoteInputController.Callback callback = callbackCaptor.getValue();
        callback.onRemoteInputActive(true);
        verify(mNotificationStackScrollLayout).setIsRemoteInputActive(true);
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
