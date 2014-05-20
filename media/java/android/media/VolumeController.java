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

package android.media;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Objects;

/**
 * Wraps the remote volume controller interface as a convenience to audio service.
 * @hide
 */
public class VolumeController {
    private static final String TAG = "VolumeController";

    private IVolumeController mController;

    public void setController(IVolumeController controller) {
        mController = controller;
    }

    public boolean isSameBinder(IVolumeController controller) {
        return Objects.equals(asBinder(), binder(controller));
    }

    public IBinder asBinder() {
        return binder(mController);
    }

    private static IBinder binder(IVolumeController controller) {
        return controller == null ? null : controller.asBinder();
    }

    @Override
    public String toString() {
        return "VolumeController(" + asBinder() + ")";
    }

    public void postHasNewRemotePlaybackInfo() {
        if (mController == null) return;
        try {
            mController.hasNewRemotePlaybackInfo();
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling hasNewRemotePlaybackInfo", e);
        }
    }

    public void postRemoteVolumeChanged(int streamType, int flags) {
        if (mController == null) return;
        try {
            mController.remoteVolumeChanged(streamType, flags);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling remoteVolumeChanged", e);
        }
    }

    public void postRemoteSliderVisibility(boolean visible) {
        if (mController == null) return;
        try {
            mController.remoteSliderVisibility(visible);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling remoteSliderVisibility", e);
        }
    }

    public void postDisplaySafeVolumeWarning(int flags) {
        if (mController == null) return;
        try {
            mController.displaySafeVolumeWarning(flags);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling displaySafeVolumeWarning", e);
        }
    }

    public void postVolumeChanged(int streamType, int flags) {
        if (mController == null) return;
        try {
            mController.volumeChanged(streamType, flags);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling volumeChanged", e);
        }
    }

    public void postMasterVolumeChanged(int flags) {
        if (mController == null) return;
        try {
            mController.masterVolumeChanged(flags);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling masterVolumeChanged", e);
        }
    }

    public void postMasterMuteChanged(int flags) {
        if (mController == null) return;
        try {
            mController.masterMuteChanged(flags);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling masterMuteChanged", e);
        }
    }

    public void setLayoutDirection(int layoutDirection) {
        if (mController == null) return;
        try {
            mController.setLayoutDirection(layoutDirection);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling setLayoutDirection", e);
        }
    }

    public void postDismiss() {
        if (mController == null) return;
        try {
            mController.dismiss();
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling dismiss", e);
        }
    }
}