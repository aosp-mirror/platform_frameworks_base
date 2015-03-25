/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.IRemoteVolumeController;
import android.media.IVolumeController;
import android.media.VolumePolicy;
import android.media.session.ISessionController;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Implementation of VolumeComponent backed by the old volume panel.
 */
public class VolumePanelComponent implements VolumeComponent {

    private final SystemUI mSysui;
    private final Context mContext;
    private final Handler mHandler;
    private final VolumeController mVolumeController;
    private final RemoteVolumeController mRemoteVolumeController;
    private final AudioManager mAudioManager;
    private final MediaSessionManager mMediaSessionManager;

    private VolumePanel mPanel;
    private int mDismissDelay;

    public VolumePanelComponent(SystemUI sysui, Context context, Handler handler,
            ZenModeController controller) {
        mSysui = sysui;
        mContext = context;
        mHandler = handler;
        mAudioManager = context.getSystemService(AudioManager.class);
        mMediaSessionManager = context.getSystemService(MediaSessionManager.class);
        mVolumeController = new VolumeController();
        mRemoteVolumeController = new RemoteVolumeController();
        mDismissDelay = mContext.getResources().getInteger(R.integer.volume_panel_dismiss_delay);
        mPanel = new VolumePanel(mContext, controller);
        mPanel.setCallback(new VolumePanel.Callback() {
            @Override
            public void onZenSettings() {
                mHandler.removeCallbacks(mStartZenSettings);
                mHandler.post(mStartZenSettings);
            }

            @Override
            public void onInteraction() {
                final KeyguardViewMediator kvm = mSysui.getComponent(KeyguardViewMediator.class);
                if (kvm != null) {
                    kvm.userActivity();
                }
            }

            @Override
            public void onVisible(boolean visible) {
                if (mAudioManager != null && mVolumeController != null) {
                    mAudioManager.notifyVolumeControllerVisible(mVolumeController, visible);
                }
            }
        });
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mPanel != null) {
            mPanel.dump(fd, pw, args);
        }
    }

    public void register() {
        mAudioManager.setVolumeController(mVolumeController);
        mAudioManager.setVolumePolicy(VolumePolicy.DEFAULT);
        mMediaSessionManager.setRemoteVolumeController(mRemoteVolumeController);
        DndTile.setVisible(mContext, false);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mPanel != null) {
            mPanel.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public ZenModeController getZenController() {
        return mPanel.getZenController();
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        mPanel.dispatchDemoCommand(command, args);
    }

    @Override
    public void dismissNow() {
        mPanel.postDismiss(0);
    }

    private final Runnable mStartZenSettings = new Runnable() {
        @Override
        public void run() {
            mSysui.getComponent(PhoneStatusBar.class).startActivityDismissingKeyguard(
                    ZenModePanel.ZEN_SETTINGS, true /* onlyProvisioned */, true /* dismissShade */);
            mPanel.postDismiss(mDismissDelay);
        }
    };

    private final class RemoteVolumeController extends IRemoteVolumeController.Stub {
        @Override
        public void remoteVolumeChanged(ISessionController binder, int flags)
                throws RemoteException {
            MediaController controller = new MediaController(mContext, binder);
            mPanel.postRemoteVolumeChanged(controller, flags);
        }

        @Override
        public void updateRemoteController(ISessionController session) throws RemoteException {
            mPanel.postRemoteSliderVisibility(session != null);
            // TODO stash default session in case the slider can be opened other
            // than by remoteVolumeChanged.
        }
    }

    /** For now, simply host an unmodified base volume panel in this process. */
    private final class VolumeController extends IVolumeController.Stub {

        @Override
        public void displaySafeVolumeWarning(int flags) throws RemoteException {
            mPanel.postDisplaySafeVolumeWarning(flags);
        }

        @Override
        public void volumeChanged(int streamType, int flags)
                throws RemoteException {
            mPanel.postVolumeChanged(streamType, flags);
        }

        @Override
        public void masterMuteChanged(int flags) throws RemoteException {
            // no-op
        }

        @Override
        public void setLayoutDirection(int layoutDirection)
                throws RemoteException {
            mPanel.postLayoutDirection(layoutDirection);
        }

        @Override
        public void dismiss() throws RemoteException {
            dismissNow();
        }
    }
}
