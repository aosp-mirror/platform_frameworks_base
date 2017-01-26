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

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService.RankingMap;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.SystemUI;
import com.android.systemui.pip.tv.PipManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;

import java.util.ArrayList;

/**
 * Status bar implementation for "large screen" products that mostly present no on-screen nav
 */

public class TvStatusBar extends SystemUI implements Callbacks {

    private IStatusBarService mBarService;

    @Override
    public void setIcon(String slot, StatusBarIcon icon) {
    }

    @Override
    public void removeIcon(String slot) {
    }

    public void removeNotification(String key, RankingMap ranking) {
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
    }

    @Override
    public void animateExpandNotificationsPanel() {
    }

    @Override
    public void animateCollapsePanels(int flags) {
    }

    @Override
    public void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis,
            int mask, Rect fullscreenStackBounds, Rect dockedStackBounds) {
    }

    @Override
    public void topAppWindowChanged(boolean visible) {
    }

    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
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
    public void animateExpandSettingsPanel(String subPanel) {
    }

    @Override
    public void showScreenPinningRequest(int taskId) {
    }

    @Override
    public void appTransitionPending() {
    }

    @Override
    public void appTransitionCancelled() {
    }

    @Override
    public void appTransitionStarting(long startTime, long duration) {
    }

    @Override
    public void appTransitionFinished() {
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
    }

    @Override
    public void showTvPictureInPictureMenu() {
        PipManager.getInstance().showTvPictureInPictureMenu();
    }

    @Override
    public void addQsTile(ComponentName tile) {
    }

    @Override
    public void remQsTile(ComponentName tile) {
    }

    @Override
    public void clickTile(ComponentName tile) {
    }

    @Override
    public void start() {
        putComponent(TvStatusBar.class, this);
        CommandQueue commandQueue = getComponent(CommandQueue.class);
        commandQueue.addCallbacks(this);
        int[] switches = new int[9];
        ArrayList<IBinder> binders = new ArrayList<>();
        ArrayList<String> iconSlots = new ArrayList<>();
        ArrayList<StatusBarIcon> icons = new ArrayList<>();
        Rect fullscreenStackBounds = new Rect();
        Rect dockedStackBounds = new Rect();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        try {
            mBarService.registerStatusBar(commandQueue, iconSlots, icons, switches, binders,
                    fullscreenStackBounds, dockedStackBounds);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }
    }

    @Override
    public void handleSystemNavigationKey(int arg1) {
        // Not implemented
    }
}
