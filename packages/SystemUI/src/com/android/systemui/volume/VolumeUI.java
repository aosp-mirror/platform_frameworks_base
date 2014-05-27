package com.android.systemui.volume;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.IVolumeController;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;

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
    private VolumeController mVolumeController;

    @Override
    public void start() {
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mVolumeController = new VolumeController(mContext);
        putComponent(VolumeComponent.class, mVolumeController);
        updateController();
        mContext.getContentResolver().registerContentObserver(SETTING_URI, false, mObserver);
    }

    private void updateController() {
        if (Settings.Global.getInt(mContext.getContentResolver(), SETTING, DEFAULT) != 0) {
            Log.d(TAG, "Registering volume controller");
            mAudioManager.setVolumeController(mVolumeController);
        } else {
            Log.d(TAG, "Unregistering volume controller");
            mAudioManager.setVolumeController(null);
        }
    }

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        public void onChange(boolean selfChange, Uri uri) {
            if (SETTING_URI.equals(uri)) {
                updateController();
            }
        }
    };

    /** For now, simply host an unmodified base volume panel in this process. */
    private final class VolumeController extends IVolumeController.Stub implements VolumeComponent {
        private final VolumePanel mDialogPanel;
        private VolumePanel mPanel;

        public VolumeController(Context context) {
            mPanel = new VolumePanel(context, null, new ZenModeControllerImpl(mContext, mHandler));
            final int delay = context.getResources().getInteger(R.integer.feedback_start_delay);
            mPanel.setZenModePanelCallback(new ZenModePanel.Callback() {
                @Override
                public void onMoreSettings() {
                    mHandler.removeCallbacks(mStartZenSettings);
                    mHandler.postDelayed(mStartZenSettings, delay);
                }

                @Override
                public void onInteraction() {
                    mDialogPanel.resetTimeout();
                }
            });
            mDialogPanel = mPanel;
        }

        private final Runnable mStartZenSettings = new Runnable() {
            @Override
            public void run() {
                mDialogPanel.postDismiss();
                final Intent intent = ZenModePanel.ZEN_SETTINGS;
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
            }
        };

        @Override
        public void hasNewRemotePlaybackInfo() throws RemoteException {
            mPanel.postHasNewRemotePlaybackInfo();
        }

        @Override
        public void remoteVolumeChanged(int streamType, int flags)
                throws RemoteException {
            mPanel.postRemoteVolumeChanged(streamType, flags);
        }

        @Override
        public void remoteSliderVisibility(boolean visible)
                throws RemoteException {
            mPanel.postRemoteSliderVisibility(visible);
        }

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
            mPanel.postDismiss();
        }

        @Override
        public ZenModeController getZenController() {
            return mDialogPanel.getZenController();
        }

        @Override
        public void setVolumePanel(VolumePanel panel) {
            mPanel = panel == null ? mDialogPanel : panel;
        }
    }
}
