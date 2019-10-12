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
package com.android.systemui.statusbar;


import static android.app.Notification.FLAG_BUBBLE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;

import static com.android.systemui.statusbar.StatusBarState.SHADE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.HeadsUpManager;

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
public class NotificationInterruptionStateProviderTest extends SysuiTestCase {

    @Mock
    PowerManager mPowerManager;
    @Mock
    IDreamManager mDreamManager;
    @Mock
    AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock
    NotificationFilter mNotificationFilter;
    @Mock
    StatusBarStateController mStatusBarStateController;
    @Mock
    NotificationPresenter mPresenter;
    @Mock
    HeadsUpManager mHeadsUpManager;
    @Mock
    NotificationInterruptionStateProvider.HeadsUpSuppressor mHeadsUpSuppressor;

    private NotificationInterruptionStateProvider mNotifInterruptionStateProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mNotifInterruptionStateProvider =
                new TestableNotificationInterruptionStateProvider(mContext,
                        mPowerManager,
                        mDreamManager,
                        mAmbientDisplayConfiguration,
                        mNotificationFilter,
                        mStatusBarStateController);

        mNotifInterruptionStateProvider.setUpWithPresenter(
                mPresenter,
                mHeadsUpManager,
                mHeadsUpSuppressor);
    }

    /**
     * Sets up the state such that any requests to
     * {@link NotificationInterruptionStateProvider#canAlertCommon(NotificationEntry)} will
     * pass as long its provided NotificationEntry fulfills group suppression check.
     */
    private void ensureStateForAlertCommon() {
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(false);
    }

    /**
     * Sets up the state such that any requests to
     * {@link NotificationInterruptionStateProvider#canAlertAwakeCommon(NotificationEntry)} will
     * pass as long its provided NotificationEntry fulfills launch fullscreen check.
     */
    private void ensureStateForAlertAwakeCommon() {
        when(mPresenter.isDeviceInVrMode()).thenReturn(false);
        when(mHeadsUpManager.isSnoozed(any())).thenReturn(false);
    }

    /**
     * Sets up the state such that any requests to
     * {@link NotificationInterruptionStateProvider#shouldHeadsUp(NotificationEntry)} will
     * pass as long its provided NotificationEntry fulfills importance & DND checks.
     */
    private void ensureStateForHeadsUpWhenAwake() throws RemoteException {
        ensureStateForAlertCommon();
        ensureStateForAlertAwakeCommon();

        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mDreamManager.isDreaming()).thenReturn(false);
        when(mPowerManager.isScreenOn()).thenReturn(true);
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(true);
    }

    /**
     * Sets up the state such that any requests to
     * {@link NotificationInterruptionStateProvider#shouldHeadsUp(NotificationEntry)} will
     * pass as long its provided NotificationEntry fulfills importance & DND checks.
     */
    private void ensureStateForHeadsUpWhenDozing() {
        ensureStateForAlertCommon();

        when(mStatusBarStateController.isDozing()).thenReturn(true);
        when(mAmbientDisplayConfiguration.pulseOnNotificationEnabled(anyInt())).thenReturn(true);
    }

    /**
     * Sets up the state such that any requests to
     * {@link NotificationInterruptionStateProvider#shouldBubbleUp(NotificationEntry)} will
     * pass as long its provided NotificationEntry fulfills importance & bubble checks.
     */
    private void ensureStateForBubbleUp() {
        ensureStateForAlertCommon();
        ensureStateForAlertAwakeCommon();
    }

    /**
     * Ensure that the disabled state is set correctly.
     */
    @Test
    public void testDisableNotificationAlerts() {
        // Enabled by default
        assertThat(mNotifInterruptionStateProvider.areNotificationAlertsDisabled()).isFalse();

        // Disable alerts
        mNotifInterruptionStateProvider.setDisableNotificationAlerts(true);
        assertThat(mNotifInterruptionStateProvider.areNotificationAlertsDisabled()).isTrue();

        // Enable alerts
        mNotifInterruptionStateProvider.setDisableNotificationAlerts(false);
        assertThat(mNotifInterruptionStateProvider.areNotificationAlertsDisabled()).isFalse();
    }

    /**
     * Ensure that the disabled alert state effects whether HUNs are enabled.
     */
    @Test
    public void testHunSettingsChange_enabled_butAlertsDisabled() {
        // Set up but without a mock change observer
        mNotifInterruptionStateProvider.setUpWithPresenter(
                mPresenter,
                mHeadsUpManager,
                mHeadsUpSuppressor);

        // HUNs enabled by default
        assertThat(mNotifInterruptionStateProvider.getUseHeadsUp()).isTrue();

        // Set alerts disabled
        mNotifInterruptionStateProvider.setDisableNotificationAlerts(true);

        // No more HUNs
        assertThat(mNotifInterruptionStateProvider.getUseHeadsUp()).isFalse();
    }

    /**
     * Alerts can happen.
     */
    @Test
    public void testCanAlertCommon_true() {
        ensureStateForAlertCommon();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.canAlertCommon(entry)).isTrue();
    }

    /**
     * Filtered out notifications don't alert.
     */
    @Test
    public void testCanAlertCommon_false_filteredOut() {
        ensureStateForAlertCommon();
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(true);

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.canAlertCommon(entry)).isFalse();
    }

    /**
     * Grouped notifications have different alerting behaviours, sometimes the alert for a
     * grouped notification may be suppressed {@link android.app.Notification#GROUP_ALERT_CHILDREN}.
     */
    @Test
    public void testCanAlertCommon_false_suppressedForGroups() {
        ensureStateForAlertCommon();

        Notification n = new Notification.Builder(getContext(), "a")
                .setGroup("a")
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        entry.importance = IMPORTANCE_DEFAULT;

        assertThat(mNotifInterruptionStateProvider.canAlertCommon(entry)).isFalse();
    }

    /**
     * HUNs while dozing can happen.
     */
    @Test
    public void testShouldHeadsUpWhenDozing_true() {
        ensureStateForHeadsUpWhenDozing();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();
    }

    /**
     * Ambient display can show HUNs for new notifications, this may be disabled.
     */
    @Test
    public void testShouldHeadsUpWhenDozing_false_pulseDisabled() {
        ensureStateForHeadsUpWhenDozing();
        when(mAmbientDisplayConfiguration.pulseOnNotificationEnabled(anyInt())).thenReturn(false);

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * If the device is not in ambient display or sleeping then we don't HUN.
     */
    @Test
    public void testShouldHeadsUpWhenDozing_false_notDozing() {
        ensureStateForHeadsUpWhenDozing();
        when(mStatusBarStateController.isDozing()).thenReturn(false);

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * In DND ambient effects can be suppressed
     * {@link android.app.NotificationManager.Policy#SUPPRESSED_EFFECT_AMBIENT}.
     */
    @Test
    public void testShouldHeadsUpWhenDozing_false_suppressingAmbient() {
        ensureStateForHeadsUpWhenDozing();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        entry.suppressedVisualEffects = SUPPRESSED_EFFECT_AMBIENT;

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * Notifications that are < {@link android.app.NotificationManager#IMPORTANCE_DEFAULT} don't
     * get to pulse.
     */
    @Test
    public void testShouldHeadsUpWhenDozing_false_lessImportant() {
        ensureStateForHeadsUpWhenDozing();

        NotificationEntry entry = createNotification(IMPORTANCE_LOW);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * Heads up can happen.
     */
    @Test
    public void testShouldHeadsUp_true() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isTrue();
    }

    /**
     * Heads up notifications can be disabled in general.
     */
    @Test
    public void testShouldHeadsUp_false_noHunsAllowed() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        // Set alerts disabled, this should cause heads up to be false
        mNotifInterruptionStateProvider.setDisableNotificationAlerts(true);
        assertThat(mNotifInterruptionStateProvider.getUseHeadsUp()).isFalse();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * If the device is dozing, we don't show as heads up.
     */
    @Test
    public void testShouldHeadsUp_false_dozing() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();
        when(mStatusBarStateController.isDozing()).thenReturn(true);

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * If the notification is a bubble, and the user is not on AOD / lockscreen, then
     * the bubble is shown rather than the heads up.
     */
    @Test
    public void testShouldHeadsUp_false_bubble() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        // Bubble bit only applies to interruption when we're in the shade
        when(mStatusBarStateController.getState()).thenReturn(SHADE);

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(createBubble())).isFalse();
    }

    /**
     * If we're not allowed to alert in general, we shouldn't be shown as heads up.
     */
    @Test
    public void testShouldHeadsUp_false_alertCommonFalse() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();
        // Make canAlertCommon false by saying it's filtered out
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(true);

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * In DND HUN peek effects can be suppressed
     * {@link android.app.NotificationManager.Policy#SUPPRESSED_EFFECT_PEEK}.
     */
    @Test
    public void testShouldHeadsUp_false_suppressPeek() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        entry.suppressedVisualEffects = SUPPRESSED_EFFECT_PEEK;

        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * Notifications that are < {@link android.app.NotificationManager#IMPORTANCE_HIGH} don't get
     * to show as a heads up.
     */
    @Test
    public void testShouldHeadsUp_false_lessImportant() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
    }

    /**
     * If the device is not in use then we shouldn't be shown as heads up.
     */
    @Test
    public void testShouldHeadsUp_false_deviceNotInUse() throws RemoteException {
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

    /**
     * If something wants to suppress this heads up, then it shouldn't be shown as a heads up.
     */
    @Test
    public void testShouldHeadsUp_false_suppressed() throws RemoteException {
        ensureStateForHeadsUpWhenAwake();
        when(mHeadsUpSuppressor.canHeadsUp(any(), any())).thenReturn(false);

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        assertThat(mNotifInterruptionStateProvider.shouldHeadsUp(entry)).isFalse();
        verify(mHeadsUpSuppressor).canHeadsUp(any(), any());
    }

    /**
     * On screen alerts don't happen when the device is in VR Mode.
     */
    @Test
    public void testCanAlertAwakeCommon__false_vrMode() {
        ensureStateForAlertAwakeCommon();
        when(mPresenter.isDeviceInVrMode()).thenReturn(true);

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.canAlertAwakeCommon(entry)).isFalse();
    }

    /**
     * On screen alerts don't happen when the notification is snoozed.
     */
    @Test
    public void testCanAlertAwakeCommon_false_snoozedPackage() {
        ensureStateForAlertAwakeCommon();
        when(mHeadsUpManager.isSnoozed(any())).thenReturn(true);

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        assertThat(mNotifInterruptionStateProvider.canAlertAwakeCommon(entry)).isFalse();
    }

    /**
     * On screen alerts don't happen when that package has just launched fullscreen.
     */
    @Test
    public void testCanAlertAwakeCommon_false_justLaunchedFullscreen() {
        ensureStateForAlertAwakeCommon();

        NotificationEntry entry = createNotification(IMPORTANCE_DEFAULT);
        entry.notifyFullScreenIntentLaunched();

        assertThat(mNotifInterruptionStateProvider.canAlertAwakeCommon(entry)).isFalse();
    }

    /**
     * Bubbles can happen.
     */
    @Test
    public void testShouldBubbleUp_true() {
        ensureStateForBubbleUp();
        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(createBubble())).isTrue();
    }

    /**
     * If the notification doesn't have permission to bubble, it shouldn't bubble.
     */
    @Test
    public void shouldBubbleUp_false_notAllowedToBubble() {
        ensureStateForBubbleUp();

        NotificationEntry entry = createBubble();
        entry.canBubble = false;

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(entry)).isFalse();
    }

    /**
     * If the notification isn't a bubble, it should definitely not show as a bubble.
     */
    @Test
    public void shouldBubbleUp_false_notABubble() {
        ensureStateForBubbleUp();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        entry.canBubble = true;

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(entry)).isFalse();
    }

    /**
     * If the notification doesn't have bubble metadata, it shouldn't bubble.
     */
    @Test
    public void shouldBubbleUp_false_invalidMetadata() {
        ensureStateForBubbleUp();

        NotificationEntry entry = createNotification(IMPORTANCE_HIGH);
        entry.canBubble = true;
        entry.notification.getNotification().flags |= FLAG_BUBBLE;

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(entry)).isFalse();
    }

    /**
     * If the notification can't heads up in general, it shouldn't bubble.
     */
    @Test
    public void shouldBubbleUp_false_alertAwakeCommonFalse() {
        ensureStateForBubbleUp();

        // Make alert common return false by pretending we're in VR mode
        when(mPresenter.isDeviceInVrMode()).thenReturn(true);

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(createBubble())).isFalse();
    }

    /**
     * If the notification can't heads up in general, it shouldn't bubble.
     */
    @Test
    public void shouldBubbleUp_false_alertCommonFalse() {
        ensureStateForBubbleUp();

        // Make canAlertCommon false by saying it's filtered out
        when(mNotificationFilter.shouldFilterOut(any())).thenReturn(true);

        assertThat(mNotifInterruptionStateProvider.shouldBubbleUp(createBubble())).isFalse();
    }

    private NotificationEntry createBubble() {
        Notification.BubbleMetadata data = new Notification.BubbleMetadata.Builder()
                .setIntent(PendingIntent.getActivity(mContext, 0, new Intent(), 0))
                .setIcon(Icon.createWithResource(mContext.getResources(), R.drawable.android))
                .build();
        Notification n = new Notification.Builder(getContext(), "a")
                .setContentTitle("title")
                .setContentText("content text")
                .setBubbleMetadata(data)
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        entry.notification.getNotification().flags |= FLAG_BUBBLE;
        entry.importance = IMPORTANCE_HIGH;
        entry.canBubble = true;
        return entry;
    }

    private NotificationEntry createNotification(int importance) {
        Notification n = new Notification.Builder(getContext(), "a")
                .setContentTitle("title")
                .setContentText("content text")
                .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "a", 0, 0, n,
                UserHandle.of(0), null, 0);
        NotificationEntry entry = new NotificationEntry(sbn);
        entry.importance = importance;
        return entry;
    }

    /**
     * Testable class overriding constructor.
     */
    public class TestableNotificationInterruptionStateProvider extends
            NotificationInterruptionStateProvider {

        TestableNotificationInterruptionStateProvider(Context context,
                PowerManager powerManager, IDreamManager dreamManager,
                AmbientDisplayConfiguration ambientDisplayConfiguration,
                NotificationFilter notificationFilter,
                StatusBarStateController statusBarStateController) {
            super(context, powerManager, dreamManager, ambientDisplayConfiguration,
                    notificationFilter,
                    statusBarStateController);
        }
    }
}
