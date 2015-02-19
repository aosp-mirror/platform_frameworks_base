/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.tv;

import android.os.IBinder;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.BaseStatusBar;

/*
 * Status bar implementation for "large screen" products that mostly present no on-screen nav
 */

public class TvStatusBar extends BaseStatusBar {

    @Override
    public void addIcon(String slot, int index, int viewIndex, StatusBarIcon icon) {
    }

    @Override
    public void updateIcon(String slot, int index, int viewIndex, StatusBarIcon old,
            StatusBarIcon icon) {
    }

    @Override
    public void removeIcon(String slot, int index, int viewIndex) {
    }

    @Override
    public void addNotification(StatusBarNotification notification, RankingMap ranking) {
    }

    @Override
    protected void updateNotificationRanking(RankingMap ranking) {
    }

    @Override
    public void removeNotification(String key, RankingMap ranking) {
    }

    @Override
    public void disable(int state, boolean animate) {
    }

    @Override
    public void animateExpandNotificationsPanel() {
    }

    @Override
    public void animateCollapsePanels(int flags) {
    }

    @Override
    public void setSystemUiVisibility(int vis, int mask) {
    }

    @Override
    public void topAppWindowChanged(boolean visible) {
    }

    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
    }

    @Override
    public void toggleRecentApps() {
    }

    @Override // CommandQueue
    public void setWindowState(int window, int state) {
    }

    @Override // CommandQueue
    public void buzzBeepBlinked() {
    }

    @Override // CommandQueue
    public void notificationLightOff() {
    }

    @Override // CommandQueue
    public void notificationLightPulse(int argb, int onMillis, int offMillis) {
    }

    @Override
    protected WindowManager.LayoutParams getSearchLayoutParams(
            LayoutParams layoutParams) {
        return null;
    }

    @Override
    protected void haltTicker() {
    }

    @Override
    protected void setAreThereNotifications() {
    }

    @Override
    protected void updateNotifications() {
    }

    @Override
    protected void tick(StatusBarNotification n, boolean firstTime) {
    }

    @Override
    protected void updateExpandedViewPos(int expandedPosition) {
    }

    @Override
    protected boolean shouldDisableNavbarGestures() {
        return true;
    }

    public View getStatusBarView() {
        return null;
    }

    @Override
    public void resetHeadsUpDecayTimer() {
    }

    @Override
    public void scheduleHeadsUpOpen() {
    }

    @Override
    public void scheduleHeadsUpEscalation() {
    }

    @Override
    public void scheduleHeadsUpClose() {
    }

    @Override
    protected int getMaxKeyguardNotifications() {
        return 0;
    }

    @Override
    public void animateExpandSettingsPanel() {
    }

    @Override
    protected void createAndAddWindows() {
    }

    @Override
    protected void refreshLayout(int layoutDirection) {
    }

    @Override
    public void onActivated(ActivatableNotificationView view) {
    }

    @Override
    public void onActivationReset(ActivatableNotificationView view) {
    }

    @Override
    public void showScreenPinningRequest() {
    }
}
