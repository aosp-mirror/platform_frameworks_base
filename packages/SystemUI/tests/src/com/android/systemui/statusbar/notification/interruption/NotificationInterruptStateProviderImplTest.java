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
import static android.app.Notification.GROUP_ALERT_SUMMARY;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;

import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.service.dreams.IDreamManager;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    IDreamManager mDreamManager;
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
    @Mock
    PendingIntent mPendingIntent;

    private NotificationInterruptStateProviderImpl mNotifInterruptionStateProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(false);

        mNotifInterruptionStateProvider =
                new NotificationInterruptStateProviderImpl(
                        mContext.getContentResolver(),
                        mPowerManager,
                        mDreamManager,
                        mAmbientDisplayConfiguration,
                        mBatteryController,
                        mStatusBarStateController,
                        mKeyguardStateController,
                        mHeadsUpManager,
                        mLogger,
                        mMockHandler,
                        mFlags,
                        mKeyguardNotificationVisibilityProvider);
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
        when(mDreamManager.isDreaming()).thenReturn(false);
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
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();
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
        when(mDreamManager.isDreaming()).thenReturn(true);
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

    @Test
    public void testShouldNotFullScreen_notPendingIntent_withStrictFlag() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        testShouldNotFullScreen_notPendingIntent();
    }

    @Test
    public void testShouldNotFullScreen_notPendingIntent() throws RemoteException {
        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldNotFullScreen_notHighImportance_withStrictFlag() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        testShouldNotFullScreen_notHighImportance();
    }

    @Test
    public void testShouldNotFullScreen_notHighImportance() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_DEFAULT, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoFullscreen(entry, "Not important enough");
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldNotFullScreen_isGroupAlertSilenced_withStrictFlag() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        testShouldNotFullScreen_isGroupAlertSilenced();
    }

    @Test
    public void testShouldNotFullScreen_isGroupAlertSilenced() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ true);
        when(mPowerManager.isInteractive()).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(true);
        when(mStatusBarStateController.getState()).thenReturn(KEYGUARD);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger).logNoFullscreenWarning(entry, "GroupAlertBehavior will prevent HUN");
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldFullScreen_notInteractive_withStrictFlag() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        testShouldFullScreen_notInteractive();
    }

    @Test
    public void testShouldFullScreen_notInteractive() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "Device is not interactive");
    }

    @Test
    public void testShouldFullScreen_isDreaming_withStrictFlag() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        testShouldFullScreen_isDreaming();
    }

    @Test
    public void testShouldFullScreen_isDreaming() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(true);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "Device is dreaming");
    }

    @Test
    public void testShouldFullScreen_onKeyguard_withStrictFlag() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        testShouldFullScreen_onKeyguard();
    }

    @Test
    public void testShouldFullScreen_onKeyguard() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(KEYGUARD);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "Keyguard is showing");
    }

    @Test
    public void testShouldNotFullScreen_willHun_withStrictFlag() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        testShouldNotFullScreen_willHun();
    }

    @Test
    public void testShouldNotFullScreen_willHun() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoFullscreen(entry, "Expected to HUN");
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger, never()).logFullscreen(any(), any());
    }

    @Test
    public void testShouldFullScreen_packageSnoozed() throws RemoteException {
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger).logNoHeadsUpPackageSnoozed(entry);
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "Expected not to HUN");
    }

    @Test
    public void testShouldFullScreen_snoozed_occluding_withStrictRules() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(true);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger).logNoHeadsUpPackageSnoozed(entry);
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "Expected not to HUN while keyguard occluded");
    }

    @Test
    public void testShouldFullScreen_snoozed_lockedShade_withStrictRules() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE_LOCKED);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isTrue();
        verify(mLogger).logNoHeadsUpPackageSnoozed(entry);
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger, never()).logNoFullscreenWarning(any(), any());
        verify(mLogger).logFullscreen(entry, "Keyguard is showing and not occluded");
    }

    @Test
    public void testShouldNotFullScreen_snoozed_unlocked_withStrictRules() throws Exception {
        when(mFlags.fullScreenIntentRequiresKeyguard()).thenReturn(true);
        NotificationEntry entry = createFsiNotification(IMPORTANCE_HIGH, /* silenced */ false);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mHeadsUpManager.isSnoozed("a")).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(false);
        when(mKeyguardStateController.isOccluded()).thenReturn(false);

        assertThat(mNotifInterruptionStateProvider.shouldLaunchFullScreenIntentWhenAdded(entry))
                .isFalse();
        verify(mLogger).logNoHeadsUpPackageSnoozed(entry);
        verify(mLogger, never()).logNoFullscreen(any(), any());
        verify(mLogger).logNoFullscreenWarning(entry, "Expected not to HUN while not on keyguard");
        verify(mLogger, never()).logFullscreen(any(), any());
    }

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

    private NotificationEntry createBubble() {
        return createBubble(null, null);
    }

    private NotificationEntry createBubble(String groupKey, Integer groupAlert) {
        Notification.BubbleMetadata data = new Notification.BubbleMetadata.Builder(
                PendingIntent.getActivity(mContext, 0, new Intent(),
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
