/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.statusbar.ui;

import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.DreamOverlayNotificationCountProvider;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.DreamOverlayStatusBarItemsProvider;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.core.FakeLogBuffer;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateListener;
import com.android.systemui.util.time.DateFormatUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4.class)
public class AmbientStatusBarViewControllerTest extends SysuiTestCase {
    private static final String NOTIFICATION_INDICATOR_FORMATTER_STRING =
            "{count, plural, =1 {# notification} other {# notifications}}";

    @Mock
    MockAmbientStatusBarView mView;
    @Mock
    Resources mResources;
    @Mock
    AlarmManager mAlarmManager;
    @Mock
    NextAlarmController mNextAlarmController;
    @Mock
    DateFormatUtil mDateFormatUtil;
    @Mock
    IndividualSensorPrivacyController mSensorPrivacyController;
    @Mock
    ZenModeController mZenModeController;
    @Mock
    DreamOverlayNotificationCountProvider mDreamOverlayNotificationCountProvider;
    @Mock
    StatusBarWindowStateController mStatusBarWindowStateController;
    @Mock
    DreamOverlayStatusBarItemsProvider mDreamOverlayStatusBarItemsProvider;
    @Mock
    DreamOverlayStatusBarItemsProvider.StatusBarItem mStatusBarItem;
    @Mock
    View mStatusBarItemView;
    @Mock
    DreamOverlayStateController mDreamOverlayStateController;
    @Mock
    UserTracker mUserTracker;

    LogBuffer mLogBuffer = FakeLogBuffer.Factory.Companion.create();

    @Captor
    private ArgumentCaptor<DreamOverlayStateController.Callback> mCallbackCaptor;

