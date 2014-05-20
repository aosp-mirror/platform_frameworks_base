package com.android.systemui.volume;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.IVolumeController;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.SystemUI;

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

    private AudioManager mAudioManager;
    private VolumeController mVolumeController;

    @Override
    public void start() {
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        updateController();
        mContext.getContentResolver().registerContentObserver(SETTING_URI, false, mObserver);
    }

    private void updateController() {
        if (Settings.Global.getInt(mContext.getContentResolver(), SETTING, DEFAULT) != 0) {
            if (mVolumeController == null) {
                mVolumeController = new VolumeController(mContext);
            }
            Log.d(TAG, "Registering volume controller");
            mAudioManager.setVolumeController(mVolumeController);
        } else {
            Log.d(TAG, "Unregistering volume controller");
            mAudioManager.setVolumeController(null);
        }
    }

    private final ContentObserver mObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange, Uri uri) {
            if (SETTING_URI.equals(uri)) {
                updateController();
            }
        }
    };

    /** For now, simply host an unmodified base volume panel in this process. */
    private final class VolumeController extends IVolumeController.Stub {
        private final VolumePanel mPanel;

        public VolumeController(Context context) {
            mPanel = new VolumePanel(context);
        }

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
            mPanel.setLayoutDirection(layoutDirection);
        }

        @Override
        public void dismiss() throws RemoteException {
            mPanel.postDismiss();
        }
    }
}
