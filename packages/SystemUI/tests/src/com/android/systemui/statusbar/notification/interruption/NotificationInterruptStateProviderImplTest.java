/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.interruption;

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.Notification.GROUP_ALERT_SUMMARY;
import static android.app.Notification.VISIBILITY_PRIVATE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.VISIBILITY_NO_OVERRIDE;
import static android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED;
import static android.provider.Settings.Global.HEADS_UP_ON;

import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Flags;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider.FullScreenIntentDecision;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.FakeEventLog;
import com.android.systemui.util.settings.FakeGlobalSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests for the interruption state provider which understands whether the system & notification
 * is in a state allowing a particular notification to hun, pulse, or bubble.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class NotificationInterruptStateProviderImplTest extends SysuiTestCase {

    @Mock
    PowerManager mPowerManager;
    @Mock
    AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock
    StatusBarStateController mStatusBarStateController;
    @Mock
    KeyguardStateController mKeyguardStateController;
    @Mock
    HeadsUpManager mHeadsUpManager;
    @Mock
    NotificationInterruptLogger mLogger;
    @Mock
    BatteryController mBatteryController;
    @Mock
    Handler mMockHandler;
    @Mock
    NotifPipelineFlags mFlags;
    @Mock
    KeyguardNotificationVisibilityProvider mKeyguardNotificationVisibilityProvider;
    UiEventLoggerFake mUiEventLoggerFake;
    @Mock
    PendingIntent mPendingIntent;
    @Mock
    UserTracker mUserTracker;
    @Mock
    DeviceProvisionedController mDeviceProvisionedController;
    FakeSystemClock mSystemClock;
    FakeGlobalSettings mGlobalSettings;
    FakeEventLog mEventLog;

    private NotificationInterruptStateProviderImpl mNotifInterruptionStateProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());

        mUiEventLoggerFake = new UiEventLoggerFake();
        mSystemClock = new FakeSystemClock();
        mGlobalSettings = new FakeGlobalSettings();
        mGlobalSettings.putInt(HEADS_UP_NOTIFICATIONS_ENABLED, HEADS_UP_ON);
        mEventLog = new FakeEventLog();

        mNotifInterruptionStateProvider =
                new NotificationInterruptStateProviderImpl(
                        mPowerManager,
                        mAmbientDisplayConfiguration,
                        mBatteryController,
                        mStatusBarStateController,
                        mKeyguardStateController,
                        mHeadsUpManager,
                        mLogger,
                        mMockHandler,
                        mFlags,
                        mKeyguardNotificationVisibilityProvider,
                        mUiEventLoggerFake,
                        mUserTracker,
                        mDeviceProvisionedController,
                        mSystemClock,
                        mGlobalSettings,
                        mEventLog);
        mNotifInterruptionStateProvider.mUseHeadsUp = true;
    }

    /**
     * Sets up the state such that any requests to
     * {@link NotificationInterruptStateProviderImpl#shouldHeadsUp(NotificationEntry)} will
     * pass as long its provided NotificationEntry fulfills importance & DND checks.
     */
    private void ensureStateForHeadsUpWhenAwake() throws RemoteException {
        when(mHeadsUpManager.isSnoozed(any())).thenReturn(false);

        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mPowerManager.isScreenOn()).thenReturn(true);
    }

    /**
     * Sets up the state such that any requests to
     * {@link NotificationInterruptStateProviderImpl#shouldHeadsUp(NotificationEntry)} will
     * pass as long its provided NotificationEntry fulfills importance & DND checks.
     */
    private void ensureStateForHeadsUpWhenDozing() {
        when(mStatusBarStateController.isDozing()).thenReturn(true);
        when(mAmbientDisplayConfiguration.pulseOnNotificationEnabled(anyInt())).thenReturn(true);
    }

    @Test
    public void testDefaultSuppressorDoesNotSuppress() {
        // GIVEN a suppressor without any overrides
        final NotificationInterruptSuppressor defaultSuppressor =
                new NotificationInterruptSuppressor() {
                    @Override
                    public String getName() {
                        return "defaultSuppressor";
                    }
                };

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);

        // THEN this suppressor doesn't suppress anything by default
        assertThat(defaultSuppressor.suppressAwakeHeadsUp(entry)).isFalse();
        assertThat(defaultSuppressor.suppressAwakeInterruptions(entry)).isFalse();
        assertThat(defaultSuppressor.suppressInterruptions(entry)).isFalse();
    }

    @Test
    public void testShouldHeadsUpAwake() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();
    }

    @Test
    public void testShouldNotHeadsUp_suppressedForGroups() throws RemoteException {
        // GIVEN state for "heads up when awake" is true
        ensureStateForHeadsUpWhenAwake();

        // WHEN the alert for a grouped notification is suppressed
        // see {@link android.app.Notification#GROUP_ALERT_CHILDREN}
        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(new Notification.Builder(getContext(), "a")
                        .setGroup("a")
                        .setGroupSummary(true)
                        .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                        .build())
                .setImportance(IMPORTANCE_DEFAULT)
                .build();

        // THEN this entry shouldn't HUN
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    @Test
    public void testShouldHeadsUpWhenDozing() {
        ensureStateForHeadsUpWhenDozing();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        modifyRanking(entry)
                .setVisibilityOverride(VISIBILITY_NO_OVERRIDE)
                .build();

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();
    }

    @Test
    public void testShouldHeadsUpWhenDozing_hiddenOnLockscreen() {
        ensureStateForHeadsUpWhenDozing();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        modifyRanking(entry)
                .setVisibilityOverride(VISIBILITY_PRIVATE)
                .build();

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    @Test
    public void testShouldNotHeadsUpWhenDozing_pulseDisabled() {
        // GIVEN state for "heads up when dozing" is true
        ensureStateForHeadsUpWhenDozing();

        // WHEN pulsing (HUNs when dozing) is disabled
        when(mAmbientDisplayConfiguration.pulseOnNotificationEnabled(anyInt())).thenReturn(false);

        // THEN this entry shouldn't HUN
        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    @Test
    public void testShouldNotHeadsUpWhenDozing_notDozing() {
        // GIVEN state for "heads up when dozing" is true
        ensureStateForHeadsUpWhenDozing();

        // WHEN we're not dozing (in ambient display or sleeping)
        when(mStatusBarStateController.isDozing()).thenReturn(false);

        // THEN this entry shouldn't HUN
        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * In DND ambient effects can be suppressed
     * {@link android.app.NotificationManager.Policy#SUPPRESSED_EFFECT_AMBIENT}.
     */
    @Test
    public void testShouldNotHeadsUpWhenDozing_suppressingAmbient() {
        ensureStateForHeadsUpWhenDozing();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        modifyRanking(entry)
                .setSuppressedVisualEffects(SUPPRESSED_EFFECT_AMBIENT)
                .build();

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    @Test
    public void testShouldNotHeadsUpWhenDozing_lessImportant() {
        ensureStateForHeadsUpWhenDozing();

        // Notifications that are < {@link android.app.NotificationManager#IMPORTANCE_DEFAULT} don't
        // get to pulse
        NotificationEntry entry = createNotification(IMPORTANCE_LOW);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    @Test
    public void testShouldHeadsUp() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();
    }

    /**
     * If the notification is a bubble, and the user is not on AOD / lockscreen, then
     * the bubble is shown rather than the heads up.
     */
    @Test
    public void testShouldNotHeadsUp_bubble() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        // Bubble bit only applies to interruption when we're in the shade
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(createBubble())).isFalse();
    }

    /**
     * If we're not allowed to alert in general, we shouldn't be shown as heads up.
     */
    @Test
    public void testShouldNotHeadsUp_filtered() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();
        // Make canAlertCommon false by saying it's filtered out
        when(mKeyguardNotificationVisibilityProvider.shouldHideNotification(any()))
                .thenReturn(true);

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * In DND HUN peek effects can be suppressed
     * {@link android.app.NotificationManager.Policy#SUPPRESSED_EFFECT_PEEK}.
     */
    @Test
    public void testShouldNotHeadsUp_suppressPeek() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        modifyRanking(entry)
                .setSuppressedVisualEffects(SUPPRESSED_EFFECT_PEEK)
                .build();

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * Notifications that are < {@link android.app.NotificationManager#IMPORTANCE_HIGH} don't get
     * to show as a heads up.
     */
    @Test
    public void testShouldNotHeadsUp_lessImportant() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * If the device is not in use then we shouldn't be shown as heads up.
     */
    @Test
    public void testShouldNotHeadsUp_deviceNotInUse() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();
        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);

        // Device is not in use if screen is not on
        when(mPowerManager.isScreenOn()).thenReturn(false);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();

        // Also not in use if screen is on but we're showing screen saver / "dreaming"
        when(mPowerManager.isDeviceIdleMode()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(true);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    @Test
    public void testShouldNotHeadsUp_headsUpSuppressed() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        // If a suppressor is suppressing heads up, then it shouldn't be shown as a heads up.
        mNotifInterruptionStateProvider.addSuppressor(mSuppressAwakeHeadsUp);

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    @Test
    public void testShouldNotHeadsUpAwake_awakeInterruptsSuppressed() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        // If a suppressor is suppressing heads up, then it shouldn't be shown as a heads up.
        mNotifInterruptionStateProvider.addSuppressor(mSuppressAwakeInterruptions);

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * On screen alerts don't happen when the notification is snoozed.
     */
    @Test
    public void testShouldNotHeadsUp_snoozedPackage() {
        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);

        when(mHeadsUpManager.isSnoozed(entry.getSbn().getPackageName())).thenReturn(true);

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }


    @Test
    public void testShouldNotHeadsUp_justLaunchedFullscreen() {

        // On screen alerts don't happen when that package has just launched fullscreen.
        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        entry.notifyFullScreenIntentLaunched();

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    private long makeWhenHoursAgo(long hoursAgo) {
        return mSystemClock.currentTimeMillis() - (1000 * 60 * 60 * hoursAgo);
    }

    @Test
    public void testShouldHeadsUp_oldWhen_whenNow() throws Exception {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();

        verify(mLogger, never()).logNoHeadsUpOldWhen(any(), anyLong(), anyLong());
        verify(mLogger, never()).logMaybeHeadsUpDespiteOldWhen(any(), anyLong(), anyLong(), any());
    }

    @Test
    public void testShouldHeadsUp_oldWhen_whenRecent() throws Exception {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        entry.getSbn().getNotification().when = makeWhenHoursAgo(13);

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();

        verify(mLogger, never()).logNoHeadsUpOldWhen(any(), anyLong(), anyLong());
        verify(mLogger, never()).logMaybeHeadsUpDespiteOldWhen(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisableFlags(Flags.FLAG_SORT_SECTION_BY_TIME)
    public void testShouldHeadsUp_oldWhen_whenZero() throws Exception {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        entry.getSbn().getNotification().when = 0L;

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();

        verify(mLogger, never()).logNoHeadsUpOldWhen(any(), anyLong(), anyLong());
        verify(mLogger).logMaybeHeadsUpDespiteOldWhen(eq(entry), eq(0L), anyLong(),
                eq("when <= 0"));
    }

    @Test
    public void testShouldHeadsUp_oldWhen_whenNegative() throws Exception {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        entry.getSbn().getNotification().when = -1L;

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();
        verify(mLogger, never()).logNoHeadsUpOldWhen(any(), anyLong(), anyLong());
        verify(mLogger).logMaybeHeadsUpDespiteOldWhen(eq(entry), eq(-1L), anyLong(),
                eq("when <= 0"));
    }

    @Test
    public void testShouldHeadsUp_oldWhen_hasFullScreenIntent() throws Exception {
        ensureStateForHeadsUpWhenAwake();
        long when = makeWhenHoursAgo(25);

        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silent= */ false);
        entry.getSbn().getNotification().when = when;

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();

        verify(mLogger, never()).logNoHeadsUpOldWhen(any(), anyLong(), anyLong());
        verify(mLogger).logMaybeHeadsUpDespiteOldWhen(eq(entry), eq(when), anyLong(),
                eq("full-screen intent"));
    }

    @Test
    public void testShouldHeadsUp_oldWhen_isForegroundService() throws Exception {
        ensureStateForHeadsUpWhenAwake();
        long when = makeWhenHoursAgo(25);

        NotificationEntry entry = createFgsNotification(IMPORTANCE_HIGH);
        entry.getSbn().getNotification().when = when;

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();

        verify(mLogger, never()).logNoHeadsUpOldWhen(any(), anyLong(), anyLong());
        verify(mLogger).logMaybeHeadsUpDespiteOldWhen(eq(entry), eq(when), anyLong(),
                eq("foreground service"));
    }

    @Test
    public void testShouldNotHeadsUp_oldWhen() throws Exception {
        ensureStateForHeadsUpWhenAwake();
        long when = makeWhenHoursAgo(25);

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        entry.getSbn().getNotification().when = when;

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();

        verify(mLogger).logNoHeadsUpOldWhen(eq(entry), eq(when), anyLong());
        verify(mLogger, never()).logMaybeHeadsUpDespiteOldWhen(any(), anyLong(), anyLong(), any());
    }

    @Test
    public void testShouldNotFullScreen_notPendingIntent() throws RemoteException {
        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FULL_SCREEN_INTENT);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldNotFullScreen_suppressedOnlyByDND() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        modifyRanking(entry)
                .setSuppressedVisualEffects(SUPPRESSED_EFFECT_FULL_SCREEN_INTENT)
                .build();
        when(mPowerManager.isInteractive()).thenReturn(false);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger, never()).logFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logNoFullscreen(entry, "NO_FSI_SUPPRESSED_ONLY_BY_DND");
    }

    @Test
    public void testShouldNotFullScreen_suppressedByDNDAndOther() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_LOW, /* silenced */ false);
        modifyRanking(entry)
                .setSuppressedVisualEffects(SUPPRESSED_EFFECT_FULL_SCREEN_INTENT)
                .build();
        when(mPowerManager.isInteractive()).thenReturn(false);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_SUPPRESSED_BY_DND);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger, never()).logFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logNoFullscreen(entry, "NO_FSI_SUPPRESSED_BY_DND");
    }

    @Test
    public void testShouldNotFullScreen_notHighImportance() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_DEFAULT, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_NOT_IMPORTANT_ENOUGH);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoFullscreen(entry, "NO_FSI_NOT_IMPORTANT_ENOUGH");
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldNotFullScreen_isGroupAlertSilenced() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ true);
        when(mPowerManager.isInteractive()).thenReturn(false);
        when(mStatusBarStateController.isDreaming()).thenReturn(true);
        when(mStatusBarStateController.getState()).thenReturn(KEYGUARD);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger).logNoFullscreenWarning(entry,
                "NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR: GroupAlertBehavior will prevent HUN");
        verify(mLogger, never()).logFullscreen(any(), any());

        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(1);
        UiEventLoggerFake.FakeUiEvent fakeUiEvent = mUiEventLoggerFake.get(0);
        assertThat(fakeUiEvent.eventId).isEqualTo(
                NotificationInterruptEvent.FSI_SUPPRESSED_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR.getId());
        assertThat(fakeUiEvent.uid).isEqualTo(entry.getSbn().getUid());
        assertThat(fakeUiEvent.packageName).isEqualTo(entry.getSbn().getPackageName());
    }

    @Test
    public void testShouldNotFullScreen_isSuppressedByBubbleMetadata() {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        Notification.BubbleMetadata bubbleMetadata = new Notification.BubbleMetadata.Builder("foo")
                .setSuppressNotification(true).build();
        entry.getSbn().getNotification().setBubbleMetadata(bubbleMetadata);
        when(mPowerManager.isInteractive()).thenReturn(false);
        when(mStatusBarStateController.isDreaming()).thenReturn(true);
        when(mStatusBarStateController.getState()).thenReturn(KEYGUARD);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_SUPPRESSIVE_BUBBLE_METADATA);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger).logNoFullscreenWarning(entry,
                "NO_FSI_SUPPRESSIVE_BUBBLE_METADATA: BubbleMetadata may prevent HUN");
        verify(mLogger, never()).logFullscreen(any(), any());

        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(1);
        UiEventLoggerFake.FakeUiEvent fakeUiEvent = mUiEventLoggerFake.get(0);
        assertThat(fakeUiEvent.eventId).isEqualTo(
                NotificationInterruptEvent.FSI_SUPPRESSED_SUPPRESSIVE_BUBBLE_METADATA.getId());
        assertThat(fakeUiEvent.uid).isEqualTo(entry.getSbn().getUid());
        assertThat(fakeUiEvent.packageName).isEqualTo(entry.getSbn().getPackageName());
    }

    @Test
    public void testShouldFullScreen_notInteractive() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        Notification.BubbleMetadata bubbleMetadata = new Notification.BubbleMetadata.Builder("foo")
                .setSuppressNotification(false).build();
        entry.getSbn().getNotification().setBubbleMetadata(bubbleMetadata);
        when(mPowerManager.isInteractive()).thenReturn(false);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "FSI_DEVICE_NOT_INTERACTIVE");
    }

    @Test
    public void testShouldFullScreen_isDreaming() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(true);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.FSI_DEVICE_IS_DREAMING);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "FSI_DEVICE_IS_DREAMING");
    }

    @Test
    public void testShouldFullScreen_onKeyguard() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(KEYGUARD);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.FSI_KEYGUARD_SHOWING);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "FSI_KEYGUARD_SHOWING");
    }

    @Test
    public void testShouldFullscreen_suppressedInterruptionsWhenNotProvisioned() {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(false);
        mNotifInterruptionStateProvider.addSuppressor(mSuppressInterruptions);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.FSI_NOT_PROVISIONED);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "FSI_NOT_PROVISIONED");
    }

    @Test
    public void testShouldNotFullScreen_willHun() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_EXPECTED_TO_HUN);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoFullscreen(entry, "NO_FSI_EXPECTED_TO_HUN");
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldNotFullScreen_snoozed_occluding() throws Exception {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(true);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_EXPECTED_TO_HUN);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoFullscreen(entry, "NO_FSI_EXPECTED_TO_HUN");
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldHeadsUp_snoozed_occluding() throws Exception {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(true);

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();

        verify(mLogger).logHeadsUpPackageSnoozeBypassedHasFsi(entry);
        verify(mLogger, never()).logHeadsUp(any());

        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(1);
        UiEventLoggerFake.FakeUiEvent fakeUiEvent = mUiEventLoggerFake.get(0);
        assertThat(fakeUiEvent.eventId).isEqualTo(
                NotificationInterruptEvent.HUN_SNOOZE_BYPASSED_POTENTIALLY_SUPPRESSED_FSI.getId());
        assertThat(fakeUiEvent.uid).isEqualTo(entry.getSbn().getUid());
        assertThat(fakeUiEvent.packageName).isEqualTo(entry.getSbn().getPackageName());
    }

    @Test
    public void testShouldNotFullScreen_snoozed_lockedShade() throws Exception {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE_LOCKED);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_EXPECTED_TO_HUN);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoFullscreen(entry, "NO_FSI_EXPECTED_TO_HUN");
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldHeadsUp_snoozed_lockedShade() throws Exception {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE_LOCKED);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();

        verify(mLogger).logHeadsUpPackageSnoozeBypassedHasFsi(entry);
        verify(mLogger, never()).logHeadsUp(any());

        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(1);
        UiEventLoggerFake.FakeUiEvent fakeUiEvent = mUiEventLoggerFake.get(0);
        assertThat(fakeUiEvent.eventId).isEqualTo(
                NotificationInterruptEvent.HUN_SNOOZE_BYPASSED_POTENTIALLY_SUPPRESSED_FSI.getId());
        assertThat(fakeUiEvent.uid).isEqualTo(entry.getSbn().getUid());
        assertThat(fakeUiEvent.packageName).isEqualTo(entry.getSbn().getPackageName());
    }

    @Test
    public void testShouldNotFullScreen_snoozed_unlocked() throws Exception {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_EXPECTED_TO_HUN);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoFullscreen(entry, "NO_FSI_EXPECTED_TO_HUN");
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldNotScreen_appSuspended() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(false);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        modifyRanking(entry).setSuspended(true).build();

        assertThat(mNotifInterruptionStateProvider.getFullScreenIntentDecision(entry))
                .isEqualTo(FullScreenIntentDecision.NO_FSI_SUSPENDED);
        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoFullscreen(entry, "NO_FSI_SUSPENDED");
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void logFullScreenIntentDecision_shouldAlmostAlwaysLogOneTime() {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        Set<FullScreenIntentDecision> warnings = new HashSet<>(Arrays.asList(
                FullScreenIntentDecision.NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR,
                FullScreenIntentDecision.NO_FSI_SUPPRESSIVE_BUBBLE_METADATA,
                FullScreenIntentDecision.NO_FSI_NO_HUN_OR_KEYGUARD
        ));
        for (FullScreenIntentDecision decision : FullScreenIntentDecision.values()) {
            clearInvocations(mLogger);
            boolean expectedToLog = decision != FullScreenIntentDecision.NO_FULL_SCREEN_INTENT;
            boolean isWarning = warnings.contains(decision);
            mNotifInterruptionStateProvider.logFullScreenIntentDecision(entry, decision);
            if (decision.shouldLaunch) {
                verify(mLogger).logFullscreen(eq(entry), contains(decision.name()));
            } else if (expectedToLog) {
                if (isWarning) {
                    verify(mLogger).logNoFullscreenWarning(eq(entry), contains(decision.name()));
                } else {
                    verify(mLogger).logNoFullscreen(eq(entry), contains(decision.name()));
                }
            }
            verifyNoMoreInteractions(mLogger);
        }
    }

    @Test
    public void testShouldHeadsUp_snoozed_unlocked() throws Exception {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mStatusBarStateController.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();

        verify(mLogger).logHeadsUpPackageSnoozeBypassedHasFsi(entry);
        verify(mLogger, never()).logHeadsUp(any());

        assertThat(mUiEventLoggerFake.numLogs()).isEqualTo(1);
        UiEventLoggerFake.FakeUiEvent fakeUiEvent = mUiEventLoggerFake.get(0);
        assertThat(fakeUiEvent.eventId).isEqualTo(
                NotificationInterruptEvent.HUN_SNOOZE_BYPASSED_POTENTIALLY_SUPPRESSED_FSI.getId());
        assertThat(fakeUiEvent.uid).isEqualTo(entry.getSbn().getUid());
        assertThat(fakeUiEvent.packageName).isEqualTo(entry.getSbn().getPackageName());
    }

    /* TODO: Verify the FSI_SUPPRESSED_NO_HUN_OR_KEYGUARD UiEvent some other way. */

    /**
     * Bubbles can happen.
     */
    @Test
    public void testShouldBubbleUp() {
        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(createBubble())).isTrue();
    }

    /**
     * Test that notification can bubble even if it is a child in a group and group settings are
     * set to alert only for summary notifications.
     */
    @Test
    public void testShouldBubbleUp_notifInGroupWithOnlySummaryAlerts() {
        NotificationEntry bubble = createBubble("testgroup", GROUP_ALERT_SUMMARY);
        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(bubble)).isTrue();
    }

    /**
     * If the notification doesn't have permission to bubble, it shouldn't bubble.
     */
    @Test
    public void shouldNotBubbleUp_notAllowedToBubble() {
        NotificationEntry entry = createBubble();
        modifyRanking(entry)
                .setCanBubble(false)
                .build();

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(entry)).isFalse();
    }

    /**
     * If the notification isn't a bubble, it should definitely not show as a bubble.
     */
    @Test
    public void shouldNotBubbleUp_notABubble() {
        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        modifyRanking(entry)
                .setCanBubble(true)
                .build();

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(entry)).isFalse();
    }

    /**
     * If the notification doesn't have bubble metadata, it shouldn't bubble.
     */
    @Test
    public void shouldNotBubbleUp_invalidMetadata() {
        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        modifyRanking(entry)
                .setCanBubble(true)
                .build();
        entry.getSbn().getNotification().flags |= FLAG_BUBBLE;

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(entry)).isFalse();
    }

    @Test
    public void shouldNotBubbleUp_suppressedInterruptions() {
        // If the notification can't heads up in general, it shouldn't bubble.
        mNotifInterruptionStateProvider.addSuppressor(mSuppressInterruptions);

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(createBubble())).isFalse();
    }

    @Test
    public void shouldNotBubbleUp_filteredOut() {
        // Make canAlertCommon false by saying it's filtered out
        when(mKeyguardNotificationVisibilityProvider.shouldHideNotification(any()))
                .thenReturn(true);

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(createBubble())).isFalse();
    }

    @Test
    public void shouldNotBubbleUp_suspended() {
        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(createSuspendedBubble()))
                .isFalse();
    }

    private NotificationEntry createSuspendedBubble() {
        return createBubble(null, null, true);
    }

    private NotificationEntry createBubble() {
        return createBubble(null, null, false);
    }

    private NotificationEntry createBubble(String groupKey, Integer groupAlert) {
        return createBubble(groupKey, groupAlert, false);
    }

    private NotificationEntry createBubble(String groupKey, Integer groupAlert, Boolean suspended) {
        Notification.BubbleMetadata data = new Notification.BubbleMetadata.Builder(
                PendingIntent.getActivity(mContext, 0,
                        new Intent().setPackage(mContext.getPackageName()),
                        PendingIntent.FLAG_MUTABLE),
                Icon.createWithResource(mContext.getResources(), R.drawable.android))
                .build();
        Notification.Builder nb = new Notification.Builder(getContext(), "a")
                .setContentTitle("title")
                .setContentText("content text")
                .setBubbleMetadata(data);
        if (groupKey != null) {
            nb.setGroup(groupKey);
            nb.setGroupSummary(false);
        }
        if (groupAlert != null) {
            nb.setGroupAlertBehavior(groupAlert);
        }
        Notification n = nb.build();
        n.flags |= FLAG_BUBBLE;

        return new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setNotification(n)
                .setImportance(IMPORTANCE_HIGH)
                .setCanBubble(true)
                .setSuspended(suspended)
                .build();
    }

    private NotificationEntry createNotification(int importance) {
        Notification n = new Notification.Builder(getContext(), "a")
                .setContentTitle("title")
                .setContentText("content text")
                .build();

        return createNotification(importance, n);
    }

    private NotificationEntry createNotification(int importance, Notification n) {
        return new NotificationEntryBuilder()
                .setPkg("a")
                .setOpPkg("a")
                .setTag("a")
                .setChannel(new NotificationChannel("a", null, importance))
                .setNotification(n)
                .setImportance(importance)
                .build();
    }

    private NotificationEntry createFsiNotification(int importance, boolean silent) {
        Notification n = new Notification.Builder(getContext(), "a")
                .setContentTitle("title")
                .setContentText("content text")
                .setFullScreenIntent(mPendingIntent, true)
                .setGroup("fsi")
                .setGroupAlertBehavior(silent ? GROUP_ALERT_SUMMARY : Notification.GROUP_ALERT_ALL)
                .build();

        return createNotification(importance, n);
    }

    private NotificationEntry createFgsNotification(int importance) {
        Notification n = new Notification.Builder(getContext(), "a")
                .setContentTitle("title")
                .setContentText("content text")
                .setFlag(FLAG_FOREGROUND_SERVICE, true)
                .build();

        return createNotification(importance, n);
    }

    private final NotificationInterruptSuppressor
            mSuppressAwakeHeadsUp =
            new NotificationInterruptSuppressor() {
                @Override
                public String getName() {
                    return "suppressAwakeHeadsUp";
                }

                @Override
                public boolean suppressAwakeHeadsUp(NotificationEntry entry) {
                    return true;
                }
            };

    private final NotificationInterruptSuppressor
            mSuppressAwakeInterruptions =
            new NotificationInterruptSuppressor() {
                @Override
                public String getName() {
                    return "suppressAwakeInterruptions";
                }

                @Override
                public boolean suppressAwakeInterruptions(NotificationEntry entry) {
                    return true;
                }
            };

    private final NotificationInterruptSuppressor
            mSuppressInterruptions =
            new NotificationInterruptSuppressor() {
                @Override
                public String getName() {
                    return "suppressInterruptions";
                }

                @Override
                public boolean suppressInterruptions(NotificationEntry entry) {
                    return true;
                }
            };
}