    private final Executor mMainExecutor = Runnable::run;

    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);

    private final FakeWifiRepository mWifiRepository = mKosmos.getFakeWifiRepository();

    AmbientStatusBarViewController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mResources.getString(R.string.dream_overlay_status_bar_notification_indicator))
                .thenReturn(NOTIFICATION_INDICATOR_FORMATTER_STRING);
        doCallRealMethod().when(mView).setVisibility(anyInt());
        doCallRealMethod().when(mView).getVisibility();
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());

        mController = new AmbientStatusBarViewController(
                mView,
                mResources,
                mMainExecutor,
                mAlarmManager,
                mNextAlarmController,
                mDateFormatUtil,
                mSensorPrivacyController,
                Optional.of(mDreamOverlayNotificationCountProvider),
                mZenModeController,
                mStatusBarWindowStateController,
                mDreamOverlayStatusBarItemsProvider,
                mDreamOverlayStateController,
                mUserTracker,
                mKosmos.getWifiInteractor(),
                mLogBuffer);
    }

    @Test
    public void testOnViewAttachedAddsCallbacks() {
        mController.onViewAttached();
        verify(mNextAlarmController).addCallback(any());
        verify(mSensorPrivacyController).addCallback(any());
        verify(mZenModeController).addCallback(any());
        verify(mDreamOverlayNotificationCountProvider).addCallback(any());
        verify(mDreamOverlayStatusBarItemsProvider).addCallback(any());
        verify(mDreamOverlayStateController).addCallback(any());
    }

    @Test
    public void testWifiIconShownWhenWifiUnavailable() {
        mController.onViewAttached();
        mController.updateWifiUnavailableStatusIcon(false);

        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, true, null);
    }

    @Test
    public void testWifiIconHiddenWhenWifiAvailable() {
        mController.onViewAttached();
        mController.updateWifiUnavailableStatusIcon(true);

        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_WIFI_UNAVAILABLE, false, null);
    }

    @Test
    public void testOnViewAttachedShowsAlarmIconWhenAlarmExists() {
        final AlarmManager.AlarmClockInfo alarmClockInfo =
                new AlarmManager.AlarmClockInfo(1L, null);
        when(mAlarmManager.getNextAlarmClock(anyInt())).thenReturn(alarmClockInfo);
        mController.onViewAttached();
        verify(mView).showIcon(
                eq(AmbientStatusBarView.STATUS_ICON_ALARM_SET), eq(true), any());
    }

    @Test
    public void testOnViewAttachedHidesAlarmIconWhenNoAlarmExists() {
        when(mAlarmManager.getNextAlarmClock(anyInt())).thenReturn(null);
        mController.onViewAttached();
        verify(mView).showIcon(
                eq(AmbientStatusBarView.STATUS_ICON_ALARM_SET), eq(false), isNull());
    }

    @Test
    public void testOnViewAttachedShowsMicIconWhenDisabled() {
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE))
                .thenReturn(true);
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA))
                .thenReturn(false);
        mController.onViewAttached();
        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_MIC_DISABLED, true, null);
    }

    @Test
    public void testOnViewAttachedShowsCameraIconWhenDisabled() {
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE))
                .thenReturn(false);
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA))
                .thenReturn(true);
        mController.onViewAttached();
        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_CAMERA_DISABLED, true, null);
    }

    @Test
    public void testOnViewAttachedShowsMicCameraIconWhenDisabled() {
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE))
                .thenReturn(true);
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA))
                .thenReturn(true);
        mController.onViewAttached();
        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_MIC_CAMERA_DISABLED, true, null);
    }

    @Test
    public void testOnViewAttachedShowsNotificationsIconWhenNotificationsExist() {
        mController.onViewAttached();

        final ArgumentCaptor<DreamOverlayNotificationCountProvider.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayNotificationCountProvider.Callback.class);
        verify(mDreamOverlayNotificationCountProvider).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onNotificationCountChanged(1);

        verify(mView).showIcon(
                eq(AmbientStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(true), any());
    }

    @Test
    public void testOnViewAttachedHidesNotificationsIconWhenNoNotificationsExist() {
        mController.onViewAttached();

        final ArgumentCaptor<DreamOverlayNotificationCountProvider.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayNotificationCountProvider.Callback.class);
        verify(mDreamOverlayNotificationCountProvider).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onNotificationCountChanged(0);

        verify(mView).showIcon(
                eq(AmbientStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(false), isNull());
    }

    @Test
    public void testNotificationsIconNotShownWhenCountProviderAbsent() {
        AmbientStatusBarViewController controller = new AmbientStatusBarViewController(
                mView,
                mResources,
                mMainExecutor,
                mAlarmManager,
                mNextAlarmController,
                mDateFormatUtil,
                mSensorPrivacyController,
                Optional.empty(),
                mZenModeController,
                mStatusBarWindowStateController,
                mDreamOverlayStatusBarItemsProvider,
                mDreamOverlayStateController,
                mUserTracker,
                mKosmos.getWifiInteractor(),
                mLogBuffer);
        controller.onViewAttached();
        verify(mView, never()).showIcon(
                eq(AmbientStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(true), any());
    }

    @Test
    public void testOnViewAttachedShowsPriorityModeIconWhenEnabled() {
        when(mZenModeController.getZen()).thenReturn(
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        mController.onViewAttached();
        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_PRIORITY_MODE_ON, true, null);
    }

    @Test
    public void testOnViewAttachedHidesPriorityModeIconWhenDisabled() {
        when(mZenModeController.getZen()).thenReturn(
                Settings.Global.ZEN_MODE_OFF);
        mController.onViewAttached();
        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_PRIORITY_MODE_ON, false, null);
    }

    @Test
    public void testOnViewDetachedRemovesCallbacks() {
        mController.onViewDetached();
        verify(mNextAlarmController).removeCallback(any());
        verify(mSensorPrivacyController).removeCallback(any());
        verify(mZenModeController).removeCallback(any());
        verify(mDreamOverlayNotificationCountProvider).removeCallback(any());
        verify(mDreamOverlayStatusBarItemsProvider).removeCallback(any());
        verify(mDreamOverlayStateController).removeCallback(any());
    }

    @Test
    public void testOnViewDetachedRemovesViews() {
        mController.onViewDetached();
        verify(mView).removeAllExtraStatusBarItemViews();
    }

    @Test
    public void testNotificationsIconShownWhenNotificationAdded() {
        mController.onViewAttached();

        final ArgumentCaptor<DreamOverlayNotificationCountProvider.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayNotificationCountProvider.Callback.class);
        verify(mDreamOverlayNotificationCountProvider).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onNotificationCountChanged(1);

        verify(mView).showIcon(
                eq(AmbientStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(true), any());
    }

    @Test
    public void testNotificationsIconHiddenWhenLastNotificationRemoved() {
        mController.onViewAttached();

        final ArgumentCaptor<DreamOverlayNotificationCountProvider.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayNotificationCountProvider.Callback.class);
        verify(mDreamOverlayNotificationCountProvider).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onNotificationCountChanged(0);

        verify(mView).showIcon(
                eq(AmbientStatusBarView.STATUS_ICON_NOTIFICATIONS), eq(false), any());
    }

    @Test
    public void testMicCameraIconShownWhenSensorsBlocked() {
        mController.onViewAttached();

        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.MICROPHONE))
                .thenReturn(true);
        when(mSensorPrivacyController.isSensorBlocked(SensorPrivacyManager.Sensors.CAMERA))
                .thenReturn(true);

        final ArgumentCaptor<IndividualSensorPrivacyController.Callback> callbackCapture =
                ArgumentCaptor.forClass(IndividualSensorPrivacyController.Callback.class);
        verify(mSensorPrivacyController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onSensorBlockedChanged(
                SensorPrivacyManager.Sensors.MICROPHONE, true);

        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_MIC_CAMERA_DISABLED, true, null);
    }

    @Test
    public void testPriorityModeIconShownWhenZenModeEnabled() {
        mController.onViewAttached();

        when(mZenModeController.getZen()).thenReturn(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);

        final ArgumentCaptor<ZenModeController.Callback> callbackCapture =
                ArgumentCaptor.forClass(ZenModeController.Callback.class);
        verify(mZenModeController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onZenChanged(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);

        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_PRIORITY_MODE_ON, true, null);
    }

    @Test
    public void testPriorityModeIconHiddenWhenZenModeDisabled() {
        when(mZenModeController.getZen()).thenReturn(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
                .thenReturn(Settings.Global.ZEN_MODE_OFF);
        mController.onViewAttached();

        final ArgumentCaptor<ZenModeController.Callback> callbackCapture =
                ArgumentCaptor.forClass(ZenModeController.Callback.class);
        verify(mZenModeController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onZenChanged(Settings.Global.ZEN_MODE_OFF);

        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_PRIORITY_MODE_ON, false, null);
    }

    @Test
    public void testAssistantAttentionIconShownWhenAttentionGained() {
        mController.onViewAttached();

        when(mDreamOverlayStateController.hasAssistantAttention()).thenReturn(true);

        final ArgumentCaptor<DreamOverlayStateController.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onStateChanged();

        verify(mView).showIcon(
                AmbientStatusBarView.STATUS_ICON_ASSISTANT_ATTENTION_ACTIVE, true, null);
    }

    @Test
    public void testStatusBarHiddenWhenSystemStatusBarShown() {
        mController.onViewAttached();

        updateEntryAnimationsFinished();
        updateStatusBarWindowState(true);

        assertThat(mView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void testStatusBarShownWhenSystemStatusBarHidden() {
        mController.onViewAttached();
        reset(mView);

        updateEntryAnimationsFinished();
        updateStatusBarWindowState(false);

        assertThat(mView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testUnattachedStatusBarVisibilityUnchangedWhenSystemStatusBarHidden() {
        mController.onViewAttached();
        updateEntryAnimationsFinished();
        mController.onViewDetached();
        reset(mView);

        updateStatusBarWindowState(true);

        verify(mView, never()).setVisibility(anyInt());
    }

    @Test
    public void testNoChangeToVisibilityBeforeDreamStartedWhenStatusBarHidden() {
        mController.onViewAttached();

        // Trigger status bar window state change.
        final StatusBarWindowStateListener listener = updateStatusBarWindowState(false);

        // Verify no visibility change because dream not started.
        verify(mView, never()).setVisibility(anyInt());

        // Dream entry animations finished.
        updateEntryAnimationsFinished();

        // Trigger another status bar window state change, and verify visibility change.
        listener.onStatusBarWindowStateChanged(WINDOW_STATE_HIDDEN);
        assertThat(mView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testExtraStatusBarItemSetWhenItemsChange() {
        mController.onViewAttached();
        when(mStatusBarItem.getView()).thenReturn(mStatusBarItemView);

        final ArgumentCaptor<DreamOverlayStatusBarItemsProvider.Callback>
                callbackCapture = ArgumentCaptor.forClass(
                DreamOverlayStatusBarItemsProvider.Callback.class);
        verify(mDreamOverlayStatusBarItemsProvider).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onStatusBarItemsChanged(List.of(mStatusBarItem));

        verify(mView).setExtraStatusBarItemViews(List.of(mStatusBarItemView));
    }

    @Test
    public void testLowLightHidesStatusBar() {
        when(mDreamOverlayStateController.isLowLightActive()).thenReturn(true);
        mController.onViewAttached();
        updateEntryAnimationsFinished();

        final ArgumentCaptor<DreamOverlayStateController.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onStateChanged();

        assertThat(mView.getVisibility()).isEqualTo(View.INVISIBLE);
        reset(mView);

        when(mDreamOverlayStateController.isLowLightActive()).thenReturn(false);
        callbackCapture.getValue().onStateChanged();

        assertThat(mView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testNoChangeToVisibilityBeforeDreamStartedWhenLowLightStateChange() {
        when(mDreamOverlayStateController.isLowLightActive()).thenReturn(false);
        mController.onViewAttached();

        // No change to visibility because dream not fully started.
        verify(mView, never()).setVisibility(anyInt());

        // Dream entry animations finished.
        updateEntryAnimationsFinished();

        // Trigger state change and verify visibility changed.
        final ArgumentCaptor<DreamOverlayStateController.Callback> callbackCapture =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(callbackCapture.capture());
        callbackCapture.getValue().onStateChanged();

        assertThat(mView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void testDreamOverlayStatusBarVisibleSetToFalseOnDetach() {
        mController.onViewAttached();
        mController.onViewDetached();
        verify(mDreamOverlayStateController).setDreamOverlayStatusBarVisible(false);
    }

    private StatusBarWindowStateListener updateStatusBarWindowState(boolean show) {
        when(mStatusBarWindowStateController.windowIsShowing()).thenReturn(show);
        final ArgumentCaptor<StatusBarWindowStateListener>
                callbackCapture = ArgumentCaptor.forClass(StatusBarWindowStateListener.class);
        verify(mStatusBarWindowStateController).addListener(callbackCapture.capture());
        final StatusBarWindowStateListener listener = callbackCapture.getValue();
        listener.onStatusBarWindowStateChanged(show ? WINDOW_STATE_SHOWING : WINDOW_STATE_HIDDEN);
        return listener;
    }

    private void updateEntryAnimationsFinished() {
        when(mDreamOverlayStateController.areEntryAnimationsFinished()).thenReturn(true);

        verify(mDreamOverlayStateController).addCallback(mCallbackCaptor.capture());
        final DreamOverlayStateController.Callback callback = mCallbackCaptor.getValue();
        callback.onStateChanged();
    }

    private static class MockAmbientStatusBarView extends AmbientStatusBarView {
        private int mVisibility = View.VISIBLE;

        private MockAmbientStatusBarView(Context context) {
            super(context);
        }

        @Override
        public void setVisibility(int visibility) {
            mVisibility = visibility;
        }

        @Override
        public int getVisibility() {
            return mVisibility;
        }
    }
}
