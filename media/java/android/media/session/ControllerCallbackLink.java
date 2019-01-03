/*
 * Copyright 2018 The Android Open Source Project
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

package android.media.session;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.session.MediaSession.QueueItem;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.List;

/**
 * Handles incoming commands to {@link MediaController.Callback}.
 * @hide
 */
@SystemApi
public final class ControllerCallbackLink implements Parcelable {
    CallbackStub mCallbackStub;
    ISessionControllerCallback mIControllerCallback;

    /**
     * Creator for stub (Callee)
     */
    public ControllerCallbackLink(@NonNull CallbackStub callbackStub) {
        mCallbackStub = callbackStub;
        mIControllerCallback = new CallbackStubProxy();
    }

    /**
     * Creator for interface (Caller)
     */
    ControllerCallbackLink(Parcel in) {
        mCallbackStub = null;
        mIControllerCallback = ISessionControllerCallback.Stub.asInterface(in.readStrongBinder());
    }

    /** Interface method for ISessionControllerCallback.notifySessionDestroyed */
    public void notifySessionDestroyed() {
        try {
            mIControllerCallback.notifySessionDestroyed();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionControllerCallback.notifyEvent */
    public void notifyEvent(String event, Bundle extras) {
        try {
            mIControllerCallback.notifyEvent(event, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionControllerCallback.notifyPlaybackStateChanged */
    public void notifyPlaybackStateChanged(PlaybackState state) {
        try {
            mIControllerCallback.notifyPlaybackStateChanged(state);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionControllerCallback.notifyMetadataChanged */
    public void notifyMetadataChanged(MediaMetadata metadata) {
        try {
            mIControllerCallback.notifyMetadataChanged(metadata);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionControllerCallback.notifyQueueChanged */
    public void notifyQueueChanged(List<QueueItem> queue) {
        try {
            mIControllerCallback.notifyQueueChanged(queue);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionControllerCallback.notifyQueueTitleChanged */
    public void notifyQueueTitleChanged(CharSequence title) {
        try {
            mIControllerCallback.notifyQueueTitleChanged(title);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionControllerCallback.notifyExtrasChanged */
    public void notifyExtrasChanged(Bundle extras) {
        try {
            mIControllerCallback.notifyExtrasChanged(extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Interface method for ISessionControllerCallback.notifyVolumeInfoChanged */
    public void notifyVolumeInfoChanged(int volumeType, AudioAttributes attrs, int controlType,
            int maxVolume, int currentVolume) {
        try {
            mIControllerCallback.notifyVolumeInfoChanged(volumeType, attrs, controlType, maxVolume,
                    currentVolume);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** Gets the binder */
    public IBinder getBinder() {
        return mIControllerCallback.asBinder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mIControllerCallback.asBinder());
    }

    public static final Parcelable.Creator<ControllerCallbackLink> CREATOR =
            new Parcelable.Creator<ControllerCallbackLink>() {
        @Override
        public ControllerCallbackLink createFromParcel(Parcel in) {
            return new ControllerCallbackLink(in);
        }

        @Override
        public ControllerCallbackLink[] newArray(int size) {
            return new ControllerCallbackLink[size];
        }
    };

    /**
     * Class for Stub implementation
     */
    public abstract static class CallbackStub {
        /** Stub method for ISessionControllerCallback.notifySessionDestroyed */
        public void onSessionDestroyed() {
        }

        /** Stub method for ISessionControllerCallback.notifyEvent */
        public void onEvent(String event, Bundle extras) {
        }

        /** Stub method for ISessionControllerCallback.notifyPlaybackStateChanged */
        public void onPlaybackStateChanged(PlaybackState state) {
        }

        /** Stub method for ISessionControllerCallback.notifyMetadataChanged */
        public void onMetadataChanged(MediaMetadata metadata) {
        }

        /** Stub method for ISessionControllerCallback.notifyQueueChanged */
        public void onQueueChanged(List<QueueItem> queue) {
        }

        /** Stub method for ISessionControllerCallback.notifyQueueTitleChanged */
        public void onQueueTitleChanged(CharSequence title) {
        }

        /** Stub method for ISessionControllerCallback.notifyExtrasChanged */
        public void onExtrasChanged(Bundle extras) {
        }

        /** Stub method for ISessionControllerCallback.notifyVolumeInfoChanged */
        public void onVolumeInfoChanged(int volumeType, AudioAttributes attrs, int controlType,
                int maxVolume, int currentVolume) {
        }
    }

    private class CallbackStubProxy extends ISessionControllerCallback.Stub {
        @Override
        public void notifyEvent(String event, Bundle extras) {
            mCallbackStub.onEvent(event, extras);
        }

        @Override
        public void notifySessionDestroyed() {
            mCallbackStub.onSessionDestroyed();
        }

        @Override
        public void notifyPlaybackStateChanged(PlaybackState state) {
            mCallbackStub.onPlaybackStateChanged(state);
        }

        @Override
        public void notifyMetadataChanged(MediaMetadata metadata) {
            mCallbackStub.onMetadataChanged(metadata);
        }

        @Override
        public void notifyQueueChanged(List<QueueItem> queue) {
            mCallbackStub.onQueueChanged(queue);
        }

        @Override
        public void notifyQueueTitleChanged(CharSequence title) {
            mCallbackStub.onQueueTitleChanged(title);
        }

        @Override
        public void notifyExtrasChanged(Bundle extras) {
            mCallbackStub.onExtrasChanged(extras);
        }

        @Override
        public void notifyVolumeInfoChanged(int volumeType, AudioAttributes attrs, int controlType,
                int maxVolume, int currentVolume) {
            mCallbackStub.onVolumeInfoChanged(volumeType, attrs, controlType, maxVolume,
                    currentVolume);
        }
    }
}
