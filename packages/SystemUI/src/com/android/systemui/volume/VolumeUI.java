package com.android.systemui.volume;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.IRemoteVolumeController;
import android.media.IVolumeController;
import android.media.session.ISessionController;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManagerGlobal;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/*
 * Copyright (C) 2014 The Android Open Source Project
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

public class VolumeUI extends SystemUI {
    private static final String TAG = "VolumeUI";
    private static final String SETTING = "systemui_volume_controller";  // for testing
    private static final Uri SETTING_URI = Settings.Global.getUriFor(SETTING);
    private static final int DEFAULT = 1;  // enabled by default

    private final Handler mHandler = new Handler();

    private AudioManager mAudioManager;
    private MediaSessionManager mMediaSessionManager;
    private VolumeController mVolumeController;
    private RemoteVolumeController mRemoteVolumeController;

    private VolumePanel mPanel;
    private int mDismissDelay;

    @Override
    public void start() {
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mMediaSessionManager = (MediaSessionManager) mContext
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        initPanel();
        mVolumeController = new VolumeController();
        mRemoteVolumeController = new RemoteVolumeController();
        putComponent(VolumeComponent.class, mVolumeController);
        updateController();
        mContext.getContentResolver().registerContentObserver(SETTING_URI, false, mObserver);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mPanel != null) {
            mPanel.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mPanel != null) {
            mPanel.dump(fd, pw, args);
        }
    }

    private void updateController() {
        if (Settings.Global.getInt(mContext.getContentResolver(), SETTING, DEFAULT) != 0) {
            Log.d(TAG, "Registering volume controller");
            mAudioManager.setVolumeController(mVolumeController);
            mMediaSessionManager.setRemoteVolumeController(mRemoteVolumeController);
        } else {
            Log.d(TAG, "Unregistering volume controller");
            mAudioManager.setVolumeController(null);
            mMediaSessionManager.setRemoteVolumeController(null);
        }
    }

    private void initPanel() {
        mDismissDelay = mContext.getResources().getInteger(R.integer.volume_panel_dismiss_delay);
        mPanel = new VolumePanel(mContext, new ZenModeControllerImpl(mContext, mHandler));
        mPanel.setCallback(new VolumePanel.Callback() {
            @Override
            public void onZenSettings() {
                mHandler.removeCallbacks(mStartZenSettings);
                mHandler.post(mStartZenSettings);
            }

            @Override
            public void onInteraction() {
                final KeyguardViewMediator kvm = getComponent(KeyguardViewMediator.class);
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

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        public void onChange(boolean selfChange, Uri uri) {
            if (SETTING_URI.equals(uri)) {
                updateController();
            }
        }
    };

    private final Runnable mStartZenSettings = new Runnable() {
        @Override
        public void run() {
            getComponent(PhoneStatusBar.class).startActivityDismissingKeyguard(
                    ZenModePanel.ZEN_SETTINGS, true /* onlyProvisioned */, true /* dismissShade */,
                    false /* afterKeyguardGone */);
            mPanel.postDismiss(mDismissDelay);
        }
    };

    /** For now, simply host an unmodified base volume panel in this process. */
    private final class VolumeController extends IVolumeController.Stub implements VolumeComponent {

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
        public void masterVolumeChanged(int flags) throws RemoteException {
            mPanel.postMasterVolumeChanged(flags);
        }

        @Override
        public void masterMuteChanged(int flags) throws RemoteException {
            mPanel.postMasterMuteChanged(flags);
        }

        @Override
        public void setLayoutDirection(int layoutDirection)
                throws RemoteException {
            mPanel.postLayoutDirection(layoutDirection);
        }

        @Override
        public void dismiss() throws RemoteException {
            mPanel.postDismiss(0);
        }

        @Override
        public ZenModeController getZenController() {
            return mPanel.getZenController();
        }
    }

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
}
